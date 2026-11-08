#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成履迹（Trace）离线分类资源。

产出（写入 android/TraceApp/app/src/main/assets/lexicon/）：
  emotion_lexicon.tsv  词 \t 小类码 \t 强度1-9 \t 极性1正/2负/0中 \t 词性码 \t 来源c/d
  word_exclusions.tsv  含情绪字但不表达情绪的词（单字词匹配时跳过）
  degree_words.tsv     程度副词 \t 权重
  negation.txt         否定词（每行一个）
  event_patterns.tsv   kind \t id \t 触发词     (kind: domain|type)
  taxonomy.json        两级枚举定义（前端直接消费）

词表来源与合并优先级
  1) tools/emotion_core.tsv —— 自建口语核心表，覆盖个人语音日记的真实表达，优先
  2) 大连理工大学信息检索研究室《情感词汇本体》（徐琳宏,林鸿飞,潘宇,等. 情报学报, 2008）
     学术研究免费；商业用途需联系 irlab@dlut.edu.cn。本脚本只做格式转换与裁剪。

极性说明：DUTIR 原表的「极性」列大量为空（高兴/难过/踏实 都是 0），不可用。
          因此极性统一由情感大类推导：乐·好→正，怒·哀·惧·恶→负，惊→中。

换词表只需保持五列格式替换 emotion_lexicon.tsv，Kotlin 侧不动。
"""
import csv
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, ".tmp", "lex", "dutir.csv")
CORE = os.path.join(ROOT, "tools", "emotion_core.tsv")
OUT = os.path.join(ROOT, "android", "TraceApp", "app", "src", "main", "assets", "lexicon")

# ---------- 情绪：21 小类 → 7 大类 ----------
EMO_L2 = {
    "PA": ("快乐", "joy"), "PE": ("安心", "joy"),
    "PD": ("尊敬", "good"), "PH": ("赞扬", "good"), "PG": ("信任", "good"),
    "PB": ("喜爱", "good"), "PK": ("祝愿", "good"),
    "NA": ("愤怒", "anger"),
    "NB": ("悲伤", "sad"), "NJ": ("失望", "sad"), "NH": ("愧疚", "sad"), "PF": ("思念", "sad"),
    "NI": ("焦虑", "fear"), "NC": ("恐惧", "fear"), "NG": ("羞耻", "fear"),
    "NE": ("烦闷", "disgust"), "ND": ("憎恶", "disgust"), "NN": ("贬责", "disgust"),
    "NK": ("妒忌", "disgust"), "NL": ("怀疑", "disgust"),
    "PC": ("惊讶", "surprise"),
}
EMO_L1 = {
    "joy": ("乐", "#d97757"), "good": ("好", "#c9a227"), "anger": ("怒", "#c0392b"),
    "sad": ("哀", "#4a6fa5"), "fear": ("惧", "#7d5ba6"), "disgust": ("恶", "#6b7280"),
    "surprise": ("惊", "#2f9e8f"),
}
POLARITY_OF_L1 = {"joy": 1, "good": 1, "anger": 2, "sad": 2, "fear": 2,
                  "disgust": 2, "surprise": 0}
POS_MAP = {"n": 0.6, "v": 1.0, "a": 1.0, "d": 0.9, "i": 0.75, "o": 0.8}

# 含情绪字但本身不表达情绪的词：单字词匹配时若落入这些窗口就跳过。
# 只收「明显中性」的组合，宁缺勿滥——把「堵车」「劳累」这类真情绪放过去。
EXCLUSIONS = [
    "积累", "累计", "连累", "拖累", "受累", "累积", "累加", "累及",
    "爽约", "秋高气爽", "毫发不爽", "屡试不爽",
    "烦请", "烦劳",
    "困难", "困惑", "困住", "围困", "困局",
    "堵塞", "堵住",
    "酸甜", "酸辣", "酸奶",
    "棒球", "木棒", "棒子", "棒状",
    "牛奶", "牛肉", "牛油", "牛仔", "蜗牛",
    "多亏",
    "没关系", "不要紧",
]


def norm_intensity(raw):
    try:
        v = float(raw)
    except (TypeError, ValueError):
        return None
    if not 1 <= v <= 9:
        return None
    odd = int(round((v - 1) / 2.0)) * 2 + 1
    return max(1, min(9, odd))


def read_core():
    rows = {}
    if not os.path.exists(CORE):
        return rows
    with open(CORE, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            p = line.split("\t")
            if len(p) < 4:
                continue
            word, cat = p[0].strip(), p[1].strip()
            if cat not in EMO_L2 or not word:
                continue
            inten = norm_intensity(p[2])
            if inten is None:
                continue
            pos = (p[3].strip() or "o")[0].lower()
            rows[word] = (word, cat, inten, pos if pos in POS_MAP else "o")
    return rows


def read_dutir():
    rows = {}
    with open(SRC, encoding="utf-8") as f:
        r = csv.reader(f)
        next(r)
        for x in r:
            if len(x) < 7:
                continue
            word, cat = x[0].strip(), x[4].strip()
            if not word or not (2 <= len(word) <= 6) or cat not in EMO_L2:
                continue
            inten = norm_intensity(x[5])
            if inten is None:
                continue
            pos = (x[1].strip() or "o")[0].lower()
            cur = rows.get(word)
            if cur is None or inten > cur[2]:
                rows[word] = (word, cat, inten, pos if pos in POS_MAP else "o")
    return rows


def build_emotion():
    core = read_core()
    dutir = read_dutir()
    merged = dict(dutir)
    merged.update(core)          # 核心表覆盖同名 DUTIR 条目

    lines = []
    for word, (w, cat, inten, pos) in merged.items():
        pol = POLARITY_OF_L1[EMO_L2[cat][1]]
        src = "c" if word in core else "d"
        lines.append((word, cat, inten, pol, pos, src))

    # 长词优先落盘，便于人工核对；实际匹配由 Kotlin 按长度分桶
    lines.sort(key=lambda t: (-len(t[0]), t[0]))
    path = os.path.join(OUT, "emotion_lexicon.tsv")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 词\t小类码\t强度\t极性1正/2负/0中\t词性\t来源(c=核心 d=DUTIR)\n")
        for row in lines:
            f.write("\t".join(str(v) for v in row) + "\n")

    path2 = os.path.join(OUT, "word_exclusions.tsv")
    with open(path2, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 含情绪字但不表达情绪的词，单字词落在这些窗口内时跳过\n")
        for w in EXCLUSIONS:
            f.write(w + "\n")

    return len(lines), len(core), os.path.getsize(path), len(EXCLUSIONS)


# ---------- 程度副词：三档 ----------
DEGREE_TIERS = {
    2.0: ["极度", "极为", "极度地", "万分", "格外", "分外", "异常", "绝顶", "最为", "极其"],
    1.5: ["太", "非常", "特别", "十分", "相当", "很", "挺", "真", "真的", "实在",
          "确实", "超级", "超", "蛮", "颇", "颇为", "尤其", "最为", "特", "老"],
    0.7: ["比较", "较为", "较", "还算", "稍微", "稍稍", "略微", "略", "有点", "有些",
          "一点", "一些", "略为", "有点儿", "多少有点"],
}

NEGATION = ["不", "没", "没有", "无", "非", "未", "别", "莫", "勿", "甭", "毫无", "并非",
            "并不", "不再", "从不", "从未", "绝不", "决不", "算不上", "谈不上", "不怎么",
            "不太", "不算", "不是", "不会", "不能", "不想", "没什么", "哪有"]


def build_degree():
    path = os.path.join(OUT, "degree_words.tsv")
    n = 0
    seen = set()
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 程度副词\t权重\n")
        for weight in (2.0, 1.5, 0.7):
            for w in DEGREE_TIERS[weight]:
                if w in seen:
                    continue
                seen.add(w)
                f.write(f"{w}\t{weight}\n")
                n += 1
    path2 = os.path.join(OUT, "negation.txt")
    with open(path2, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 否定词：命中则翻转情感极性\n")
        for w in NEGATION:
            f.write(w + "\n")
    return n, len(NEGATION)


# ---------- 事件分类：domain 6 + type 8 ----------
DOMAINS = {
    "life": ["吃饭", "早点", "午饭", "晚饭", "夜宵", "吃", "睡觉", "起床", "洗澡", "洗衣服",
             "做饭", "点外卖", "外卖", "打扫", "收拾", "家务", "买菜", "超市", "快递",
             "通勤", "地铁", "公交", "打车", "开车", "堵车", "回家", "出门", "到家",
             "天气", "下雨", "下雪", "晴天", "周末", "假期", "放假", "旅游", "旅行",
             "电影", "追剧", "看剧", "综艺", "游戏", "逛街", "购物", "理发", "剪头",
             "宠物", "猫", "狗", "养花", "种花", "搬家", "装修", "睡懒觉", "午休"],
    "work": ["上班", "下班", "加班", "赶工", "项目", "会议", "开会", "汇报", "述职", "周报",
             "日报", "老板", "领导", "同事", "客户", "甲方", "需求", "上线", "发布",
             "代码", "编程", "调试", "debug", "重构", "测试", "部署", "接口", "数据",
             "论文", "作业", "考试", "复习", "预习", "上课", "课程", "课堂", "笔记",
             "实习", "面试", "简历", "offer", "求职", "招聘", "投递", "答辩", "组会",
             "导师", "实验室", "自习", "刷题", "背单词", "学习", "读书", "看书"],
    "relation": ["朋友", "好友", "兄弟", "闺蜜", "家人", "家里", "妈妈", "爸爸", "父母",
                 "老妈", "老爸", "姐姐", "哥哥", "弟弟", "妹妹", "爷爷", "奶奶", "外婆", "外公",
                 "对象", "男朋友", "女朋友", "老公", "老婆", "恋人", "孩子", "儿子", "女儿",
                 "老师", "同学", "室友", "邻居", "亲戚", "聊天", "聊了", "谈心",
                 "吵架", "冷战", "闹别扭", "和好", "表白", "分手", "复合",
                 "聚会", "聚餐", "约饭", "见面", "打电话", "视频", "相亲", "婚礼"],
    "health": ["累", "疲惫", "乏力", "失眠", "睡不着", "早醒", "熬夜", "通宵", "头疼", "头痛",
               "感冒", "发烧", "咳嗽", "嗓子", "胃疼", "肚子疼", "医院", "看病", "挂号",
               "吃药", "体检", "复查", "运动", "跑步", "健身", "锻炼", "瑜伽", "散步",
               "游泳", "打球", "爬山", "骑车", "拉伸", "疼", "难受", "不舒服", "减肥",
               "节食", "体重", "心率", "睡眠", "情绪", "压力", "焦虑", "放松", "冥想"],
    "money": ["花钱", "花销", "支出", "消费", "工资", "发工资", "薪水", "收入", "奖金",
              "报销", "账单", "房租", "房贷", "车贷", "还款", "信用卡", "花呗", "借呗",
              "存款", "存钱", "攒钱", "余额", "投资", "理财", "基金", "股票", "亏了",
              "赚了", "买", "买了", "下单", "付款", "退款", "退货", "贵", "便宜",
              "打折", "优惠", "预算", "超支", "省钱"],
    "idea": ["想法", "点子", "灵感", "构思", "设计", "方案", "思路", "反思", "复盘",
             "总结", "意识到", "发现", "想通", "明白", "懂了", "悟", "觉察", "思考",
             "琢磨", "计划", "目标", "愿望", "梦想", "价值观", "原则", "意义", "选择",
             "取舍", "纠结", "犹豫", "成长", "变化", "进步"],
}

TYPES = {
    "decision": ["决定", "确定", "定了", "选了", "选择了", "下定决心", "下决心", "决定不",
                 "放弃", "不做了", "算了", "取舍", "想清楚", "宁可", "与其",
                 "权衡", "纠结", "犹豫", "要不要", "该不该", "值不值"],
    "action": ["去了", "做了", "开始", "动手", "完成", "做完", "跑完", "写完", "交了",
               "提交", "发了", "发出", "买了", "练了", "整理", "搬", "出发", "回来",
               "试了", "尝试", "打卡", "坚持了"],
    "outcome": ["成功", "失败", "拿到", "通过", "没过", "过了", "挂了", "赢了", "输了",
                "得了", "获奖", "拿奖", "跑通", "搞定", "解决了", "拿到了", "晋级",
                "落选", "被拒", "结果", "最终", "终于", "差点"],
    "emotion": ["开心", "高兴", "快乐", "难过", "伤心", "生气", "愤怒", "焦虑", "紧张",
                "害怕", "委屈", "失落", "兴奋", "崩溃", "郁闷", "沮丧", "满足",
                "感动", "轻松", "压抑", "孤独", "踏实", "心安", "累", "烦"],
    "problem": ["问题", "卡住", "卡了", "搞不定", "不会", "做不了", "太难", "麻烦",
                "冲突", "矛盾", "出错", "报错", "崩了", "bug", "故障", "拖延",
                "来不及", "赶不上", "错过", "失败", "瓶颈", "搞砸"],
    "plan": ["打算", "准备", "计划", "明天", "下周", "下个月", "接下来", "之后要",
             "准备去", "要做", "目标", "安排", "争取", "希望能", "想要", "定个"],
    "insight": ["发现", "意识到", "原来", "其实", "想明白", "想通", "悟到", "觉察",
                "反思", "总结出", "明白", "看懂了", "关键是", "本质", "规律",
                "一直以为", "才发现", "才知道"],
    "fact": ["遇到", "看到", "看见", "听说", "发生", "碰到", "见到", "聊到", "路过",
             "收到", "接到", "得知", "有人", "外面", "据说"],
}


def build_patterns():
    path = os.path.join(OUT, "event_patterns.tsv")
    n = 0
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# kind\tid\t触发词\n")
        for did, words in DOMAINS.items():
            for w in sorted(set(words)):
                f.write(f"domain\t{did}\t{w}\n")
                n += 1
        for tid, words in TYPES.items():
            for w in sorted(set(words)):
                f.write(f"type\t{tid}\t{w}\n")
                n += 1
    return n


TAXONOMY = {
    "version": "1.1",
    "updated": "2026-11-07",
    "source": {
        "emotion": "自建口语核心表 + 大连理工大学《情感词汇本体》（徐琳宏等, 情报学报 2008）",
        "license": "DUTIR 学术研究免费；商业用途需联系 irlab@dlut.edu.cn。核心表可独立替换。",
    },
    "domains": [
        {"id": "life", "name": "生活日常", "color": "#5b8def", "icon": "home"},
        {"id": "work", "name": "工作学习", "color": "#6a737d", "icon": "briefcase"},
        {"id": "relation", "name": "关系社交", "color": "#d97757", "icon": "users"},
        {"id": "health", "name": "身心状态", "color": "#2f9e8f", "icon": "heart"},
        {"id": "money", "name": "财务消费", "color": "#c9a227", "icon": "wallet"},
        {"id": "idea", "name": "想法觉察", "color": "#7d5ba6", "icon": "bulb"},
    ],
    "types": [
        {"id": "decision", "name": "决定", "desc": "做了取舍或正在纠结要不要"},
        {"id": "action", "name": "行动", "desc": "实际做了什么"},
        {"id": "outcome", "name": "结果", "desc": "事情有了成败结局"},
        {"id": "emotion", "name": "情绪", "desc": "主要是在表达感受"},
        {"id": "problem", "name": "问题", "desc": "卡住、冲突、做不下去"},
        {"id": "plan", "name": "计划", "desc": "指向未来的打算"},
        {"id": "insight", "name": "觉察", "desc": "想明白了什么"},
        {"id": "fact", "name": "见闻", "desc": "中性叙述发生了什么"},
    ],
    "tense": [
        {"id": "past", "name": "已发生"}, {"id": "present", "name": "正在"},
        {"id": "future", "name": "将发生"},
    ],
    "modality": [
        {"id": "actual", "name": "确实"}, {"id": "intended", "name": "打算"},
        {"id": "hypothetical", "name": "假设"},
    ],
    "polarity": [
        {"id": "pos", "name": "正向"}, {"id": "neg", "name": "负向"}, {"id": "neu", "name": "中性"},
    ],
    "emotions": {
        "level1": [{"id": k, "name": v[0], "color": v[1]} for k, v in EMO_L1.items()],
        "level2": [{"id": k, "name": v[0], "parent": v[1]} for k, v in EMO_L2.items()],
    },
    "intensity_scale": {"min": 1, "max": 9, "steps": [1, 3, 5, 7, 9]},
}


def main():
    if not os.path.exists(SRC):
        sys.exit(f"缺少源词表：{SRC}")
    os.makedirs(OUT, exist_ok=True)
    n_emo, n_core, size, n_ex = build_emotion()
    n_deg, n_neg = build_degree()
    n_pat = build_patterns()
    path = os.path.join(OUT, "taxonomy.json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(TAXONOMY, f, ensure_ascii=False, indent=2)
    print(f"emotion_lexicon.tsv  {n_emo} 词（核心 {n_core}）  {size/1024:.0f} KB")
    print(f"word_exclusions.tsv  {n_ex} 条")
    print(f"degree_words.tsv     {n_deg} 词")
    print(f"negation.txt         {n_neg} 词")
    print(f"event_patterns.tsv   {n_pat} 条")
    print(f"taxonomy.json        {os.path.getsize(path)/1024:.0f} KB")


if __name__ == "__main__":
    main()
