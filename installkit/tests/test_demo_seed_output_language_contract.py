"""Language contract for demo_seed runtime output text (install-surface rule).

The installer/demo seeder ships English-only operator output (no i18n track on
this surface), while payload data values (demo project/case names seeded into
the server) keep the product dual-track Chinese, and quoted screen literals
wrapped in 「」 are the on-screen anchors the guide lines must point at so an
operator can actually follow them (error-guidance rule: real paths only).
"""
import ast
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, os.pardir, "demo_seed.py")

CJK = re.compile(r"[\u4e00-\u9fff]")
FULLWIDTH = re.compile(r"[\uFF00-\uFFEF\u3000-\u303F\u2190-\u21FF\u2500-\u257F]")
SCREEN_LITERAL = re.compile(r"\u300c[^\u300d]*\u300d")


def _output_call_segments(tree, source):
    """Collect source of every print(...) call and raise statement."""
    segments = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            fn = node.func
            name = getattr(fn, "id", None) or getattr(fn, "attr", None)
            if name in ("print", "exit"):
                segments.append(ast.get_source_segment(source, node) or "")
        elif isinstance(node, ast.Raise):
            segments.append(ast.get_source_segment(source, node) or "")
    return segments


def test_demo_seed_runtime_output_ships_english_only():
    source = open(SRC, encoding="utf-8").read()
    tree = ast.parse(source)
    offenders = []
    for seg in _output_call_segments(tree, source):
        stripped = SCREEN_LITERAL.sub("", seg)
        if CJK.search(stripped) or FULLWIDTH.search(stripped):
            offenders.append(seg.splitlines()[0][:90])
    assert not offenders, (
        "demo_seed operator output must be English (quoted screen literals in "
        "corned-bracket wrappers are the only allowed CJK); offenders: %s"
        % offenders)


def test_gate_detects_planted_cjk_output():
    """Two-way selftest: the gate has teeth (R43 no silent no-op gates)."""
    planted = 'raise SystemExit("缺少凭据 %s——请配置后再跑" % key)'
    seg = SCREEN_LITERAL.sub("", planted)
    assert CJK.search(seg), "gate pattern must catch planted CJK operator text"
