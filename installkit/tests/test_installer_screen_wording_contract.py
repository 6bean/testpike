# -*- coding: utf-8 -*-
"""Installer screen wording gate.

Everything the operator reads while installing (printed lines, argparse
help/description text, echo strings in the two shell entry points) must state
operational facts: what happened, which value caused it, where to change it.
Internal vocabulary ("single source", "adjudication", "ratchet") and tracking
codes (task numbers, section pointers) carry zero action value there, and the
family keeps re-appearing because nothing mechanical watched it — this test is
that watch. No baseline, no exemption list: a new hit is a red test.
"""
import ast
import re
from pathlib import Path

INSTALLKIT = Path(__file__).resolve().parents[1]

BANNED_WORDS = ("人话", "拒件", "裁决", "单源", "棘轮", "收口", "出账")
BOOKKEEPING = (
    re.compile(r"\bT\d{3,4}\b"),
    re.compile(r"\bTC-[A-Z]{1,2}\b"),
    re.compile(r"\bR\d{2}\b"),
    re.compile(r"§\d"),
)
_MAX_LEN = 400  # a blob this large is data or a fixture, not a screen line


def _docstring_nodes(tree):
    skip = set()
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            body = getattr(node, "body", [])
            if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) \
                    and isinstance(body[0].value.value, str):
                skip.add(id(body[0].value))
    return skip


def python_screen_strings(path):
    """Every string constant except docstrings (help text and printed lines)."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    skip = _docstring_nodes(tree)
    return [(node.lineno, node.value)
            for node in ast.walk(tree)
            if isinstance(node, ast.Constant) and isinstance(node.value, str)
            and id(node) not in skip]


def shell_screen_strings(path):
    """Quoted spans on non-comment lines of install.sh / install.ps1."""
    out = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("#") or stripped.startswith("//"):
            continue
        for double, single in re.findall(r'"([^"]*)"|\'([^\']*)\'', line):
            out.append((lineno, double or single))
    return out


def _violations(path):
    items = (python_screen_strings(path) if path.suffix == ".py"
             else shell_screen_strings(path))
    bad = []
    for lineno, text in items:
        if not text or len(text) > _MAX_LEN:
            continue
        for word in BANNED_WORDS:
            if word in text:
                bad.append("%s:%d 内部用语「%s」：%s" % (path.name, lineno, word, text[:90]))
        for pattern in BOOKKEEPING:
            if pattern.search(text):
                bad.append("%s:%d 簿记码上屏：%s" % (path.name, lineno, text[:90]))
    return bad


def _installer_files():
    files = sorted(INSTALLKIT.glob("*.py"))
    files += [INSTALLKIT / "install.sh", INSTALLKIT / "install.ps1"]
    return [f for f in files if f.exists() and not f.name.startswith("test_")]


def test_installer_surface_is_registered():
    names = {f.name for f in _installer_files()}
    for expected in ("install.sh", "install.ps1", "s1_bootstrap.py", "pkg_probe.py",
                     "image_relay.py", "failure_guide.py", "service_agent.py"):
        assert expected in names, "installer file left the scanned surface: %s" % expected


def test_no_internal_wording_on_the_installer_surface():
    hits = [h for f in _installer_files() for h in _violations(f)]
    assert not hits, "上屏文案含内部用语/簿记码：\n" + "\n".join(hits)


def test_scanner_detects_a_planted_violation(tmp_path):
    """A gate that can go silent is a defect: prove both branches actually fire."""
    planted = tmp_path / "planted.py"
    planted.write_text(
        "import argparse\n"
        "p = argparse.ArgumentParser(description='装机账 单源件 见 T317')\n"
        "print('原因见上方人话输出')\n", encoding="utf-8")
    hits = _violations(planted)
    assert any("单源" in h for h in hits), hits
    assert any("人话" in h for h in hits), hits
    assert any("簿记码" in h for h in hits), hits
