#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GLSL 符号静态校验（Kotlin 模板展开后做「调用但未定义」检查）。

为什么需要它：
    Kotlin 编译**不校验 GLSL**——着色器是字符串，真机运行时才编译。
    一旦引用了未定义的函数（例如把共用片段注入到新的着色器时漏搬依赖），
    GLSL 编译失败 -> program 链接失败 -> 渲染全黑（表现为"预览黑屏"），
    而 `gradle compileDebugKotlin` 依然 BUILD SUCCESSFUL。

输出规则：
    入口着色器用 `${if (variant.x) A else B}` 按变体切换内容。把所有分支都展开会
    引入"该变体根本编译不到"的假缺失；因此分别按 true / false 展开，
    只有**两次都缺**的符号才是真问题（无论运行时选哪个变体都会编译失败）。

用法：
    python tools/check_glsl_symbols.py [--entry preview|graded|raw|all]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

SRC = pathlib.Path(__file__).resolve().parent.parent / (
    "android/app/src/main/java/com/photographercamera/photon"
)

RAW_STRING = re.compile(r'val\s+(\w+)\s*=\s*"""(.*?)"""', re.S)
OBJECT_DECL = re.compile(r'^\s*(?:internal\s+|private\s+)?object\s+(\w+)', re.M)

GLSL_TYPE = (
    r'(?:void|float|int|uint|bool|vec2|vec3|vec4|ivec2|ivec3|ivec4|'
    r'bvec2|bvec3|bvec4|mat2|mat3|mat4)'
)
FUNC_DEF = re.compile(GLSL_TYPE + r'\s+(\w+)\s*\(')
FUNC_CALL = re.compile(r'(?<![\w.])(\w+)\s*\(')

BUILTINS = set("""
texture textureLod textureGrad texelFetch textureSize textureProj
mix clamp pow dot cross distance length normalize reflect refract faceforward
max min abs sign floor ceil fract mod round roundEven trunc step smoothstep
sqrt inversesqrt exp log exp2 log2 sin cos tan asin acos atan
sinh cosh tanh asinh acosh atanh radians degrees
any all not lessThan lessThanEqual greaterThan greaterThanEqual equal notEqual
isnan isinf floatBitsToInt floatBitsToUint intBitsToFloat uintBitsToFloat
transpose inverse determinant outerProduct matrixCompMult
vec2 vec3 vec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 bvec2 bvec3 bvec4
mat2 mat3 mat4 float int uint bool
if else for while do return break continue discard switch case default
""".split())


def load_tables() -> dict[str, str]:
    """收集 `Object.NAME` / `NAME` -> GLSL 片段。"""
    table: dict[str, str] = {}
    for path in SRC.rglob("*.kt"):
        try:
            txt = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        objects = [(m.start(), m.group(1)) for m in OBJECT_DECL.finditer(txt)]
        for m in RAW_STRING.finditer(txt):
            name, body = m.group(1), m.group(2)
            owner = None
            for pos, oname in objects:
                if pos < m.start():
                    owner = oname
                else:
                    break
            table.setdefault(f"{owner}.{name}", body)
            table.setdefault(name, body)
    return table


ENTRY_FILES = {
    "preview": SRC / "lut/PreviewColorShader.kt",
    "graded": SRC / "lut/LutImageProcessor.kt",
    "raw": SRC / "raw/RawEngineTonePass.kt",
}


def extract_entry(name: str) -> str:
    """入口文件的着色器模板（preview 是 `return \"\"\"...\"\"\"` 形式）。"""
    path = ENTRY_FILES[name]
    txt = path.read_text(encoding="utf-8", errors="replace")
    i = txt.find('return """')
    if i < 0:
        bodies = [m.group(2) for m in RAW_STRING.finditer(txt)]
        if bodies:
            return max(bodies, key=len)
        raise SystemExit(f"找不到 {name} 的着色器字符串: {path}")
    body_start = i + len('return """')
    # 闭合点是紧跟 .trimIndent() 的那个 """（内层嵌套 raw string 不带 trimIndent）
    closes = [m.start() for m in re.finditer(r'"""(?=\s*\.trimIndent\(\))', txt[body_start:])]
    if not closes:
        raise SystemExit(f"{name} 着色器字符串未闭合: {path}")
    return txt[body_start:body_start + closes[0]]


