# -*- coding: utf-8 -*-
"""desk-pet 包间依赖分析（2026-10-10 重构 Spec 输入）。
扫描主源/测试 Kotlin 文件：声明包、内部 import、LOC、android 依赖、包间矩阵、循环检测。
"""
import json
import os
import re
import sys
from collections import defaultdict

ROOT = r"E:\desk-pet-android"
SRC_MAIN = os.path.join(ROOT, "app", "src", "main", "java")
SRC_TEST = os.path.join(ROOT, "app", "src", "test")
PKG_PREFIX = "com.xiaoduo.deskpet"

# root 枢纽白名单（refactor-structure.spec §4.3）：这 3 个 app 层类型的被引用边
# 是登记在案的例外，不计入环检测，但单独计数（节点名 "(root-hub)"）。
WHITELIST_ROOT_TYPES = {"PetOverlayService", "PetOverlayState", "VoiceForegroundService"}

PKG_RE = re.compile(r"^\s*package\s+([\w.]+)")
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)")


def scan(root):
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for name in names:
            if name.endswith(".kt"):
                files.append(os.path.join(dirpath, name))
    return sorted(files)


def analyze_file(path):
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    pkg = None
    imports = set()
    for line in lines:
        if pkg is None:
            m = PKG_RE.match(line)
            if m:
                pkg = m.group(1)
        m = IMPORT_RE.match(line)
        if m:
            imports.add(m.group(1))
    loc = len([l for l in lines if l.strip()])
    code = "\n".join(lines)
    return {
        "path": os.path.relpath(path, ROOT).replace("\\", "/"),
        "pkg": pkg,
        "imports": sorted(imports),
        "loc": loc,
        "has_android": bool(re.search(r"\bandroid\.", code)),
        "has_androidx": bool(re.search(r"\bandroidx\.", code)),
        "has_compose": bool(re.search(r"\bandroidx\.compose\.", code)),
        "has_coroutines": bool(re.search(r"\bkotlinx\.coroutines\.", code)),
    }


def pkg_group(pkg):
    if not pkg or not pkg.startswith(PKG_PREFIX):
        return "(external)"
    rest = pkg[len(PKG_PREFIX):].lstrip(".")
    if not rest:
        return "(root)"
    return rest.split(".")[0]


def import_group(imp):
    """import 目标分组：与 pkg_group 对称——root 包的类（如 PetOverlayState，
    com.xiaoduo.deskpet.X 无中间段）归 (root)，否则取首个子包段。
    v1 把 root 类当独立伪节点，致 ui↔root 等环漏检（对抗评审 #2 修正）。"""
    if not imp.startswith(PKG_PREFIX):
        return "(external)"
    rest = imp[len(PKG_PREFIX):].lstrip(".")
    if not rest or "." not in rest:
        if rest in WHITELIST_ROOT_TYPES:
            return "(root-hub)"
        if rest == "BuildConfig":
            return "(buildgen)"  # 生成的构建产物，非 root 源码类；单独计数（例外边，module 化门槛 2 相关）
        return "(root)"
    return rest.split(".")[0]


