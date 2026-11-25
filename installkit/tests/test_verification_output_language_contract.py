"""Language contract for runtime operator output of tools/verification scripts.

Rule source: installation-surface English ruling, second part. Every operator-
visible line a live-verification script prints, logs, raises or exposes as an
argparse help entry must ship English-only (no i18n track on this surface).
Payload data values and predicates that match Chinese server-side text stay
untouched — they live outside these output segments by design (data belongs to
variables, not to the operator line). Quoted screen literals wrapped in 「」
remain the on-screen anchors guidance lines must point at (error-guidance
rule: real, followable paths only).
"""
import ast
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir, os.pardir))
# Second-family coverage: scripts/ ships the chain-slot tooling the operator runs
# directly (chain_slot / slot_occupancy / ledger selftests / mock server help).
# Third-family coverage: tools/ops ships the maintenance tooling the operator runs.
TARGET_DIRS = [os.path.join(ROOT, "tools", "verification"), os.path.join(ROOT, "scripts"),
               os.path.join(ROOT, "tools", "ops")]
SHELL_TARGET_DIRS = [os.path.join(ROOT, "tools", "ops")]

CJK = re.compile(r"[\u4e00-\u9fff]")
FULLWIDTH = re.compile(r"[\uFF00-\uFFEF\u3000-\u303F\u2190-\u21FF\u2500-\u257F]")
SCREEN_LITERAL = re.compile(r"\u300c[^\u300d]*\u300d")

PRINT_FUNCS = ("print", "exit")
LOG_METHODS = ("info", "warning", "warn", "error", "debug", "critical",
               "exception", "log")


def _output_call_segments(source):
    """Collect source text of every operator-output call/raise in a module.

    Covers print(...), sys.exit(...)/exit(...), logger-style calls
    (log.info / logging.warn / .error ...), raise statements, and every
    ``help=`` keyword (argparse surface shown with --help).
    """
    tree = ast.parse(source)
    segments = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call):
            name = getattr(node.func, "id", None) or getattr(node.func, "attr", None)
            if name in PRINT_FUNCS or name in LOG_METHODS:
                segments.append((node.lineno,
                                 ast.get_source_segment(source, node) or ""))
            for kw in node.keywords:
                if kw.arg == "help":
                    segments.append((node.lineno,
                                     ast.get_source_segment(source, kw) or ""))
        elif isinstance(node, ast.Raise):
            segments.append((node.lineno,
                             ast.get_source_segment(source, node) or ""))
    return segments


def _offending_segments(source):
    hits = []
    for lineno, seg in _output_call_segments(source):
        stripped = SCREEN_LITERAL.sub("", seg)
        if CJK.search(stripped) or FULLWIDTH.search(stripped):
            hits.append((lineno, stripped.splitlines()[0][:90]))
    return hits


def test_verification_scripts_ship_english_operator_output():
    offenders = []
    unparseable = []
    for target_dir in TARGET_DIRS:
        for dirpath, dirnames, filenames in os.walk(target_dir):
            dirnames[:] = [d for d in dirnames if d != "__pycache__"]
            for fn in sorted(filenames):
                if not fn.endswith(".py"):
                    continue
                path = os.path.join(dirpath, fn)
                rel = os.path.relpath(path, ROOT)
                source = open(path, encoding="utf-8", errors="ignore").read()
                try:
                    hits = _offending_segments(source)
                except SyntaxError as exc:
                    unparseable.append(f"{rel}: {exc}")
                    continue
                for lineno, preview in hits:
                    offenders.append(f"{rel}:{lineno}: {preview}")
    assert not unparseable, (
        "operator-facing scripts must stay parseable for the language gate; "
        "unparseable: %s" % unparseable)
    assert not offenders, (
        "operator output (print/log/raise/exit/help) must ship English on tools/verification "
        "and scripts/ (screen literals in corner brackets are the only allowed CJK); offenders:\n"
        + "\n".join(sorted(offenders)))


def test_gate_detects_planted_cjk_output_and_help():
    """Two-way selftest: the gate must flag planted CJK output."""
    planted = (
        "print(f\"结果：{n} 项\")\n"
        "logging.warning(\"缺少凭据——请配置后再跑\")\n"
        "raise SystemExit(\"派发单缺失，后续断言无意义\")\n"
        "parser.add_argument('--db', help='数据库名')\n"
    )
    assert len(_offending_segments(planted)) == 4, "gate must catch CJK in print/log/raise/help"
    clean = (
        "print(f\"result: {n} items\")\n"
        "logging.warning(\"credential missing - configure it first\")\n"
        "raise SystemExit(\"dispatch row absent; later assertions meaningless\")\n"
        "parser.add_argument('--db', help='database name')\n"
        "print('seeded 「用例管理」 page')\n"
    )
    assert _offending_segments(clean) == [], "gate must pass English output and corner-bracket screen literals"


