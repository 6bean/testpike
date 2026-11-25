#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import re
from pathlib import Path
import subprocess
import sys

BANNED = ["腿", "电池", "铁三角", "抓手"]
META_MARKERS = ("→", "废止", "禁再用", "禁自造", "术语")
LITERAL_OK = ("拖后腿", "后腿", "识字", "零误杀")
LEGACY_EXEMPT_PATH_PARTS = ("docs/task-archives/", "tools/term_lint.py",
                            "tools/term_lint_ledger.json")


def _tracked_text_files(root: str) -> list[str]:
    out = subprocess.run(
        ["git", "-C", root, "ls-files"],
        capture_output=True, text=True, check=True,
    ).stdout.splitlines()
    skip_ext = (".png", ".jpg", ".gif", ".ico", ".woff", ".woff2", ".ttf",
                ".zip", ".jar", ".pdf", ".min.js", ".map", ".svg")
    return [f for f in out if not f.lower().endswith(skip_ext)]


def scan_file(path: str) -> list[str]:
    hits: list[str] = []
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
    except (UnicodeDecodeError, OSError):
        return hits
    for no, line in enumerate(lines, 1):
        for word in BANNED:
            if word not in line:
                continue
            norm = path.replace(os.sep, "/")
            if any(part in norm for part in LEGACY_EXEMPT_PATH_PARTS):
                continue
            if "_PRD.md" in norm and line.lstrip().startswith("| v"):
                continue
            if any(ok in line for ok in LITERAL_OK):
                continue
            if any(m in line for m in META_MARKERS) and (word + "→") in line.replace(" ", ""):
                continue
            if any(m in line for m in META_MARKERS):
                continue
            frag = line.strip()
            hits.append(f"{path}:{no}: …{frag[:80]}…")
            break
    return hits


BOOKKEEP_OK_PREFIX = re.compile(r"^P(?:[0-3]\b|[59]0\b|[59]5\b|[9]9\b)")
BOOKKEEP_RE = re.compile(
    r"^(?:T\d{2,4}[a-z]?\s|A-\d\s|M\d+(?:\.\d+)?\s*[批波波次]|W\d[a-z]?\s"
    r"|S\d-[a-z0-9]+\s|§\s*\d+|V\d{2,4}\s*[+＋]|TC-[A-Z]\d|IT-[A-Z]\d|D\d\s)"
)
I18N_EXEMPT = ("如 ", "如:", "如：", "示例", "example", "e.g.", "placeholder")


def scan_bookkeeping(root: str) -> list[str]:
    hits: list[str] = []
    targets = []
    for rel in ("frontend/src/utils/locales",):
        d = os.path.join(root, rel)
        if os.path.isdir(d):
            for n in os.listdir(d):
                if n.endswith(".ts"):
                    targets.append(os.path.join(d, n))
    for f in targets:
        rp = os.path.relpath(f, root)
        for no, line in enumerate(open(f, encoding="utf-8"), 1):
            m = re.match(r"\s*([A-Za-z][\w]*)(Label|Title|Name|Text|Desc|Hint)?\s*:\s*['\"]([^'\"]+)['\"]", line)
            if not m:
                continue
            val = m.group(3).strip()
            if any(x in val for x in I18N_EXEMPT):
                continue
            if BOOKKEEP_OK_PREFIX.match(val):
                continue
            if BOOKKEEP_RE.match(val):
                hits.append(f"{rp}:{no}: 屏显文案以内部簿记码开头 → …{val[:60]}…")
    return hits


# ── Second-gate extension : database-seed and test-fixture
# surfaces. The tp_sys_config.description column renders verbatim on the /configs
# admin page, so it carries the same ban as i18n values: no internal bookkeeping
# codes (task ids, doc section refs, migration/version ids, test-case ids). The gate
# scans the seed statement in db/schema-init.sql and every test fixture under
# */src/test/** that references tp_sys_config (assertions embed the same strings).
# Legacy hits are ratcheted, decrease-only: washing values in the closeout
# lowers the count and the baseline in the same commit; a new seed line above the
# baseline fails the gate.
SEED_OCC = re.compile(
    r"(T\d{2,4}(?![0-9])|TC-[A-Z]{1,4}(?:-\d+)?|IT-[A-Z]{1,4}(?:-\d+)?"
    r"|§\s*[A-Z]?\d|v2\.\d+|详设|裁决件|PRD"
    r"|[（(][ACWDFS]\d"
    r"|(?<![A-Za-z0-9/])[A-Z]\d{1,2}(?![0-9A-Za-z]))")