def main():
    main_files = [analyze_file(p) for p in scan(SRC_MAIN)]
    test_files = [analyze_file(p) for p in scan(SRC_TEST)] if os.path.isdir(SRC_TEST) else []

    for f in main_files + test_files:
        f["group"] = pkg_group(f["pkg"])
        f["internal"] = sorted(
            {i for i in f["imports"] if i.startswith(PKG_PREFIX) and i != f["pkg"]}
        )

    groups = sorted({f["group"] for f in main_files})
    matrix = {g: defaultdict(int) for g in groups}
    matrix_files = {g: defaultdict(set) for g in groups}
    for f in main_files:
        for imp in f["internal"]:
            tgt = import_group(imp)
            if tgt != f["group"]:
                matrix[f["group"]][tgt] += 1
                matrix_files[f["group"]][tgt].add(f["path"])

    # 循环检测（包级，DFS 三色）
    cycles = []
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {g: WHITE for g in groups}
    stack = []

    def dfs(u):
        color[u] = GRAY
        stack.append(u)
        for v in matrix[u]:
            if v not in color:
                continue
            if color[v] == GRAY:
                i = stack.index(v)
                cycles.append(stack[i:] + [v])
            elif color[v] == WHITE:
                dfs(v)
        stack.pop()
        color[u] = BLACK

    for g in groups:
        if color[g] == WHITE:
            dfs(g)

    # god class 候选
    big = sorted(main_files, key=lambda f: -f["loc"])[:25]
    # 声明包与目录不符的异常
    anomalies = []
    for f in main_files:
        expect = "app/src/main/java/" + (f["pkg"] or "").replace(".", "/")
        if not f["path"].startswith(expect):
            anomalies.append({"path": f["path"], "pkg": f["pkg"]})

    summary = {
        "main_total": len(main_files),
        "test_total": len(test_files),
        "loc_total": sum(f["loc"] for f in main_files),
        "groups": {
            g: {
                "files": sum(1 for f in main_files if f["group"] == g),
                "loc": sum(f["loc"] for f in main_files if f["group"] == g),
                "android": sum(1 for f in main_files if f["group"] == g and f["has_android"]),
                "compose": sum(1 for f in main_files if f["group"] == g and f["has_compose"]),
                "coroutines": sum(1 for f in main_files if f["group"] == g and f["has_coroutines"]),
            }
            for g in groups
        },
        "matrix": {g: dict(matrix[g]) for g in groups},
        "matrix_filelinks": {
            g: {t: sorted(matrix_files[g][t]) for t in matrix_files[g]} for g in groups
        },
        "cycles": cycles,
        "top25_files": [
            {"path": f["path"], "loc": f["loc"], "pkg": f["pkg"]} for f in big
        ],
        "anomalies": anomalies,
        "test_by_group": {
            g: sum(1 for f in test_files if f["group"] == g) for g in
            sorted({f["group"] for f in test_files})
        },
        "root_files": [f["path"] for f in main_files if f["group"] == "(root)"],
    }

    out = os.path.join(ROOT, ".scratch", "dep-analysis-20261010.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "files": main_files + test_files}, f, ensure_ascii=False, indent=1)

    # 可读摘要打 stdout
    print("== 包规模（主源）==")
    for g in sorted(groups, key=lambda x: -summary["groups"][x]["loc"]):
        s = summary["groups"][g]
        print(f"  {g:10s} files={s['files']:3d} loc={s['loc']:5d} android={s['android']:3d} compose={s['compose']:3d}")
    print(f"\n== 包间依赖矩阵（import 语句数，src → dst）==")
    for g in groups:
        deps = matrix[g]
        if deps:
            print(f"  {g:10s} -> " + ", ".join(f"{t}({n})" for t, n in sorted(deps.items(), key=lambda kv: -kv[1])))
        else:
            print(f"  {g:10s} -> (无外部依赖)")
    print(f"\n== 包级循环依赖 ==  {'; '.join(' <-> '.join(c) for c in cycles) if cycles else '无'}")
    hub = {g: dict(matrix[g]).get("(root-hub)", 0) for g in groups}
    hub_total = sum(hub.values())
    print(f"== 白名单 root-hub 边（例外，不计环）==  共 {hub_total} 条: "
          + ", ".join(f"{g}({n})" for g, n in hub.items() if n))
    bg = {g: dict(matrix[g]).get("(buildgen)", 0) for g in groups}
    bg_total = sum(bg.values())
    print(f"== BuildConfig 产物边（例外，不计环）==  共 {bg_total} 条: "
          + ", ".join(f"{g}({n})" for g, n in bg.items() if n))
    print(f"\n== 声明包≠目录 异常 ==  {len(anomalies)} 条")
    for a in anomalies[:10]:
        print(f"  {a['path']} 声明 {a['pkg']}")
    print(f"\n== Top15 大文件 ==")
    for f in big[:15]:
        print(f"  {f['loc']:5d}  {f['path']}")
    print(f"\n== 根包文件 ==  {len(summary['root_files'])} 个")
    for p in summary["root_files"]:
        print(f"  {p}")
    print(f"\n== 测试分布 ==  总 {len(test_files)}")
    for g, n in sorted(summary["test_by_group"].items(), key=lambda kv: -kv[1]):
        print(f"  {g:10s} {n}")
    print(f"\nJSON 全量: {out}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
