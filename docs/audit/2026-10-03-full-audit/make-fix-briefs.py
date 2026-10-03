#!/usr/bin/env python3
"""按文件归属把 P2(除分发形态三件套)切成三份修复简报。零 LLM,纯本地。"""
import json, os, collections

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
EXCLUDE_IDS = {"SEC-1", "SEC-4"}          # TLS 白名单 / BuildConfig 密钥 → 分发形态决策包
EXCLUDE_TOPICS = ("debug 签名", "debug签名")  # release 签名条(非代码面 F-P2-1)

def files_of(item):
    out = set()
    for r in item.get("refs") or []:
        if isinstance(r, str):
            out.add(r.split(":")[0])
        elif isinstance(r, dict):
            p = r.get("path") or r.get("file")
            if p: out.add(p)
    return sorted(out)

def pkg_of(paths):
    for p in paths:
        seg = p.replace("\\", "/")
        if "/voice/" in seg or "/agui/" in seg: return "voice"  # agui 与 voice 的 SSE 链耦合,整段划 voice 防跨包改同一文件
        if "/ui/" in seg: return "ui"
    return "misc"  # chat/cloud/net/device/util/根文件/非代码

d = json.load(open(os.path.join(HERE, "p2-p3-detail.json"), encoding="utf-8"))
buckets = collections.defaultdict(list)
excluded = []
for x in d["p2"]:
    title = str(x.get("title", ""))
    if x["id"] in EXCLUDE_IDS or any(t in title for t in EXCLUDE_TOPICS):
        excluded.append(x); continue
    buckets[pkg_of(files_of(x))].append(x)

for pkg, items in buckets.items():
    lines = [f"# 修复简报 · {pkg} 包(P2 共 {len(items)} 条)\n",
             "> 来源:全量审计 2026-10-03(unified-findings.md)。字段原样取自 p2-p3-detail.json,行号未改动。",
             "> 逐条:先在源码验证问题属实(不属实→记「证伪跳过」+理由),属实→先写失败测试(TDD)→最小修复→测试转绿。"
             f"禁改本简报地界外的文件。SEC-3 若在你的地界:注意 prefs 迁移要兼容板端存量数据。\n"]
    for x in items:
        lines.append("---\n")
        lines.append(f"## {x['id']}  [{','.join(x.get('channels', []))}]{'  〔待复诊〕' if x.get('needs_human_review') else ''}")
        lines.append(f"- 标题: {x.get('title','')}")
        lines.append(f"- 文件:行号: {', '.join(files_of(x)) or '(见 problem 内引用)'}")
        lines.append(f"- 问题: {x.get('problem','')}")
        lines.append(f"- 影响: {x.get('impact','')}")
        lines.append(f"- 修法建议: {x.get('fix','')}")
        if x.get("review_note"): lines.append(f"- 复诊备注: {x['review_note']}")
        lines.append(f"- ocr 索引: {x.get('ocr_indices')}")
    out = os.path.join(HERE, f"fix-brief-{pkg}.md")
    open(out, "w", encoding="utf-8").write("\n".join(lines))
    print(f"{pkg}: {len(items)} 条 → fix-brief-{pkg}.md ({sorted({os.path.basename(f) for x in items for f in files_of(x)})})")
print("excluded(分发形态):", [x["id"] for x in excluded])
print("total in-scope:", sum(len(v) for v in buckets.values()))