SEED_EXEMPT = re.compile(r"^(?:如|示例|example)")
# are industry vocabulary, not bookkeeping codes. K8 is the standard shorthand
# for Kubernetes (hot-reload deployment surface), likewise operational information.
# Industry tokens that merely LOOK like internal bookkeeping codes:
# P\d{1,2} priorities/percentiles, K8 (Kubernetes), S3 (Amazon Simple Storage
# Service protocol family - our storage provider enum value and endpoint name).
SEED_OCC_OK_TOKEN = re.compile(r"^(?:P\d{1,2}|K8|S3)$")


def _seed_bookkeep_hit(text: str) -> bool:
    """True when text carries an internal bookkeeping code (second-gate SQL/
    fixture surface), honouring the industry-token whitelist."""
    for m in SEED_OCC.finditer(text):
        tok = m.group(0)
        if tok and tok[0] in "（(":
            tok = tok[1:]
        if SEED_OCC_OK_TOKEN.match(tok):
            continue
        return True
    return False

# Baseline frozen 2026-10-31: 47 seed lines in db/schema-init.sql + 45 fixture
# assertion lines (68 files reference tp_sys_config; live DB side = 107 of 294
# rows, handled by the acceptance SQL in the closeout, not by this ratchet).
# Pattern extended 2026-10-31 (multi-letter TC-/IT- case ids, §-letter
# doc refs, PRD, bare feature codes like A6/I4/K11/R3). The stock ledger was
# fully washed to zero and the ratchet retired — this surface is
# fail-on-any-hit.
LEGACY_SEED_BOOKKEEP_BASELINE = 0


def scan_seed_bookkeeping(root: str) -> list[str]:
    """Seed-fixture bookkeeping hits (ratcheted, not auto-fail below baseline)."""
    hits: list[str] = []
    seed = os.path.join(root, "db", "schema-init.sql")
    if os.path.isfile(seed):
        scanning = False
        with open(seed, encoding="utf-8", errors="replace") as fh:
            for no, line in enumerate(fh, 1):
                if not scanning and "INSERT INTO tp_sys_config" in line:
                    scanning = True
                if scanning:
                    for q in re.findall(r"'([^']{4,})'", line):
                        if _seed_bookkeep_hit(q) and not SEED_EXEMPT.match(q.strip()):
                            hits.append(f"db/schema-init.sql:{no}: 配置种子描述含内部簿记码 → …{q[:60]}…")
                            break
                    if line.rstrip().endswith(";"):
                        scanning = False
    for mod in ("core", "admin", "api", "ai-service", "common"):
        d = os.path.join(root, mod, "src", "test")
        if not os.path.isdir(d):
            continue
        for dirpath, dirnames, names in os.walk(d):
            if "/target/" in dirpath:
                continue
            dirnames[:] = [x for x in dirnames if x != "target"]
            for name in names:
                if not name.endswith(".java"):
                    continue
                p = os.path.join(dirpath, name)
                with open(p, encoding="utf-8", errors="replace") as fh:
                    body = fh.read()
                if "tp_sys_config" not in body:
                    continue
                for no, line in enumerate(body.splitlines(), 1):
                    for q in re.findall(r"'([^']{4,})'", line):
                        if _seed_bookkeep_hit(q) and not SEED_EXEMPT.match(q.strip()):
                            rel = os.path.relpath(p, root)
                            hits.append(f"{rel}:{no}: 测试夹具种/断言配置描述含内部簿记码 → …{q[:60]}…")
                            break
    return hits


COINED = ["刀", "blade", "钉进", "钉死", "钉牢"]
COINED_CTX = ("刀1", "刀2", "刀3", "刀4", "刀5", "刀序", "刀位", "刀口", "刀中途",
              "接刀", "插刀", "随刀", "补刀", "一刀", "本刀", "该刀", "末刀", "首刀",
              "1刀", "2刀", "3刀", "4刀", "5刀", "6刀", "7刀", "8刀", "9刀")
COINED_OK = ("两肋插刀", "笑里藏刀", "刀光剑影", "刀片", "刀面", "菜刀", "冰刀",
             "刀削", "刀柄", "指甲刀")

IDENT_BOOKKEEP = [
    (r"T\d{2,4}", "任务号"),
    (r"(?:^|_)t\d{2,4}", "任务号"),
    (r"TC-[A-Za-z][\w.\-]*", "用例码"),
]
DECL_RES = [
    re.compile(r"(?:export\s+)?([A-Za-z_]\w*)="),
    re.compile(r"([a-z_]\w*)\(\)\s*\{"),
    re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)"),
    re.compile(r"\bvoid\s+(\w+)\s*\("),
]
LEGACY_IDENT_BASELINE = 236


