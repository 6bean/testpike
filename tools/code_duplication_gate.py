#!/usr/bin/env python3
"""code_duplication_gate.py -- R40 machine gate: the same logic written twice.

Detects normalized-identical blocks (>= threshold non-blank lines) appearing
in more than one file. Built for the parallel-chain hazard: chains cannot see
each other's in-flight work, so copying the same validation/dispatch/
rendering logic creates two decision sources that later diverge silently.

Detection semantics (honest boundary):
  * catches exact copies and whitespace/comment formatting variants
    (normalization strips comments and blank lines);
  * ignores windows whose normalized lines are ALL import/package/export
    declarations -- boilerplate co-typing, not duplicated logic;
  * does NOT catch renamed/reordered semantic duplication -- that is the job
    of the R40 pre-construction reconnaissance (scan the sibling chain's
    in-flight edit surface and the shared-component catalog) plus review.
    The gate is a floor, not a ceiling.

Usage:
  code_duplication_gate.py <root>                       # full scan
  code_duplication_gate.py <root> --diff-from REF       # flag groups touching
                                                        # files changed since REF
  code_duplication_gate.py <root> --emit-baseline F     # record stock groups
  code_duplication_gate.py <root> --baseline F          # exempt stock only for
                                                        # the SAME file sets --
                                                        # a new copy into a new
                                                        # file turns RED
  code_duplication_gate.py --selftest                   # 17 checks, exit 1 if
                                                        # the gate misfires

Config (hot-updatable; projects must not hardcode):
  DUP_GATE_MIN_LINES     window in non-blank lines, default 8
  DUP_GATE_EXCLUDES      comma-separated path fragments to skip
  DUP_GATE_INCLUDE_EXT   comma-separated source extensions

Exit: 0 green / 1 duplicates beyond baseline / 2 usage or IO error.
R40 authoritative text = ~/.hermes/skills/autonomous-ai-agents/long-running-tasks/scripts/rulings.yaml
"""
import argparse
import ast
import hashlib
import json
import os
import re
import subprocess
import sys

DEFAULT_EXCLUDES = (
    "node_modules,dist,build,target,__pycache__,.git,.venv,venv,"
    ".worktrees,"
    # i18n resource files are data, not logic: zh/en (and anchor) blocks are
    # deliberately byte-identical, and key symmetry has its own dedicated
    # contracts (i18nKeys parity tests). Flagging them here would push teams
    # to "extract a shared block" out of two required resource files.
    "/utils/locales,"
    "frontend/tests/e2e/report,_scratch,db/migration,docs"
)
DEFAULT_EXT = ".py,.java,.ts,.js,.vue,.go,.kt,.rs"

COMMENT_RE = re.compile(r"^\s*(//|/\*|\*|#(?!!))")

# Declaration boilerplate across the supported languages: java package/import,
# python import/from..import, js/ts/vue export/import. A window whose
# normalized lines are ALL declarations is import noise, not duplicated logic
# (false-positive class fixed here: two sibling classes with the same
# 8-line import block). Mixed windows (declaration + logic line) stay in.
# A trailing-comma bare identifier is a member line of a parenthesized
# multi-line import (java/python/js shapes alike) -- still a declaration;
# the lone closing paren ends such a block and is noise too.
# A lone annotation line (@SpringBootTest/@TestPropertySource class headers)
# is harness configuration boilerplate, not logic -- false-positive class
# fixed here: an import block plus the identical runner annotations below it
# formed a "mixed" window that was pure co-typing (live group proven 2026-11-05).
BOILERPLATE_RE = re.compile(
    r"^(?:import\b|package\b|export\b|from[^\n]*\bimport\b|[A-Za-z_]\w*,?$|\)$|@[\w.]+"
    # Vue SFC chrome: template/script/style open-close lines are file skeleton,
    # not logic. Without them a page tail (</template> + <script setup> + imports)
    # matches every other page's tail byte-for-byte (journey wall vs knowledge
    # doc list, E2E-rod false red on 2026-11-05; same treatment class as the
    # 0d1f97216 annotation-line extension).
    r"|</?template>$|<script setup[^>]*>$|</script>$|</?style[^>]*>$"
    # any bare closing tag (</div>, </el-dialog>, ...): markup skeleton, the
    # semantics live on the opening line with its attributes.
    r"|</[A-Za-z][\w-]*>$"
    # shebang line (#!/...): executable-file skeleton, never logic. Without
    # this the first window of two scripts that share only the interpreter
    # line plus their stdlib imports is counted as a mixed (logic) window and
    # flags as a false copy (live false-red 2026-11-14: the L4 case-linkage
    # spectrum and the vision capture script share shebang + 7 stdlib
    # imports -- declaration noise, same class as the closing-paren and
    # annotation-line extensions above).
    r"|#!/\S+)")