# ---------------------------------------------------------------------------
# Shell surface: operator-visible lines printed by tools/ops
# scripts. A physical line counts when it echoes/prints quoted text or sits in
# a cat-heredoc usage block; comment lines and bare variable echoes never
# carry literals here. Chinese data predicates (grep patterns matching
# server-side text) live outside these segments by design.
# ---------------------------------------------------------------------------
_SHELL_ECHO = re.compile(r"\b(?:echo|printf)\s+.*[\"']")
_SHELL_HEREDOC = re.compile(r"<<-?\s*['\"]?([A-Z_]+)['\"]?")


def _offending_shell_lines(source):
    hits = []
    heredoc_end = None
    for lineno, line in enumerate(source.splitlines(), 1):
        stripped = line.strip()
        if heredoc_end is not None:
            if stripped == heredoc_end:
                heredoc_end = None
                continue
            target = stripped
        elif stripped.startswith("#"):
            continue
        elif _SHELL_ECHO.search(line):
            target = stripped
        else:
            m = _SHELL_HEREDOC.search(line)
            if m and re.search(r"\bcat\b", line):
                heredoc_end = m.group(1)
            continue
        probe = SCREEN_LITERAL.sub("", target)
        if CJK.search(probe) or FULLWIDTH.search(probe):
            hits.append((lineno, target[:90]))
    return hits


def test_ops_shell_ships_english_operator_output():
    offenders = []
    for target_dir in SHELL_TARGET_DIRS:
        for dirpath, dirnames, filenames in os.walk(target_dir):
            dirnames[:] = []
            for fn in sorted(filenames):
                if not fn.endswith(".sh"):
                    continue
                path = os.path.join(dirpath, fn)
                rel = os.path.relpath(path, ROOT)
                source = open(path, encoding="utf-8", errors="ignore").read()
                for lineno, preview in _offending_shell_lines(source):
                    offenders.append(f"{rel}:{lineno}: {preview}")
    assert not offenders, (
        "shell operator output (echo/printf/usage heredoc) must ship English on "
        "tools/ops (screen literals in corner brackets are the only allowed CJK); offenders:\n"
        + "\n".join(sorted(offenders)))


def test_shell_gate_detects_planted_and_passes_clean():
    """Two-way selftest for the shell segment detector."""
    planted = (
        "#!/usr/bin/env bash\n"
        "usage() { cat <<'USAGE'\n用法：必填 --version\nUSAGE\n}\n"
        "echo \"缺少参数\" >&2\n"
        "printf '产物未生成\\n'\n"
    )
    assert len(_offending_shell_lines(planted)) == 3, "gate must catch usage heredoc + echo + printf"
    clean = (
        "# 注释里可以有中文，屏显行不行\n"
        "echo \"missing --version=<v>\" >&2\n"
        "printf 'release.json written (%d artifacts)\\n' \"$n\"\n"
        "res=$(grep -c \"批量通过\\|批量拒绝\" locales/zh-CN.ts)\n"
        "echo \"seeded 「用例管理」 page\"\n"
    )
    assert _offending_shell_lines(clean) == [], "comments, English output, data predicates and corner-bracket literals must pass"


# ---------------------------------------------------------------------------
# Fourth-family coverage: flat tools/*.py gate/ledger scripts the operator
# runs directly (operator tooling layer booking). Two files stay on the next
# booked batch; their ceilings are a only-shrinks ratchet, and a ceiling that
# reached zero without its entry being deleted fails on purpose (booked
# surface must stay auditable — a stale zero row is silent-idle detection,
# same family as an allow rule that matches nothing).
# ---------------------------------------------------------------------------
TOOLS_ROOT = os.path.join(ROOT, "tools")
TOOLS_ROOT_PENDING: dict[str, int] = {}


def test_tools_root_ships_english_operator_output():
    offenders = []
    stale = []
    unparseable = []
    for fn in sorted(os.listdir(TOOLS_ROOT)):
        if not fn.endswith(".py"):
            continue
        path = os.path.join(TOOLS_ROOT, fn)
        source = open(path, encoding="utf-8", errors="ignore").read()
        try:
            hits = _offending_segments(source)
        except SyntaxError as exc:
            unparseable.append(f"tools/{fn}: {exc}")
            continue
        if fn in TOOLS_ROOT_PENDING:
            if not hits:
                stale.append(fn)
            elif len(hits) > TOOLS_ROOT_PENDING[fn]:
                offenders.append(
                    f"tools/{fn}: {len(hits)} offender lines exceed the booked "
                    f"ceiling {TOOLS_ROOT_PENDING[fn]} (ratchet only shrinks)")
            continue
        for lineno, preview in hits:
            offenders.append(f"tools/{fn}:{lineno}: {preview}")
    assert not unparseable, (
        "operator-facing scripts must stay parseable for the language gate; "
        "unparseable: %s" % unparseable)
    assert not stale, (
        "booked pending files now ship English operator output; delete them "
        "from TOOLS_ROOT_PENDING in the same batch: %s" % stale)
    assert not offenders, (
        "operator output (print/log/raise/exit/help) must ship English on flat "
        "tools/*.py scripts (screen literals in corner brackets are the only "
        "allowed CJK); offenders:\n" + "\n".join(sorted(offenders)))