def scan_identifier_bookkeeping(root: str) -> list[str]:
    pass
    d = subprocess.run(["git", "-C", root, "diff", "-U0", "--cached", "HEAD"],
                       capture_output=True, text=True, errors="replace").stdout
    hits: list[str] = []
    ar = subprocess.run(["git", "-C", root, "diff", "--cached", "--name-only",
                         "--diff-filter=AR", "HEAD"],
                        capture_output=True, text=True).stdout
    for rel in ar.splitlines():
        if rel.startswith(("docs/", "scripts/", "db/")):
            continue
        if rel.rsplit(".", 1)[-1] not in ("java", "py", "ts", "mjs", "js", "vue", "sh", "yml", "yaml", "sql", "json"):
            continue
        stem = rel.rsplit("/", 1)[-1]
        for code_rx, code_label in IDENT_BOOKKEEP:
            if re.search(code_rx, stem):
                hits.append(f"{rel}: 新增/改名文件携带{code_label} → …{stem[:60]}…")
    cur = "?"
    for ln in d.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:]
            continue
        if not ln.startswith("+"):
            continue
        seg = ln[1:]
        if "→" in seg or "禁" in seg:
            continue
        for rx in DECL_RES:
            m = rx.search(seg)
            if not m:
                continue
            ident = m.group(1)
            for code_rx, code_label in IDENT_BOOKKEEP:
                if re.search(code_rx, ident):
                    hits.append(f"{cur}: 标识符「{ident}」携带{code_label} → …{seg.strip()[:72]}…")
    return hits


_CHAIN_TOKEN_WARN: list[str] = []


def _chain_tokens():
    """Chain identities from the topology, so this gate keeps no second list of them.

    Importing from the repository's own scripts directory rather than hoping sys.path has it:
    an empty result here silently un-exempts every chain-token file and grows the ratchet,
    which is worse than a loud failure.
    """
    global _CHAIN_TOKEN_WARN
    if _CHAIN_TOKEN_WARN:
        return set()
    here = Path(__file__).resolve().parent.parent / "scripts"
    if str(here) not in sys.path:
        sys.path.insert(0, str(here))
    try:
        import slot_topology as _t
    except Exception as exc:
        _CHAIN_TOKEN_WARN = [f"chain-token-exempt-unavailable: {type(exc).__name__}: {str(exc)[:80]}"
                             " → 链名文件可能被计入任务号账（虚高），先修导入再读账"]
        return set()
    return set(_t.known_chain_tokens())



def count_legacy_ident_names(root: str) -> int:
    pass
    n = 0
    out = subprocess.run(["git", "-C", root, "ls-files"],
                         capture_output=True, text=True).stdout
    for rel in out.splitlines():
        if rel.startswith("docs/") or rel.startswith("scripts/") or rel.startswith("db/"):
            continue
        if not rel.rsplit(".", 1)[-1] in ("java", "py", "ts", "mjs", "js", "vue", "sh", "yml", "yaml", "sql", "json"):
            continue
        stem = rel.rsplit("/", 1)[-1]
        if re.search(r"T\d{2,4}", stem) or re.search(r"(?:^|_)t\d{2,4}", stem) \
                or re.search(r"TC-[A-Za-z]", stem):
            # A chain identity (t399se and friends) is not a ticket number; it is judged by
            # the isolation gate under its own rule, and the names come from the topology.
            if any(re.search(rf"(?:^|[-_./]){re.escape(tok)}", stem.lower())
                   for tok in _chain_tokens()):
                continue
            n += 1
    return n


W6_ZH = ["人话", "拒件", "单源", "棘轮", "落章", "出账", "收口", "裁决",
         # Internal engineering wording that leaked onto progress events:
         # only words with no legitimate on-screen product meaning join this
         # global family. Contract, dispatch and cursor stay out = established
         # terms in the contract-testing and task-dispatch features; their
         # misuse inside progress-event frames is policed by one wording guard
         # fixture (core ProcessEventWordingGuardTest) instead of this family.
         "封账", "派发门",
         # The process drawer is titled "process detail" on screen; this internal
         # shortname has no legitimate on-screen use. Joined after the seeded
         # help chapters were rewritten. The truncation phrase for tool output
         # ("head and tail window") is a different concept and never matches
         # this longer entry.
         "尾窗子窗"]
