#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线分类器的参考实现 + 评测。

用途：在移植到 Kotlin（Classifier.kt）之前，先用真实口语句子把权重调好。
Kotlin 侧必须与这里的逻辑一致，改动请两边同步。

算法要点（都是踩过的坑）
  1) 最长匹配优先：对每个位置尝试 6→1 字窗口，命中即停
  2) 单字词护栏：落在 word_exclusions.tsv 的窗口内则跳过（「积累」不算「累」）
  3) 否定词护栏：单字否定词若属于更长词就作废（「特别」的「别」、「不错」的「不」）
  4) 已匹配区间：修饰词扫描不能落进已被更长词占用的位置
  5) 前置修饰：向前 4 字取最近的程度副词与否定词
  6) 后置程度：「累死了」「压力好大」「烦得很」在情绪词之后，必须单独认
  7) 加权：score = 强度 × 程度权重 × 词性权重 × (否定 0.6)
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LEX = os.path.join(ROOT, "android", "TraceApp", "app", "src", "main",
                   "assets", "lexicon")

EMO_L1_OF_L2 = {
    "PA": "joy", "PE": "joy", "PD": "good", "PH": "good", "PG": "good",
    "PB": "good", "PK": "good", "NA": "anger",
    "NB": "sad", "NJ": "sad", "NH": "sad", "PF": "sad",
    "NI": "fear", "NC": "fear", "NG": "fear",
    "NE": "disgust", "ND": "disgust", "NN": "disgust", "NK": "disgust", "NL": "disgust",
    "PC": "surprise",
}
POS_WEIGHT = {"n": 0.6, "v": 1.0, "a": 1.0, "d": 0.9, "i": 0.75, "o": 0.8}
MAX_WORD = 6

# 单字否定词若出现在这些更长词里，就不是否定
NEG_GUARD = {
    "别": ["特别", "分别", "差别", "区别", "别人", "告别", "性别", "个别",
           "离别", "级别", "识别", "辨别", "类别", "派别", "别墅"],
    "无": ["无比", "无论", "无数", "无限", "无疑", "无意", "无法"],
    "非": ["非常", "非洲", "是非", "除非", "非要", "非得"],
    "未": ["未来", "未必", "未曾"],
    "不": ["不过", "不久", "不少", "不错", "不但", "不禁", "不得不", "不管",
           "不论", "不断", "不仅", "不免", "不由得", "不一会"],
    "没": ["没有"],   # 「没有」本身也是否定，保留生效
    "莫": ["莫名", "莫过于"],
    "甭": [],
}

# 后置程度：出现在情绪词之后
POST_DEGREE = {
    "死了": 9, "极了": 9, "坏了": 9, "爆了": 9, "到爆": 9, "疯": 9,
    "得很": 8, "得慌": 8, "不已": 8, "不行": 8, "要命": 8, "透顶": 9,
    "得很厉害": 8, "得要命": 9,
}
# 「好大」「太重」这类「程度副词 + 形容词」的后置结构
POST_ADJ = ["大", "重", "多", "高", "深", "久", "强", "狠", "凶"]


class Lexicon:
    def __init__(self):
        self.emo = {}
        self.exclusions = set()
        self.degree = {}
        self.negation = set()
        self.domain = {}
        self.etype = {}
        self._load()

    def _lines(self, name):
        path = os.path.join(LEX, name)
        if not os.path.exists(path):
            return
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if line and not line.startswith("#"):
                    yield line

    def _load(self):
        for line in self._lines("emotion_lexicon.tsv"):
            p = line.split("\t")
            if len(p) >= 6:
                self.emo[p[0]] = (p[1], int(p[2]), int(p[3]), p[4])
        for line in self._lines("word_exclusions.tsv"):
            self.exclusions.add(line)
        for line in self._lines("degree_words.tsv"):
            p = line.split("\t")
            if len(p) >= 2:
                self.degree[p[0]] = float(p[1])
        for line in self._lines("negation.txt"):
            self.negation.add(line)
        for line in self._lines("event_patterns.tsv"):
            p = line.split("\t")
            if len(p) >= 3:
                bucket = self.domain if p[0] == "domain" else self.etype
                bucket.setdefault(p[1], []).append(p[2])

    @property
    def max_neg_len(self):
        return max(len(w) for w in self.negation)


LEX = Lexicon()