def norm_lines(text):
    """Normalized lines WITH their original file line numbers:
    [(stripped_line, file_lineno)]. Blank/comment lines drop out but positions
    stay pinned to the file, so a window can be intersected against the
    changed-line ranges of the current diff (line-aware --diff-from)."""
    out = []
    for i, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or COMMENT_RE.match(line):
            continue
        out.append((line, i))
    return out


def py_prose_masked(text):
    """Blank docstring/prose-literal line ranges before windowing (P18: prose
    never enters the duplication ledger -- shared documentation wording is
    shared language, not shared logic). Every bare string statement (module,
    class and function docstrings alike) is masked; assigned string data and
    inline literals stay in scope. On SyntaxError the text is kept unchanged:
    a file we cannot parse must never be silently exempted."""
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return text
    lines = text.splitlines()
    for node in ast.walk(tree):
        if (isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant)
                and isinstance(node.value.value, str)):
            for ln in range(node.lineno, (node.end_lineno or node.lineno) + 1):
                if 1 <= ln <= len(lines):
                    lines[ln - 1] = ""
    return "\n".join(lines)


def iter_files(root, excludes, exts):
    ex = [e.strip() for e in excludes if e.strip()]
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames
                       if not any(os.path.join(dirpath, d).replace("\\", "/").endswith(e)
                                  or ("/" + e + "/") in (os.path.join(dirpath, d) + "/").replace("\\", "/")
                                  for e in ex)]
        for fn in filenames:
            if os.path.splitext(fn)[1].lower() in exts:
                yield os.path.join(dirpath, fn)