W6_EN = [r"plain[- ]language", r"plain[- ]word", r"plain words", r"single source",
         r"adjudicat\w*", r"final(?:ly)? judged[^']{0,30}server",
         r"dispatch door", r"tail follows?"]


def _w6_line_ok(line: str) -> bool:
    pass
    s = line.strip()
    return not (s.startswith("//") or s.startswith("/*") or s.startswith("*")
                or s.startswith("--"))


def scan_w6_locale(root: str) -> list[str]:
    hits: list[str] = []
    for rel in ("frontend/src/utils/locales/zh-CN.ts", "frontend/src/utils/locales/en-US.ts"):
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            continue
        for no, line in enumerate(open(p, encoding="utf-8"), 1):
            if not _w6_line_ok(line):
                continue
            if "zh-CN" in rel:
                bad = next((w for w in W6_ZH if w in line), None)
            else:
                bad = next((w for w in W6_EN if re.search(w, line, re.I)), None)
            if bad:
                hits.append(f"{rel}:{no}: 屏显文案含内部治理语「{bad}」→ …{line.strip()[:72]}…")
    seed_files = ["db/schema-init.sql"]
    mig_dir = os.path.join(root, "db", "migration")
    if os.path.isdir(mig_dir):
        seed_files += [f"db/migration/{n}" for n in sorted(os.listdir(mig_dir)) if n.endswith(".sql")]
    for rel in seed_files:
        p = os.path.join(root, rel)
        if not os.path.isfile(p):
            continue
        for no, line in enumerate(open(p, encoding="utf-8"), 1):
            if not _w6_line_ok(line) or line.strip().startswith("COMMENT ON"):
                continue
            body = re.sub(r"--.*$", "", line)
            bad = next((w for w in W6_ZH if w in body), None)
            if bad:
                hits.append(f"{rel}:{no}: seed 上屏文案含内部治理语「{bad}」→ …{body.strip()[:72]}…")
    return hits


# ── Sixth gate: Java runtime operator text must ship in English ─────────────
# Logger calls and internal exception throws reach operators through logs and
# stack traces, so they belong to the English release surface. Exception
# families whose message is mapped onto the bilingual UI (ServiceException
# with a business error code, BizException, ValidationException per the
# pending classification note) keep the Chinese track and are exempt here;
# that wording is governed by the screen gates instead. The stock count is a
# ratchet that only shrinks, lowered batch by batch as domains are cleaned;
# newly added lines are blocked outright by the incremental check below.
_HAN = re.compile(r"[\u4e00-\u9fff\u3400-\u4dbf\uf900-\ufaff]")
_JAVA_MAIN = re.compile(r"^(?:core|admin|api|ai-service|common)/src/main/")
_LOG_OPEN = re.compile(
    r"\b(?:log|logger|LOG|LOGGER)\s*\.\s*(?:trace|debug|info|warn|error)\s*\(")
_THROW_OPEN = re.compile(r"\bthrow\s+new\s+([A-Za-z_$][A-Za-z0-9_$]*Exception)\s*\(")
_UI_MAPPED_EXCEPTIONS = {"ServiceException", "BizException", "ValidationException"}
_JAVA_STRING = re.compile(r'"(?:[^"\\\n]|\\.)*"')
# Baseline frozen at gate wiring (2026-11-25) using this gate's own counting
# rule: one hit per offending call statement, multi-line argument continuations
# joined into the statement. Probe-derived line counts differ by design and
# were deliberately not reused here.
JAVA_RUNTIME_CJK_BASELINE = 824  # gate's own rule; ratchet shrinks with each cleaned batch


def _call_statement(lines: list[str], idx: int, col: int, max_lines: int = 24) -> str:
    """Join source text from an opening parenthesis until the call closes
    (paren balance tracked outside string literals) so concatenated literal
    fragments on continuation lines are judged as part of one call."""
    out: list[str] = []
    depth = 0
    i, c = idx, col
    while i < len(lines) and (i - idx) < max_lines:
        seg = lines[i][c:]
        out.append(seg)
        in_str = esc = False
        for ch in seg:
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return " ".join(out)
        i += 1
        c = 0
    return " ".join(out)


