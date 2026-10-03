#!/usr/bin/env python3
"""合并 ocr-01..07 扫描批次 → merged-ocr-findings.json + coverage.tsv
用法: python merge-ocr.py  (在本目录运行)
只做本地纯计算，零 LLM 配额。"""
import json, glob, hashlib, io, sys, os

HERE = os.path.dirname(os.path.abspath(__file__))
BATCHES = ["01-voice","02-ui","03-chat-cloud","04-agui-net","05-util-device-pet-root","06-recovery","07-rescue","08a","08b","08c","08d","08e"]
EXPECTED = 105  # 源码文件总数(管理者实测)

comments, seen = [], set()
coverage = {}   # path -> {batches:[], comments:int, errors:[]}
for b in BATCHES:
    p = os.path.join(HERE, f"ocr-{b}.json")
    if not os.path.exists(p):
        print(f"[skip] ocr-{b}.json 不存在"); continue
    d = json.load(open(p, encoding="utf-8"))
    for c in d.get("comments", []):
        key = hashlib.sha1(json.dumps(
            {k: c.get(k) for k in ("path","start_line","end_line","content")},
            ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        if key in seen: continue
        seen.add(key)
        comments.append({**{k: c.get(k) for k in
            ("path","start_line","end_line","content","suggestion_code","existing_code","category","severity")},
            "batch": b})
        coverage.setdefault(c["path"], {"batches": [], "comments": 0})
        coverage[c["path"]]["comments"] += 1
    errlog = os.path.join(HERE, f"ocr-{b}.log")
    if os.path.exists(errlog):
        for line in open(errlog, encoding="utf-8", errors="replace"):
            if "Scan subtask error" in line:
                m = line.split("Scan subtask error for")[-1].split("(")[0].strip()
                path = m if m.startswith("app/") else "app/src/main/java/com/xiaoduo/deskpet/" + m.lstrip()
                coverage.setdefault(path, {"batches": [], "comments": 0})
                coverage[path].setdefault("errors", []).append(b)

for path, info in coverage.items():
    pass
# batches 列表: 该文件出现在哪个批次的 comments 或 error 里
for b in BATCHES:
    p = os.path.join(HERE, f"ocr-{b}.json")
    if not os.path.exists(p): continue
    d = json.load(open(p, encoding="utf-8"))
    for c in d.get("comments", []):
        if c["path"] in coverage and b not in coverage[c["path"]]["batches"]:
            coverage[c["path"]]["batches"].append(b)

by_sev = {}
for c in comments:
    by_sev[c["severity"]] = by_sev.get(c["severity"], 0) + 1

uncovered = [p for p in coverage if not coverage[p]["comments"]]
# 真疑似部分评审: 同一批次内既有 error 又有该批产出的 comments
# (跨批 error=旧批失败已被后续批补扫, 不算)
comment_batch_by_path = {}
for c in comments:
    comment_batch_by_path.setdefault(c["path"], set()).add(c["batch"])
suspect = [p for p in coverage if coverage[p].get("errors") and
           (comment_batch_by_path.get(p, set()) & set(coverage[p]["errors"]))]
print(f"合并 comments: {len(comments)} (去重后)")
print(f"severity 分布: {by_sev}")
print(f"覆盖文件数: {len([p for p in coverage if coverage[p]['comments']])} / {EXPECTED}")
print(f"疑似部分评审(有comments也有error): {len(suspect)}")
for p in sorted(suspect): print("  SUSPECT:", p, coverage[p]["errors"])
if uncovered:
    print("零/失败覆盖文件:")
    for p in sorted(uncovered): print("  ", p, coverage[p].get("errors", ""))
json.dump({"suspect_partial": suspect, "uncovered": uncovered},
          open(os.path.join(HERE, "coverage-flags.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)

json.dump({"merged_comments": comments, "severity_dist": by_sev,
           "total": len(comments)}, open(os.path.join(HERE, "merged-ocr-findings.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
with io.open(os.path.join(HERE, "coverage.tsv"), "w", encoding="utf-8") as f:
    f.write("path\tcomment_count\tbatches\terrors\n")
    for p in sorted(coverage):
        i = coverage[p]
        f.write(f"{p}\t{i['comments']}\t{','.join(i['batches'])}\t{','.join(i.get('errors',[]))}\n")
print("写出: merged-ocr-findings.json, coverage.tsv")