def shingles(path, k):
    """Yield (block_hash, start_index, first_normalized_line, file_span)
    windows of k lines; file_span = (first_file_line, last_file_line) of the
    window in the CURRENT file (positions after normalization stay pinned)."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return
    if path.endswith(".py"):
        text = py_prose_masked(text)
    entries = norm_lines(text)
    for i in range(0, max(0, len(entries) - k + 1)):
        window_entries = entries[i:i + k]
        window_lines = [l for l, _ in window_entries]
        if all(BOILERPLATE_RE.match(l) for l in window_lines):
            continue  # pure declaration window: noise, never a group
        window = "\n".join(window_lines)
        h = hashlib.sha1(window.encode("utf-8")).hexdigest()
        yield h, i, window_lines[0], (window_entries[0][1], window_entries[-1][1])


def scan_all_windows(root, k, excludes, exts):
    seen = {}
    for path in iter_files(root, excludes, exts):
        for h, start, sample, span in shingles(path, k):
            seen.setdefault(h, []).append((path, start, sample, span))
    return seen


def files_of(spots):
    return {p for p, *_ in spots}


def groups_of(seen):
    """Duplicate groups: same normalized block in >=2 distinct files."""
    return {h: spots for h, spots in seen.items()
            if len(files_of(spots)) >= 2}


def load_baseline(path, root=None):
    """hash -> set(files) recorded as stock. Exemption requires the live
    group's file set to be a SUBSET of the recorded set, so copying existing
    stock logic into a NEW file is not exempt (that is exactly the R40 case).
    Old hash-only baselines load as exempt-nothing with a loud warning.
    Portability: a baseline may have been emitted from ANOTHER checkout of
    the same repo (e.g. a parallel worktree), which records absolute paths
    under its own root. When root is given, the recorded root prefix (the
    longest common directory of all absolute entries) is re-anchored to the
    live root, so exemptions keep working across worktrees."""
    if not path:
        return {}
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        print(f"GATE WARN: baseline unreadable ({e}); treating as empty")
        return {}
    if "block_hashes" in data and "groups" not in data:
        print("GATE WARN: baseline is old hash-only format (exempts nothing); "
              "re-emit with --emit-baseline to record file positions")
        return {}
    groups = {h: set(v) for h, v in data.get("groups", {}).items()}
    if root:
        live = os.path.abspath(root)
        # Relative entries are the shipped portable form: resolve against the
        # live root. Absolute entries (legacy emits) go through reanchor.
        rel = {h: {p if os.path.isabs(p) else os.path.normpath(os.path.join(live, p))
                   for p in v} for h, v in groups.items()}
        groups = reanchor(rel, live)
    return groups


def reanchor(groups, root):
    """Replace the recorded emit-root prefix with the live scan root.
    Entries may come from ANY checkout of the same repo (main tree or any
    parallel worktree, absolute form /<parent>/<tree>/<relpath>), so the
    remap is per-entry: everything under the recorded tree directory is
    re-rooted to the live scan root. A single commonprefix across mixed
    emit roots used to collapse to the parent directory and silently
    broke every exemption (fleet bridge tick 2026-11-22). Relative
    entries are already portable and stay untouched."""
    live = os.path.normpath(root)
    out = {}
    cache = {}
    for h, files in groups.items():
        new = set()
        for p in files:
            if not os.path.isabs(p):
                new.add(p)
                continue
            if p in cache:
                new.add(cache[p])
                continue
            parts = p.lstrip("/").split("/")
            hit = p
            # Deepest cut first: the recorded tree dir is the LAST parent
            # of the repo-relative remainder, so the shortest remainder
            # that exists under the live root is the correct remap.
            for i in range(len(parts) - 1, 0, -1):
                cand = os.path.join(live, *parts[i:])
                if os.path.exists(cand):
                    hit = cand
                    break
            cache[p] = hit
            new.add(hit)
        out[h] = new
    return out


def exempt(h, files_set, baseline):
    return h in baseline and files_set <= baseline[h]


def changed_line_ranges(root, ref):
    """path -> list of (start, end) NEW-SIDE line ranges touched since ref.

    Parsed from `git diff -U0 ref` (working tree incl. staged vs ref), so an
    edit far away from an untouched duplicate block no longer re-flags stock
    debt (line-aware diff mode; live permanent-red class 2026-11-08: every
    later edit to a strategy file re-flagged its untouched land/parseBody
    copies, pushing toward blanket baseline re-emits). New files arrive as one
    full-range hunk: every window inside them overlaps -> still flagged."""
    try:
        raw = subprocess.check_output(
            ["git", "-C", root, "diff", "-U0", ref], text=True,
            stderr=subprocess.STDOUT)
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"GATE ERROR: git diff failed: {e}")
        return None
    ranges, cur = {}, None
    hunk_re = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
    for line in raw.splitlines():
        if line.startswith("+++ "):
            p = line[4:].split("\t")[0]
            cur = None
            if p.startswith("b/"):
                cur = os.path.normpath(
                    os.path.join(root, p[2:]))
                ranges.setdefault(cur, [])
            continue
        m = hunk_re.match(line)
        if m and cur is not None:
            start = int(m.group(1))
            n = int(m.group(2)) if m.group(2) is not None else 1
            if n > 0:
                ranges[cur].append((start, start + n - 1))
    return ranges


def overlaps(span, file_ranges):
    for s, e in file_ranges:
        if span[0] <= e and s <= span[1]:
            return True
    return False


def run(k, excludes, exts, root, baseline_path, diff_from, emit_path):
    ranges = {}
    if diff_from:
        ranges = changed_line_ranges(root, diff_from)
        if ranges is None:
            return 2

    seen = scan_all_windows(root, k, excludes, exts)
    all_groups = groups_of(seen)

    if emit_path:
        # Portable form: repo-relative paths keep identity literals out of
        # shipped baselines and load_baseline resolves them against the live root.
        groups = {h: sorted(os.path.relpath(p, root) for p in files_of(spots))
                  for h, spots in all_groups.items()}
        with open(emit_path, "w", encoding="utf-8") as f:
            json.dump({"groups": groups, "min_lines": k}, f)
        print(f"baseline emitted: {len(groups)} stock duplicate groups -> {emit_path}")
        return 0

    base = load_baseline(baseline_path, root)

    def relevant(spots):
        if not diff_from:
            return True
        # Line-aware: a spot only involves this batch when its CURRENT file
        # lines intersect the batch's changed ranges for that file. Untouched
        # stock blocks inside an edited file are not this batch's debt.
        return any(p in ranges and overlaps(span, ranges[p])
                   for p, _, _, span in spots)

    flagged, stock_exempt = [], 0
    for h, spots in all_groups.items():
        if exempt(h, files_of(spots), base):
            stock_exempt += 1
            continue
        if relevant(spots):
            flagged.append((h, spots))

    shown = set()
    count = 0
    for h, spots in sorted(flagged):
        key = tuple(sorted(files_of(spots)))
        if key in shown:
            continue
        shown.add(key)
        count += 1
        locs = "; ".join(f"{os.path.relpath(p, root)}:{span[0]}-{span[1]}"
                         for p, _, _, span in spots[:4])
        print(f"DUPLICATE h={h[:12]} blocks={locs} first={spots[0][2][:60]!r}")
    scope = f"changed-since-{diff_from}" if diff_from else "full"
    print(f"summary: mode={scope} stock_groups_exempted={stock_exempt} "
          f"flagged_groups={count} min_lines={k}")
    if count:
        print("GATE RED: identical logic blocks beyond baseline -- R40: "
              "extract a shared component, consume the single source.")
        return 1
    print("GATE GREEN")
    return 0


def selftest(k, excludes, exts):
    """Copy detected / unique clean / baseline exempts recorded pair /
    same block copied into a THIRD file turns red again / diff mode flags
    changed-file involvement."""
    import tempfile
    body = "\n".join(f"    value = step{idx} * 2 + offset" for idx in range(1, k + 1))
    with tempfile.TemporaryDirectory() as td:
        a = os.path.join(td, "a.py")
        b = os.path.join(td, "b.py")
        with open(a, "w") as f:
            f.write("def alpha():\n" + body + "\n    return value\n")
        with open(b, "w") as f:
            f.write("# comment noise\n\ndef beta():\n" + body + "\n    return value\n")
        dirty_ok = len(groups_of(scan_all_windows(td, k, excludes, exts))) > 0

        with open(b, "w") as f:
            f.write("def beta():\n    return 42\n")
        clean_ok = len(groups_of(scan_all_windows(td, k, excludes, exts))) == 0

        with open(b, "w") as f:
            f.write("def beta():\n" + body + "\n    return value\n")
        bl = os.path.join(td, "baseline.json")
        emit_ok = run(k, excludes, exts, td, None, None, bl) == 0
        base = load_baseline(bl, td)
        pair_exempt = all(
            exempt(h, files_of(spots), base)
            for h, spots in groups_of(scan_all_windows(td, k, excludes, exts)).items())

        # Portability (worktree-reemit case): the same baseline recorded
        # under ANOTHER checkout root must still exempt the live pair once
        # re-anchored to the scan root; loaded without a root it must NOT
        # exempt (negative control for the re-anchor branch).
        live_groups = groups_of(scan_all_windows(td, k, excludes, exts))
        fake = os.path.join(td, "baseline-fake.json")
        with open(bl, encoding="utf-8") as f:
            emitted = json.load(f)
        shifted = {h: [os.path.join("/some/other/emit-root", p)
                       for p in v] for h, v in emitted["groups"].items()}
        with open(fake, "w") as f:
            json.dump({"groups": shifted, "min_lines": emitted["min_lines"]}, f)
        anchor_ok = all(exempt(h, files_of(spots), load_baseline(fake, td))
                        for h, spots in live_groups.items())
        # Mixed emit-root DEPTHS (live class fixed 2026-11-22: a fixed
        # 2-segment strip misplaced every entry of a 4-segment checkout
        # path /home/<user>/code/<tree>/rel, zeroing 12951 exemptions).
        mixed = {h: [os.path.join(d, p) for p in v]
                 for n, (h, v) in enumerate(emitted["groups"].items())
                 for d in ("/deep/nested/parent/chain-tree"
                           if n % 2 else "/some/other/emit-root",)}
        deep = os.path.join(td, "baseline-deep.json")
        with open(deep, "w") as f:
            json.dump({"groups": mixed, "min_lines": emitted["min_lines"]}, f)
        anchor_mixed_depth = all(
            exempt(h, files_of(spots), load_baseline(deep, td))
            for h, spots in live_groups.items()) if live_groups else False
        anchor_negative = all(
            not exempt(h, files_of(spots), load_baseline(fake))
            for h, spots in live_groups.items()) if live_groups else False

        # Line-aware diff mode, bidirectional (live permanent-red class fixed
        # 2026-11-08: file-level relevance re-flagged stock blocks that the
        # batch never touched, pushing toward blanket baseline re-emits).
        # The exclusion branch needs a miss case AND hit cases:
        #   MISS  - a real repo where the duplicated block stays untouched
        #           while a far-away edit lands -> NOT flagged;
        #   HIT   - a staged new-file copy of the block -> flagged;
        #   HIT   - an edit INSIDE the duplicated block -> flagged.
        with tempfile.TemporaryDirectory() as rp:
            genv = dict(os.environ, GIT_AUTHOR_NAME="gate",
                        GIT_AUTHOR_EMAIL="gate@local",
                        GIT_COMMITTER_NAME="gate", GIT_COMMITTER_EMAIL="gate@local")

            def gcommit(*a):
                subprocess.check_call(["git", *a], cwd=rp, env=genv,
                                      stdout=subprocess.DEVNULL)
            gcommit("init", "-q")
            for name in ("a.py", "b.py"):
                with open(os.path.join(rp, name), "w") as f:
                    f.write(f"def f_{name[0]}():\n" + body + "\n    return value\n")
            gcommit("add", "-A")
            gcommit("commit", "-qm", "two copies")
            with open(os.path.join(rp, "a.py"), "a") as f:
                f.write("\n\ndef far_extra_a(x):\n    return x + 1\n")
            untouched_block_green = run(k, excludes, exts, rp, None, "HEAD", None) == 0
            gcommit("add", "-A")
            gcommit("commit", "-qm", "far edit")
            with open(os.path.join(rp, "c.py"), "w") as f:
                f.write("def f_c():\n" + body + "\n    return value\n")
            gcommit("add", "c.py")
            staged_new_copy_red = run(k, excludes, exts, rp, None, "HEAD", None) == 1
            os.remove(os.path.join(rp, "c.py"))
            with open(os.path.join(rp, "b.py"), encoding="utf-8") as f:
                content = f.read()
            # Insert a blank line INSIDE the duplicated block: normalization
            # keeps the copy intact (group still exists) while the diff hunk
            # lands on the block's lines -> line-aware mode must flag it.
            with open(os.path.join(rp, "b.py"), "w") as f:
                f.write(content.replace("    value = step1 * 2 + offset\n",
                                        "    value = step1 * 2 + offset\n\n", 1))
            touched_block_red = run(k, excludes, exts, rp, None, "HEAD", None) == 1

        sub = os.path.join(td, "sub")
        os.makedirs(sub, exist_ok=True)
        with open(os.path.join(sub, "c.py"), "w") as f:
            f.write("def gamma():\n" + body + "\n    return value\n")
        third_red = run(k, excludes, exts, td, bl, None, None) == 1

        # Boilerplate filter, bidirectional (the exclusion branch needs both
        # a hit case and a miss case or it is a silent spin):
        #   * an identical pure import/package/export block in two files is
        #     declaration noise, not duplicated logic -> excluded;
        #   * the same import block FOLLOWED BY real logic is still flagged
        #     while only the pure-declaration window is dropped.
        with tempfile.TemporaryDirectory() as bp:
            imp_block = "\n".join(f"import dep{idx}" for idx in range(1, k + 1))
            for name in ("d.py", "e.py"):
                with open(os.path.join(bp, name), "w") as f:
                    f.write(imp_block + "\n")
            boilerplate_green = (
                len(groups_of(scan_all_windows(bp, k, excludes, exts))) == 0)
            logic_tail = "\n".join(f"    result{idx} = dep{idx} + idx"
                                   for idx in range(1, k + 1))
            for name in ("d.py", "e.py"):
                with open(os.path.join(bp, name), "w") as f:
                    f.write(imp_block + "\n" + logic_tail + "\n")
            g2 = groups_of(scan_all_windows(bp, k, excludes, exts))
            imp_hash = hashlib.sha1(imp_block.encode("utf-8")).hexdigest()
            logic_after_imports_red = len(g2) > 0 and imp_hash not in g2

            # Parenthesized import with member continuation lines
            # ("    dep0," / "dep0,"): every window is declaration noise
            # -> excluded; the same block followed by logic still flags.
            paren = os.path.join(td, "paren")
            os.makedirs(paren, exist_ok=True)
            for name in ("m.py", "n.py"):
                with open(os.path.join(paren, name), "w") as f:
                    f.write("import pytest\nfrom support import (\n"
                            + "\n".join(f"    dep{idx}," for idx in range(1, k + 1))
                            + "\n)\n")
            paren_green = (len(groups_of(scan_all_windows(paren, k, excludes, exts))) == 0)
            for name in ("m.py", "n.py"):
                with open(os.path.join(paren, name), "w") as f:
                    f.write("import pytest\nfrom support import (\n"
                            + "\n".join(f"    dep{idx}," for idx in range(1, k + 1))
                            + "\n)\n" + logic_tail + "\n")
            g3 = groups_of(scan_all_windows(paren, k, excludes, exts))
            paren_logic_tail_red = len(g3) > 0

            # Shebang + stdlib import skeleton, bidirectional (live false-red
            # 2026-11-14: two detached scripts sharing `#!/usr/bin/env python3`
            # plus the identical json/os/subprocess/sys/time/uuid/pathlib
            # import run -- interpreter line is file skeleton, not logic):
            #   * shebang + identical import block -> excluded;
            #   * the same header FOLLOWED by identical logic -> still flagged
            #     while only the header window drops.
            sbx = os.path.join(td, "shebang")
            os.makedirs(sbx, exist_ok=True)
            stdlib_head = ("#!/usr/bin/env python3\nimport json\nimport os\n"
                           "import subprocess\nimport sys\nimport time\n"
                           "import uuid\nfrom pathlib import Path\n")
            for name in ("s.py", "t.py"):
                with open(os.path.join(sbx, name), "w") as f:
                    f.write(stdlib_head + "\n")
            shebang_head_green = (
                len(groups_of(scan_all_windows(sbx, k, excludes, exts))) == 0)
            for name in ("s.py", "t.py"):
                with open(os.path.join(sbx, name), "w") as f:
                    f.write(stdlib_head + logic_tail + "\n")
            g_sbx = groups_of(scan_all_windows(sbx, k, excludes, exts))
            head_hash = hashlib.sha1(
                "\n".join(stdlib_head.splitlines()[1:]).encode("utf-8")
            ).hexdigest()
            shebang_logic_after_head_red = len(g_sbx) > 0 and head_hash not in g_sbx

            # Docstring prose, bidirectional (P18: prose never enters the debt
            # ledger -- identical documentation wording is shared language,
            # not shared logic; the branch needs a hit case AND a miss case):
            #   * two files sharing a long docstring but unique code are
            #     prose noise -> excluded;
            #   * the same docstring FOLLOWED by identical logic still flags
            #     while only the docstring windows drop.
            prose = os.path.join(td, "prose")
            os.makedirs(prose, exist_ok=True)
            doc_lines = [f"shared wording line {idx} for the family"
                         for idx in range(1, k + 1)]
            doc = '"""\n' + "\n".join(doc_lines) + '\n"""'
            with open(os.path.join(prose, "p.py"), "w") as f:
                f.write(doc + "\ndef p():\n    return 1\n")
            with open(os.path.join(prose, "q.py"), "w") as f:
                f.write(doc + "\ndef q():\n    return 2\n")
            prose_green = (len(groups_of(scan_all_windows(prose, k, excludes, exts))) == 0)
            for name, head in (("p.py", "def p():"), ("q.py", "def q():")):
                with open(os.path.join(prose, name), "w") as f:
                    f.write(doc + "\n" + head + "\n" + body + "\n    return value\n")
            g4 = groups_of(scan_all_windows(prose, k, excludes, exts))
            doc_hash = hashlib.sha1("\n".join(doc_lines).encode("utf-8")).hexdigest()
            prose_logic_still_flagged = len(g4) > 0 and doc_hash not in g4

            # Vue SFC chrome, bidirectional (live false-red 2026-11-05: a page
            # tail </template> + <script setup> + imports matches every other
            # page's tail byte-for-byte -- skeleton noise, not shared logic):
            #   * two pages sharing only the SFC chrome window -> excluded;
            #   * identical logic added after the chrome -> still flagged.
            vue = os.path.join(td, "vue")
            os.makedirs(vue, exist_ok=True)
            chrome = ('</el-dialog>\n</div>\n</template>\n\n<script setup lang="ts">\n'
                      + "\n".join(f"import comp{idx} from './c{idx}'"
                                  for idx in range(1, k + 1))
                      + "\n</script>\n")
            for name in ("x.vue", "y.vue"):
                with open(os.path.join(vue, name), "w") as f:
                    f.write(chrome)
            vue_chrome_green = (
                len(groups_of(scan_all_windows(vue, k, excludes, exts))) == 0)
            vue_logic = "\n".join(f"const row{idx} = items.value[{idx}].id"
                                  for idx in range(1, k + 1))
            for name in ("x.vue", "y.vue"):
                with open(os.path.join(vue, name), "w") as f:
                    f.write(chrome + "<template>\n" + vue_logic + "\n</template>\n")
            vue_logic_red = len(groups_of(scan_all_windows(vue, k, excludes, exts))) > 0

            # i18n resource exclusion, bidirectional (live false-red 2026-11-06:
            # the zh/en anchor blocks are byte-identical BY DESIGN -- resource
            # data, not shared logic; key symmetry has its own parity tests):
            #   * identical resource blocks under the locales path -> excluded;
            #   * the same content OUTSIDE that path -> still flagged
            #     (the exclusion branch has a hit case, it is not a blanket off).
            res_block = "\n".join(f"'anchor{idx}': 'chapter label {idx}',"
                                  for idx in range(1, k + 1))
            loc = os.path.join(td, "frontend/src/utils/locales")
            os.makedirs(loc, exist_ok=True)
            for name in ("zh-CN.ts", "en-US.ts"):
                with open(os.path.join(loc, name), "w") as f:
                    f.write("export default {\n" + res_block + "\n}\n")
            other = os.path.join(td, "src/other")
            os.makedirs(other, exist_ok=True)
            for name in ("a.ts", "b.ts"):
                with open(os.path.join(other, name), "w") as f:
                    f.write("export default {\n" + res_block + "\n}\n")
            # contrast on the isolated 'other' tree first (locales pair exists in
            # the same td but must be skipped by the exclusion -- red here means
            # the contrast is real), then settle the earlier py fixtures and
            # measure the green half with nothing else duplicated in td.
            outside_locales_red = (
                len(groups_of(scan_all_windows(other, k, excludes, exts))) > 0)
            # drop the contrast pair now that it has been measured (it lives
            # under td too and would otherwise pollute the green half)
            for name in ("a.ts", "b.ts"):
                pth = os.path.join(other, name)
                if os.path.exists(pth):
                    os.remove(pth)
            for name in ("a.py", "b.py"):
                pth = os.path.join(td, name)
                if os.path.exists(pth):
                    os.remove(pth)
            sub_py = os.path.join(td, "sub", "c.py")
            if os.path.exists(sub_py):
                os.remove(sub_py)
            vue_pair = os.path.join(td, "vue")
            if os.path.isdir(vue_pair):
                for name in ("x.vue", "y.vue"):
                    pth = os.path.join(vue_pair, name)
                    if os.path.exists(pth):
                        os.remove(pth)
            # neutralise the other fixture pairs (indexed variants -> unique):
            # the green half below then measures ONLY the locales contrast
            for idx, name in enumerate(("m.py", "n.py"), start=1):
                with open(os.path.join(paren, name), "w") as f:
                    f.write("import pytest\nfrom support import (\n"
                            + "\n".join(f"    dep{idx}_{j}," for j in range(1, k + 1))
                            + "\n)\n"
                            + "\n".join(f"    result{idx}_{j} = dep{idx}_{j} + idx"
                                        for j in range(1, k + 1)) + "\n")
            for idx, name in enumerate(("p.py", "q.py"), start=1):
                with open(os.path.join(prose, name), "w") as f:
                    f.write(f"def f{idx}():\n"
                            + "\n".join(f"    v{idx}_{j} = step{idx} * j"
                                        for j in range(1, k + 1))
                            + f"\n    return v{idx}_{k}\n")
            for idx, name in enumerate(("s.py", "t.py"), start=1):
                with open(os.path.join(sbx, name), "w") as f:
                    f.write(stdlib_head
                            + "\n".join(f"    result{idx}_{j} = dep{idx} + j"
                                        for j in range(1, k + 1)) + "\n")
            locales_green = (
                len(groups_of(scan_all_windows(td, k, excludes, exts))) == 0)
    results = {"dirty-copy-detected": dirty_ok, "unique-clean": clean_ok,
               "emit-ok": emit_ok, "baseline-exempts-recorded-pair": pair_exempt,
               "foreign-root-baseline-reanchors-exempts": anchor_ok,
               "mixed-depth-emit-roots-reanchor-exempt": anchor_mixed_depth,
               "unanchored-foreign-root-exempts-nothing": anchor_negative,
               "new-third-file-copy-flagged": third_red,
               "line-aware-untouched-block-not-flagged": untouched_block_green,
               "line-aware-staged-new-copy-flagged": staged_new_copy_red,
               "line-aware-touched-block-flagged": touched_block_red,
               "pure-import-window-excluded": boilerplate_green,
               "logic-after-imports-still-flagged": logic_after_imports_red,
               "parenthesized-import-window-excluded": paren_green,
               "parenthesized-import-logic-tail-still-flagged": paren_logic_tail_red,
               "shebang-import-skeleton-window-excluded": shebang_head_green,
               "logic-after-shebang-header-still-flagged": shebang_logic_after_head_red,
               "shared-docstring-prose-excluded": prose_green,
               "logic-after-docstring-still-flagged": prose_logic_still_flagged,
               "vue-sfc-chrome-window-excluded": vue_chrome_green,
               "vue-logic-after-chrome-still-flagged": vue_logic_red,
               "i18n-locale-resource-window-excluded": locales_green,
               "identical-resource-outside-locales-still-flagged": outside_locales_red}
    bad = [n for n, ok in results.items() if not ok]
    if bad:
        print("SELFTEST FAIL:", ", ".join(bad), "(gate idle-spins or exemptions wrong)")
        return 1
    print("SELFTEST PASS: " + "; ".join(results))
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?")
    ap.add_argument("--baseline", default=None)
    ap.add_argument("--emit-baseline", default=None)
    ap.add_argument("--diff-from", default=None,
                    help="git ref; only duplicate groups touching files changed "
                         "since this ref are flagged (stock untouched)")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    k = max(2, int(os.environ.get("DUP_GATE_MIN_LINES", "8")))
    excludes = os.environ.get("DUP_GATE_EXCLUDES", DEFAULT_EXCLUDES).split(",")
    exts = {e.strip().lower() for e in
            os.environ.get("DUP_GATE_INCLUDE_EXT", DEFAULT_EXT).split(",")}
    if args.selftest:
        sys.exit(selftest(k, excludes, exts))
    if not args.root:
        print("GATE ERROR: root required (or use --selftest)")
        sys.exit(2)
    sys.exit(run(k, excludes, exts, os.path.abspath(args.root),
                 args.baseline, args.diff_from, args.emit_baseline))


if __name__ == "__main__":
    main()