def _scan_java_runtime_text(rel: str, text: str) -> list[str]:
    """Runtime-text hits for one Java file: CJK inside a string literal of a
    logger call or a non-exempt exception throw. Comments never match because
    only quoted literals are inspected."""
    hits: list[str] = []
    lines = text.splitlines()
    seen: set[int] = set()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        marks = []
        m = _LOG_OPEN.search(line)
        if m:
            marks.append(("log-call", m.end() - 1, None))
        t = _THROW_OPEN.search(line)
        if t and t.group(1) not in _UI_MAPPED_EXCEPTIONS:
            marks.append(("throw-" + t.group(1), t.end() - 1, None))
        for kind, open_col, _ in marks:
            if i in seen:
                continue
            stmt = _call_statement(lines, i, open_col)
            if any(_HAN.search(lit) for lit in _JAVA_STRING.findall(stmt)):
                hits.append(f"{rel}:{i + 1}: {kind} carries CJK runtime text: "
                            f"…{stripped[:72]}…")
                seen.add(i)
    return hits


def scan_java_runtime_cjk(root: str) -> list[str]:
    hits: list[str] = []
    for rel in _tracked_text_files(root):
        if not rel.endswith(".java") or not _JAVA_MAIN.match(rel):
            continue
        path = os.path.join(root, rel)
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            continue
        hits.extend(_scan_java_runtime_text(rel, text))
    return hits


def scan_java_runtime_cjk_diff(root: str) -> list[str]:
    """Incremental block: a diff line that opens a logger call or a non-exempt
    exception throw with CJK inside fails outright. Continuation fragments of
    multi-line calls are covered by the full-scan ratchet (adding one without
    removing another raises the count above the frozen baseline)."""
    d = subprocess.run(["git", "-C", root, "diff", "-U0", "HEAD"],
                       capture_output=True, text=True, errors="replace").stdout
    hits: list[str] = []
    cur = "?"
    for ln in d.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:]
            continue
        if not cur.endswith(".java") or not _JAVA_MAIN.match(cur):
            continue
        if not ln.startswith("+") or ln.startswith("+++"):
            continue
        seg = ln[1:]
        if not _HAN.search(seg):
            continue
        if _LOG_OPEN.search(seg):
            hits.append(f"{cur}: new log call carries CJK: …{seg.strip()[:72]}…")
            continue
        t = _THROW_OPEN.search(seg)
        if t and t.group(1) not in _UI_MAPPED_EXCEPTIONS:
            hits.append(f"{cur}: new {t.group(1)} message carries CJK: "
                        f"…{seg.strip()[:72]}…")
    return hits


def _java_runtime_selftest() -> None:
    """Two-way fixture (R43③): every predicate and exemption branch must prove
    it fires, and clean English lines plus comments must not."""
    # Build real CJK literals at runtime so this gate file itself never ships a
    # Chinese string in its own diff (definition surface stays clean).
    han = "\u4e2d\u6587"
    fixture = "\n".join([
        "class Probe {",
        f'    void dirty() {{ log.info("task {han} finished", id); }}',
        f'    void cont() {{ log.warn("retry {han}"',
        '        + " skipped", id); }',
        f'    void exc() {{ throw new IllegalStateException("{han} state"); }}',
        f'    void ui() {{ throw new ServiceException("code {han}", 50003); }}',
        f'    void vd() {{ throw new ValidationException("value {han}"); }}',
        '    void clean() { log.info("plain english line", id); }',
        f'    // comment about {han} must not count',
        '}',
    ])
    hits = _scan_java_runtime_text("probe/X.java", fixture)
    assert len(hits) == 3, f"java runtime scan missed hits (expected 3, got {len(hits)}): {hits}"
    assert any("log-call" in h for h in hits), "single-line logger hit not caught"
    assert any("IllegalStateException" in h for h in hits), "throw hit not caught"
    assert not any("ServiceException" in h for h in hits), "UI-mapped family was not exempted"
    assert not any("ValidationException" in h for h in hits), "pending-family was not exempted"
    assert not any("plain english" in h for h in hits), "clean line false positive"
    assert not any("comment about" in h for h in hits), "comment false positive"


def _ledger_bans(root: str) -> list[tuple[str, str]]:
    """Ledger-driven ban regexes (R55: the word list lives in the data file;
    registering a coined variant there must extend the gate with no code edit).
    Malformed/missing ledger = empty set with a loud warn line (R43: an
    exemption or data branch that silently no-ops is a defect)."""
    path = os.path.join(root, "tools", "term_lint_ledger.json")
    if not os.path.isfile(path):
        _CHAIN_TOKEN_WARN.append("term_lint_ledger.json 缺失=自造词台账面空转")
        return []
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        bans = data.get("bans", {})
        _LEDGER_EXEMPT.clear()
        _LEDGER_EXEMPT.extend(data.get("literal_exempt", []))
    except (ValueError, OSError) as exc:
        _CHAIN_TOKEN_WARN.append(f"term_lint_ledger.json 解析失败({exc})=台账面空转")
        return []
    out = []
    for name, pat in bans.items():
        try:
            out.append((name, re.compile(pat)))
        except re.error:
            _CHAIN_TOKEN_WARN.append(f"台账禁词正则不可编译: {name}")
    return out