def _in_spans(j, spans):
    for s, e in spans:
        if s <= j < e:
            return True
    return False


def _near(text, i, table, spans, max_back=4, max_len=3):
    """在 i 之前找最近的表项，跳过已被更长词占用的位置。"""
    start = max(0, i - max_back)
    for dist in range(1, i - start + 1):
        j = i - dist
        if _in_spans(j, spans):
            continue
        for ln in range(min(max_len, i - j), 0, -1):
            w = text[j:j + ln]
            if w in table:
                return w, table[w], dist - ln + 1
    return None


def _negated(text, i, spans):
    """i 之前是否有否定词；对单字否定词做护栏检查。

    护栏必须向两侧都看：命中「别」时，它既可能是「别去」的别（j 起头），
    也可能是「特别」的别（j-1 起头）。所以扫 j-3..j 起头的 2-4 字窗口。
    """
    start = max(0, i - 4)
    for dist in range(1, i - start + 1):
        j = i - dist
        if _in_spans(j, spans):
            continue
        for ln in range(min(LEX.max_neg_len, i - j), 0, -1):
            w = text[j:j + ln]
            if w not in LEX.negation:
                continue
            if len(w) == 1:
                guard = NEG_GUARD.get(w, [])
                hit_guard = False
                for st in range(max(0, j - 3), j + 1):
                    for gl in range(2, 5):
                        if text[st:st + gl] in guard:
                            hit_guard = True
                            break
                    if hit_guard:
                        break
                if hit_guard:
                    continue        # 这个位置不算否定，继续往前找
            return True
    return False


def _post_degree(text, end):
    """情绪词之后的程度表达。"""
    tail = text[end:end + 3]
    best = None
    for w, val in POST_DEGREE.items():
        if tail.startswith(w):
            best = val if best is None else max(best, val)
    if best is None and len(text) > end + 1:
        c = text[end]
        if c in ("好", "很", "太", "超") and text[end + 1] in POST_ADJ:
            best = 8
        elif c in ("得",) and text[end + 1] in POST_ADJ:
            best = 7
    return best


def _scan(text):
    """最长匹配扫描，返回 [(start, word, len)]。"""
    out = []
    i, n = 0, len(text)
    while i < n:
        hit = None
        for ln in range(min(MAX_WORD, n - i), 0, -1):
            w = text[i:i + ln]
            if w not in LEX.emo:
                continue
            if ln == 1:
                guard = False
                for off in (-2, -1, 0, 1):
                    s = i + off
                    if s >= 0 and s + 2 <= n and text[s:s + 2] in LEX.exclusions:
                        guard = True
                        break
                if guard:
                    break
            hit = (i, w, ln)
            break
        if hit:
            out.append(hit)
            i += hit[2]
        else:
            i += 1
    return out


def analyze_emotion(text):
    scans = _scan(text)
    spans = [(s, s + ln) for s, _, ln in scans]
    hits = []
    for s, w, ln in scans:
        l2, inten, pol0, pos = LEX.emo[w]
        end = s + ln
        deg = _near(text, s, LEX.degree, spans)
        is_neg = _negated(text, s, spans)
        dw = deg[1] if deg else 1.0
        post = _post_degree(text, end)
        eff = max(inten, post) if post else inten
        if is_neg:
            # 被否定的情绪通常是弱化的（「不太开心」轻于「难过」），
            # 此时不再吃程度副词加成，避免「不太开心」被算成强负面。
            eff = max(3, round(eff * 0.5))
            score = eff * POS_WEIGHT.get(pos, 0.8)
        else:
            eff = min(9, round(eff * dw))
            score = eff * POS_WEIGHT.get(pos, 0.8)
        pol = pol0
        if is_neg and pol0 != 0:
            pol = 3 - pol0
        hits.append({"word": w, "l2": l2, "l1": EMO_L1_OF_L2[l2],
                     "intensity": eff, "polarity": pol,
                     "score": round(score, 2), "negated": is_neg,
                     "degree": dw if deg else None, "pos": s})

    if not hits:
        return {"primary": None, "secondary": [], "sentiment": 0.0, "hits": []}

    hits.sort(key=lambda h: -h["score"])
    top = hits[0]
    # 同小类取最大强度（求大会超 9），其余进辅助
    same = [h for h in hits[1:] if h["l2"] == top["l2"]]
    primary_int = max([top["intensity"]] + [h["intensity"] for h in same])
    secondary = []
    for h in hits[1:]:
        if h["l2"] != top["l2"] and len(secondary) < 3:
            secondary.append({k: h[k] for k in ("word", "l1", "l2", "intensity", "polarity")})
    primary = {k: top[k] for k in ("word", "l1", "l2", "polarity", "negated")}
    primary["intensity"] = primary_int

    pos_sum = sum(h["score"] for h in hits if h["polarity"] == 1)
    neg_sum = sum(h["score"] for h in hits if h["polarity"] == 2)
    total = pos_sum + neg_sum
    sentiment = 0.0 if total == 0 else round((pos_sum - neg_sum) / total, 2)
    return {"primary": primary, "secondary": secondary,
            "sentiment": sentiment, "hits": hits}