def expand(tpl: str, table: dict[str, str], branch: str = "true", depth: int = 0) -> str:
    """展开模板；branch 决定 `${if (cond) A else B}` 走哪一支。

    注意：if 分支的结果必须**重新用 ${} 包裹**，否则得到的是裸标识符，
    后面的 ${Obj.NAME} 查表就不会命中（曾因此误报一批"缺失"）。
    """
    if depth > 10:
        return tpl
    before = tpl
    if branch == "true":
        tpl = re.sub(
            r'\$\{\s*if\s*\(([^()]*)\)\s*([^{}]*?)\s*else\s*([^{}]*?)\s*\}',
            lambda m: "${" + m.group(2).strip() + "}", tpl)
        tpl = re.sub(r'\$\{\s*if\s*\(([^()]*)\)\s*([^{}]*?)\s*\}',
                     lambda m: "${" + m.group(2).strip() + "}", tpl)
    elif branch == "false":
        tpl = re.sub(
            r'\$\{\s*if\s*\(([^()]*)\)\s*([^{}]*?)\s*else\s*([^{}]*?)\s*\}',
            lambda m: "${" + m.group(3).strip() + "}", tpl)
        tpl = re.sub(r'\$\{\s*if\s*\(([^()]*)\)\s*([^{}]*?)\s*\}',
                     lambda m: "", tpl)
    else:  # both
        tpl = re.sub(
            r'\$\{\s*if\s*\(([^()]*)\)\s*([^{}]*?)\s*else\s*([^{}]*?)\s*\}',
            lambda m: "${" + m.group(2).strip() + "}${" + m.group(3).strip() + "}", tpl)
        tpl = re.sub(r'\$\{\s*if\s*\(([^()]*)\)\s*([^{}]*?)\s*\}',
                     lambda m: "${" + m.group(2).strip() + "}", tpl)

    def repl_var(m: re.Match) -> str:
        key = m.group(1).strip()
        return table.get(key, table.get(key.split(".")[-1], ""))

    tpl = re.sub(r'\$\{\s*([A-Za-z_][\w.]*)\s*\}', repl_var, tpl)
    # 剩余无法解析的 ${...}（例如 ${if (...)}）清掉
    tpl = re.sub(r'\$\{[^{}]*\}', " ", tpl)
    if tpl != before:
        return expand(tpl, table, branch, depth + 1)
    return tpl


def _missing(src_tpl: str, table: dict[str, str], branch: str) -> set[str]:
    src = expand(src_tpl, table, branch).replace('"""', " ")
    src = re.sub(r'//[^\n]*', " ", src)
    defined = {m.group(1) for m in FUNC_DEF.finditer(src)} | BUILTINS
    calls = {m.group(1) for m in FUNC_CALL.finditer(src)}
    return {c for c in calls - defined if not c.isupper()}


def audit(name: str, table: dict[str, str]) -> int:
    tpl = extract_entry(name)
    mt = _missing(tpl, table, "true")
    mf = _missing(tpl, table, "false")
    real = sorted(mt & mf)
    variant_only = sorted((mt | mf) - (mt & mf))

    print(f"\n=== {name} === (模板 {len(tpl)} 字符)")
    if variant_only:
        print(f"  · 仅某一变体分支出现（通常无害）: {', '.join(variant_only)}")
    if real:
        print("  ✗ 真缺失：无论选哪个变体都编译不过 -> 真机黑屏")
        for m in real:
            print(f"      {m}")
        return 1
    print("  ✓ 无条件路径上的符号全部已定义")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--entry", default="preview", choices=["preview", "graded", "raw", "all"])
    args = ap.parse_args()
    table = load_tables()
    print(f"收集到 {len(table)} 个 GLSL 片段")
    entries = ["preview", "graded", "raw"] if args.entry == "all" else [args.entry]
    rc = 0
    for e in entries:
        try:
            rc |= audit(e, table)
        except SystemExit as exc:
            print(f"\n=== {e} === 跳过: {exc}")
    return rc


if __name__ == "__main__":
    sys.exit(main())