_LEDGER_REL = "tools/term_lint_ledger.json"
# The gate's own definition surface (this file + the ledger data file) carries
# the banned words as data, exactly like LEGACY_EXEMPT_PATH_PARTS exempts them
# from the full-file scan. Proving the detector still bites is the ledger
# selftest fixture's job (--selftest reads those probes from the data file).
_DEFINITIONS = (_LEDGER_REL, "tools/term_lint.py")
# clearing a false positive is also a one-line data edit, not a code edit.
_LEDGER_EXEMPT: list[str] = []


def _coined_hit(seg: str, path: str = "", bans=()) -> str | None:
    """One new-diff-line -> coined term it carries (None = clean).

    The line-level judgement is single-sourced here so --selftest can plant
    and exempt lines without staging a git diff. Ledger regexes make the word
    list data-driven: registering a variant in the ledger extends detection
    with no code edit (R55)."""
    if any(ok in seg for ok in COINED_OK) or any(ok in seg for ok in _LEDGER_EXEMPT):
        return None
    if "→" in seg and any(w in seg for w in ("刀", "blade", "钉")):
        return None
    norm = path.replace(os.sep, "/")
    if any(norm.endswith(rel) for rel in _DEFINITIONS):
        return None
    bad = next((c for c in COINED_CTX if c in seg), None)
    if bad:
        return bad
    if re.search(r"(?<![车冰])\d+刀|(?<![车冰])刀\d|(?<![\u4e00-\u9fff])blade\b",
                 seg, re.I):
        return "刀族/blade"
    for _name, rx in bans:
        if rx.search(seg):
            return f"台账:{_name}"
    return None


def scan_coined_diff(root: str) -> list[str]:
    d = subprocess.run(["git", "-C", root, "diff", "-U0", "HEAD"],
                       capture_output=True, text=True, errors="replace").stdout
    bans = _ledger_bans(root)
    hits: list[str] = []
    cur = "?"
    for ln in d.splitlines():
        if ln.startswith("+++ b/"):
            cur = ln[6:]
            continue
        if not ln.startswith("+") or ln.startswith("+++"):
            continue
        seg = ln[1:]
        bad = _coined_hit(seg, cur, bans)
        if bad:
            hits.append(f"{cur}: 新增行含自造词「{bad}」→ …{seg.strip()[:72]}…")
    return hits