def classify_event(text):
    def score(d):
        out = {}
        for k, words in d.items():
            s = sum(len(w) for w in words if w in text)
            if s:
                out[k] = s
        return sorted(out.items(), key=lambda t: -t[1])

    dom = score(LEX.domain)
    typ = score(LEX.etype)
    return {"domain": dom[0][0] if dom else None, "domain_rank": dom[:3],
            "type": typ[0][0] if typ else None, "type_rank": typ[:3]}


CASES = [
    ("今天特别累，加班到十一点", "NE", 2, 7),
    ("拿奖了，但没想象中那么开心", "PA", None, None),
    ("有点焦虑，怕做不完", "NI", 2, 5),
    ("下午跟朋友聊了很久，心里舒服多了", "PA", 1, None),
    ("不想干了，太烦了", "NE", 2, 9),
    ("妈妈打电话来，问我最近怎么样", None, 0, None),
    ("项目上线成功了，松了一口气", None, 1, None),
    ("有点委屈，但不想说", "NB", 2, 5),
    ("明天要交论文，压力好大", "NI", 2, 8),
    ("跟他吵架了，很生气", "NA", 2, 7),
    ("今天天气很好，出去走了走", None, None, None),
    ("终于搞定了，太爽了", "PA", 1, 9),
    ("不太开心", "PA", 2, None),
    ("一点也不累", "NE", 1, None),
    ("他夸我做得不错，挺开心的", "PA", 1, 7),
    ("没拿到奖，有点失落", "NB", 2, 5),
    ("就是这么回事，大概吧", None, 0, None),
    ("失眠到三点，脑子停不下来", "NE", 2, None),
    ("今天积累了很多素材", None, None, None),
    ("他爽约了，我很失望", "NJ", 2, 7),
]


def main():
    ok = 0
    print(f"{'句子':30} {'主':>4} {'期望':>4} {'极':>3} {'望':>3} {'强':>3} {'望':>3} 判定")
    print("-" * 96)
    for text, want_l2, want_pol, want_int in CASES:
        r = analyze_emotion(text)
        p = r["primary"]
        got_l2 = p["l2"] if p else None
        got_pol = p["polarity"] if p else 0
        got_int = p["intensity"] if p else 0
        good = True
        if want_l2 is not None and got_l2 != want_l2:
            good = False
        if want_pol is not None and got_pol != want_pol:
            good = False
        if want_int is not None and abs(got_int - want_int) > 2:
            good = False
        ok += good
        print(f"{text[:28]:30} {str(got_l2):>4} {str(want_l2):>4} "
              f"{got_pol:>3} {str(want_pol):>3} {got_int:>3} {str(want_int):>3} "
              f"{'OK' if good else 'NG'} [{p['word'] if p else ''}] {r['sentiment']}")
    print("-" * 96)
    print(f"情绪 {ok}/{len(CASES)} 通过\n")

    print("事件分类：")
    for text in ["今天特别累，加班到十一点", "明天要交论文，压力好大",
                 "跟他吵架了，很生气", "拿奖了，但没想象中那么开心",
                 "花了三百块买了双鞋", "突然想明白了一件事"]:
        r = classify_event(text)
        e = analyze_emotion(text)
        print(f"  {text[:24]:26} domain={str(r['domain']):9} type={str(r['type']):10} "
              f"emo={e['primary']['l2'] if e['primary'] else '-':4} "
              f"int={e['primary']['intensity'] if e['primary'] else 0}")


if __name__ == "__main__":
    main()