def selftest() -> int:
    """Two-sided check of the identifier/filename bookkeeping rule."""
    toks = _chain_tokens()
    assert toks, "链身份集为空=排除面静默空转（先修 scripts 导入路径，再读账）"
    # Fixture derives from the live registry, never from a literal: the old
    # sorted()[0] pick assumed the first token was ticket-shaped, which broke
    # once every ticket-shaped chain had retired (the gate then red-flagged
    # itself). Each real token now proves its own exemption, and the ticket
    # branch keeps a guaranteed hit via one synthetic token (R43③: an
    # exemption branch that never fires is a defect, not a pass).
    for tk in sorted(toks):
        probe = f"{tk}_run_hot_e2e.sh"
        assert any(re.search(rf"(?:^|[-_./]){re.escape(t)}", probe.lower()) for t in toks), \
            f"链名文件未被排除={probe} 会被记进任务号账（虚高）"
    ticket_tok = next((t for t in sorted(toks) if re.search(r"t\d{2,4}", t)), None)
    token = ticket_tok if ticket_tok else "t599zz"   # synthetic keeps the ticket branch alive
    chain_file = f"{token}_run_hot_e2e.sh"       # the exemption must fire for real chain names
    ticket_file = "test_z999_pure_ticket.py"
    cammel = "T257BatchReportTest.java"
    suffixed = "test_t190b1_http.py"
    # Probe pattern must equal the gate pattern (hyphen separators included):
    # chain names are hyphen-shaped (y-t285t286), and the old ^|_ class made
    # this self-assert miss its own target — the tool red-flagged itself.
    assert re.search(r"(?:^|[-_./])t\d{2,4}", chain_file), "ticket 形态探针必须命中任务号形态"
    exempt = any(re.search(rf"(?:^|[-_./]){re.escape(tk)}", chain_file.lower()) for tk in toks)
    assert exempt or token == "t599zz", "在册链名探针未被排除=它会被记进任务号账（虚高）"
    assert not any(re.search(rf"(?:^|[-_./]){re.escape(tk)}", ticket_file.lower()) for tk in toks), \
        "真任务号文件被链名排除吞掉=漏账"
    for name in (cammel, suffixed):
        assert re.search(r"T\d{2,4}", name) or re.search(r"(?:^|_)t\d{2,4}", name), \
            f"CamelCase/多字母后缀任务号被漏判: {name}"
    assert re.search(r"(?:^|[-_./])t\d{2,4}", "t171-ensure-tooling.sh"), "shell 任务号名必须计入"
    # Two-sided case for the metaphor-gate exemptions (R43③: an exclusion
    # branch without a proving fixture is a silent no-op). The ledger data
    # file and the zero-false-positive citation line must be exempt, while
    # the very same banned word on an ordinary source line must still hit.
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        tools = os.path.join(td, "tools")
        os.makedirs(tools, exist_ok=True)
        led = os.path.join(tools, "term_lint_ledger.json")
        with open(led, "w", encoding="utf-8") as fh:
            fh.write('{"battery-metaphor": "电池", "copy": "抓手"}\n')
        assert scan_file(led) == [], "词表数据文件豁免分支空转（应零命中）"
        copy = os.path.join(tools, "random_data.json")
        with open(copy, "w", encoding="utf-8") as fh:
            fh.write('{"battery-metaphor": "电池"}\n')
        assert len(scan_file(copy)) == 1, "同词面在非豁免路径必须命中（排除面过宽）"
        quote = os.path.join(td, "docs", "裁决件.md")
        os.makedirs(os.path.dirname(quote), exist_ok=True)
        with open(quote, "w", encoding="utf-8") as fh:
            fh.write("引语豁免=设备电量电池/骁龙芯片的零误杀判据\n"
                     "循环跑到第三电池为止\n")
        qh = scan_file(quote)
        assert len(qh) == 1 and "第三电池" in qh[0], \
            f"引语豁免行应豁免、裸词行应命中: {qh}"
    # Coined-word diff judge, both directions (R43③ / R55: the gate must prove
    # it bites on every registered family, not just the words it was written
    # with). Probe strings live in the ledger data file so this gate's own
    # diff never carries a banned literal; reading them here also proves the
    # ledger surface is wired, an empty fixture section is a hard failure.
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    bans = _ledger_bans(repo_root)
    assert bans, "ledger ban list is empty = the data face matches nothing (fix tools/term_lint_ledger.json before trusting this gate)"
    try:
        with open(os.path.join(repo_root, _LEDGER_REL), encoding="utf-8") as fh:
            st_fix = json.load(fh)["selftest"]
    except (ValueError, OSError, KeyError) as exc:
        raise SystemExit(f"ledger selftest fixtures missing ({exc}) = detection capability cannot be proven")
    # Gather every registered bite_* family instead of a hardcoded list: the
    # generation family sat in the data file un-probed while the ban itself
    # had drifted to its canonical replacement word, and the gate stayed green
    # (R43: fixture coverage follows the ledger, never a frozen key list).
    probes = [s for k, v in st_fix.items() if k.startswith("bite_") for s in v]
    assert any(k.startswith("bite_") for k in st_fix), "bite fixture list is empty = the required probe never runs"
    for s in probes:
        assert _coined_hit(s, "fixture/consumer.ts", bans), f"coined-term probe missed = gate never runs: {s}"
    for s in st_fix["clean"]:
        assert _coined_hit(s, "fixture/consumer.ts", bans) is None, f"exemption face false positive: {s}"
    # Definition surface exemption: the very same banned word inside the gate
    # files must not self-flag (registering a word could never otherwise land).
    assert _coined_hit(probes[0], _LEDGER_REL, bans) is None, "definition-surface exemption broken = registering a word flags itself"
    _java_runtime_selftest()
    print(f"TERM-LINT SELFTEST PASS (chain-name exclusion hits={sorted(toks)[:3]}; "
          f"CamelCase and letter-suffixed task codes still counted; two-way exemption fixtures in place; empty set raises)")
    return 0


def main(argv=None) -> int:
    if "--selftest" in (argv if argv is not None else sys.argv[1:]):
        return selftest()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    files = [os.path.join(root, f) for f in _tracked_text_files(root)]
    for extra in sys.argv[1:]:
        p = os.path.abspath(extra)
        if os.path.isfile(p):
            files.append(p)
        elif os.path.isdir(p):
            for dirpath, _dirs, names in os.walk(p):
                if "/target/" in dirpath or "/node_modules/" in dirpath or "/.git/" in dirpath:
                    continue
                files.extend(os.path.join(dirpath, n) for n in names)
    all_hits: list[str] = []
    for f in files:
        all_hits.extend(scan_file(f))
    bk_hits = scan_bookkeeping(root)
    seed_hits = scan_seed_bookkeeping(root)
    if len(seed_hits) > LEGACY_SEED_BOOKKEEP_BASELINE:
        bk_hits.extend(
            [h for h in seed_hits]
            + [f"seed/fixture bookkeeping-code debt {len(seed_hits)} > baseline {LEGACY_SEED_BOOKKEEP_BASELINE} "
               f"(the baseline only shrinks: lower it in the same batch as the cleanup, never add new entries of this type)"])
    else:
        print(f"SEED-BOOKKEEP legacy debt: {len(seed_hits)}/{LEGACY_SEED_BOOKKEEP_BASELINE}"
              f" (the baseline shrinks in the same batch as each cleanup)")
    w6_hits = scan_w6_locale(root)
    cd_hits = scan_coined_diff(root)
    id_hits = scan_identifier_bookkeeping(root)
    j_hits = scan_java_runtime_cjk(root)
    jd_hits = scan_java_runtime_cjk_diff(root)
    j_over = (len(j_hits) > JAVA_RUNTIME_CJK_BASELINE)
    if not j_over:
        print(f"JAVA-RUNTIME-CJK legacy debt: {len(j_hits)}/{JAVA_RUNTIME_CJK_BASELINE}"
              f" (the baseline shrinks in the same batch as each domain cleanup)")
    legacy_n = count_legacy_ident_names(root)
    if _CHAIN_TOKEN_WARN:
        print("TERM-LINT WARN: " + " / ".join(_CHAIN_TOKEN_WARN))
    if legacy_n > LEGACY_IDENT_BASELINE:
        id_hits.append(f"legacy bookkeeping-code file names {legacy_n} > baseline {LEGACY_IDENT_BASELINE}"
                       f" (the baseline only shrinks: clean up stock or rename to capability names, never add new files of this type)")
    if all_hits:
        print(f"TERM-LINT FAIL: {len(all_hits)} coined metaphor terms found (rule 2026-09-28)")
        for h in all_hits[:200]:
            print(h)
        if len(all_hits) > 200:
            print(f"... {len(all_hits) - 200} more not listed")
    if bk_hits:
        print(f"TERM-LINT FAIL: {len(bk_hits)} screen strings start with internal bookkeeping codes (rule 2026-10-18: task numbers belong in comments/docs, never in the UI)")
        for h in bk_hits[:100]:
            print(h)
    if w6_hits:
        print(f"TERM-LINT FAIL: {len(w6_hits)} user-visible strings carry internal governance wording (rule 2026-10-24: process self-narration must not reach the screen; UI text shows operation info only - defaults, ranges, rejection consequences, where to read causes)")
        for h in w6_hits[:100]:
            print(h)
    if cd_hits:
        print(f"TERM-LINT FAIL: {len(cd_hits)} new diff lines use coined vocabulary (rule 2026-10-18: replace invented metaphors with industry terms or plain descriptions; existing stock is not rewritten retroactively)")
        for h in cd_hits[:100]:
            print(h)
    if id_hits:
        print(f"TERM-LINT FAIL: {len(id_hits)} identifiers or new file names carry internal bookkeeping codes (rule 2026-10-24: class/file/method names describe capability; task, migration and case numbers stay in on-disk docs)")
        for h in id_hits[:100]:
            print(h)
    if jd_hits:
        print(f"TERM-LINT FAIL: {len(jd_hits)} new Java lines add CJK runtime text (rule 2026-11-24: operator-visible logger and exception text ships in English; UI-mapped message families keep the bilingual track and are exempt)")
        for h in jd_hits[:100]:
            print(h)
    if j_over:
        print(f"TERM-LINT FAIL: Java CJK runtime-text stock {len(j_hits)} > baseline {JAVA_RUNTIME_CJK_BASELINE} (rule 2026-11-24: the ratchet only shrinks; lower the baseline in the same batch as each cleanup, never add new offenders of this type)")
        for h in j_hits[:100]:
            print(h)
    if all_hits or bk_hits or cd_hits or id_hits or w6_hits or jd_hits or j_over:
        return 1
    print(f"TERM-LINT PASS (coinage scan + screen bookkeeping-code scan + UI governance-word scan + diff coinage scan + identifier bookkeeping scan + Java runtime-text scan; legacy file-name baseline {legacy_n}/{LEGACY_IDENT_BASELINE})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
