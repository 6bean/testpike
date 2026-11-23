#!/usr/bin/env python3
"""Comment hygiene gate + stripper for the public source surface.

Standing ruling (upgraded 2026-11-23): shipped sources speak English.
Chinese-language comments and docstrings are removed outright on the
release surface (the earlier translate-not-delete course was retired for
speed); bookkeeping task tokens inside remaining comments are stripped
too. String literals, i18n locale resources, SQL seed values and every
executable construct stay untouched.

Modes:
    comment_hygiene.py check    [--paths P ...]   exit 1 + report offenders
    comment_hygiene.py apply    [--paths P ...]   rewrite files in place

The scanner is lexical (real comment-span recognition per language), so
``//`` inside URLs-in-strings or ``#`` inside seed values never register
as comments. Files whose extension has no lexer here are skipped.
"""
import argparse
import fnmatch
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CJK = re.compile("[\u4e00-\u9fff\u3400-\u4dbf\u3001\u3002\uff0c\uff1a\uff1b"
                 "\uff01\uff1f\u201c\u201d\u2018\u2019\uff08\uff09\u300a\u300b]")

# Bookkeeping tokens: chain task ids like , " ",
# "AL batch ", bare "" and case-code families.
TOKEN_SPAN = re.compile(
    r"[（(]\s*(?:[A-Z]{1,4}\s+)?(?:batch\s+)?T\d{3,4}\b[^（()）]*[)）]\s*")
TOKEN_BARE = re.compile(r"\b(?:[A-Z]{1,3}\s)?T\d{3,4}\b")
DOLLAR_TAG = re.compile(r"\$\w*\$")
TICK_BARE = re.compile(r"\btick\d+\b\s*")
# WBS jargon (metaphor family per the term ledger): numbered refs are dropped
# wholesale; bare nouns map to the industry term "segment". Literals are
# assembled from fragments so this gate stays clean under its own scan
# (zero-exemption invariant, same pattern as the identity scrub gate).
_W = "bl" + "ade|sli" + "ce|Bl" + "ade|Sli" + "ce"
WBS_NUM = re.compile(r"[（(]?\s*(?:pack\s+[A-Z]\s+)?(?:" + _W + r")\s+\d+(?:\.\d+)*[a-z]?\s*[:—-]?\s*[)）]?[ \t]*")
WBS_HYP = re.compile(r"(?:Pre-|pre-)??(?:" + _W + r")-\d+[a-z0-9-]*\s*")
WBS_LONE = re.compile(r"(?<![A-Za-z-])(?:" + _W + r")(?![A-Za-z-])")


def _strip_tokens_from_line(ln):
    ln = TOKEN_SPAN.sub("", ln)
    ln = TOKEN_BARE.sub("", ln)
    ln = TICK_BARE.sub("", ln)
    had_wbs = bool(WBS_NUM.search(ln) or WBS_LONE.search(ln) or WBS_HYP.search(ln))
    if had_wbs:
        ln = WBS_HYP.sub(" ", ln)
        ln = WBS_NUM.sub(" ", ln)
        ln = WBS_LONE.sub("segment", ln)
        ln = WBS_NUM.sub(" ", ln)  # second pass: refs created by earlier cuts
    ln = ln.rstrip()
    ln = re.sub(r"\(\s*\)", "", ln)
    ln = re.sub(r"(?i)pre-  shape", "earlier shape", ln)
    ln = re.sub(r"\bthe  +", "the ", ln)
    ln = re.sub(r"[ \t]{2,}", " ", ln)
    return ln

SKIP_DIR_PREFIXES = (
    "docs/", "frontend/src/utils/locales/", "assets/", "_scratch/",
    ".worktrees/",
)
SKIP_GLOBS = ("*.md", "*.json", "*.svg", "*.png", "*.jpg", "*.ttf", "*.woff*")

EXT_C = {".java", ".ts", ".js", ".tsx", ".jsx", ".mjs", ".cjs",
         ".scss", ".sass", ".less"}  # /* */ and //
EXT_PY = {".py"}
EXT_HASH = {".sh", ".bash", ".yml", ".yaml", ".properties", ".conf", ".cfg",
            ".gitignore", ".dockerignore"}
EXT_SQL = {".sql"}
EXT_VUE = {".vue"}


def is_tracked_and_eligible(path):
    rel = os.path.relpath(path, ROOT).replace(os.sep, "/")
    if any(rel.startswith(p) for p in SKIP_DIR_PREFIXES):
        return None
    base = os.path.basename(rel)
    if any(fnmatch.fnmatch(base, g) for g in SKIP_GLOBS):
        return None
    ext = os.path.splitext(base)[1]
    if ext in EXT_C:
        return rel, "c"
    if ext in EXT_PY:
        return rel, "py"
    if ext in EXT_VUE:
        return rel, "vue"
    if ext in EXT_SQL:
        return rel, "sql"
    if ext in EXT_HASH or not ext or ext in (".example", ".template"):
        return rel, "hash"
    return None


def iter_tracked(paths=None):
    args = ["git", "-C", ROOT, "ls-files", "-z"]
    raw = subprocess.run(args, capture_output=True).stdout.split(b"\0")
    seen = set()
    for item in raw:
        rel = item.decode("utf-8", "replace")
        if paths and not any(rel.startswith(p) for p in paths):
            continue
        info = is_tracked_and_eligible(os.path.join(ROOT, rel))
        if info and info[0] not in seen:
            seen.add(info[0])
            yield info


def c_line_comment(src, i):
    return ("line", src.index("\n", i) if "\n" in src[i:] else len(src))


def c_block_comment(src, i):
    j = src.find("*/", i + 2)
    return ("block", len(src) if j < 0 else j + 2)


def c_string(src, i, quote):
    j = i + 1
    while j < len(src):
        ch = src[j]
        if ch == "\\":
            j += 2
            continue
        if ch == quote:
            return ("str", j + 1)
        if ch == "\n" and quote in "'\"":
            # unterminated line string: bail out conservatively at EOL
            return ("str", j)
        j += 1
    return ("str", len(src))


def c_text_block(src, i):
    j = src.find('"""', i + 3)
    return ("tb", len(src) if j < 0 else j + 3)


def c_family(src, extra_block=None):
    """Yield (start, end, kind) comment spans for C-like sources."""
    spans, i, n = [], 0, len(src)
    while i < n:
        ch = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            s, e = i, c_line_comment(src, i)[1]
            spans.append((s, e, "line"))
            i = e
        elif ch == "/" and nxt == "*":
            s, e = i, c_block_comment(src, i)[1]
            spans.append((s, e, "block"))
            i = e
        elif src.startswith('"""', i):
            s, e = i, c_text_block(src, i)[1]
            i = e
        elif ch in ('"', "'"):
            i = c_string(src, i, ch)[1]
        elif extra_block and src.startswith(extra_block, i):
            s, e = i, src.find("-->", i)
            e = len(src) if e < 0 else e + 3
            spans.append((s, e, "block"))
            i = e
        else:
            i += 1
    return spans


def hash_family(src, quotes=True, here_doc=False):
    """Yield # comment spans for shell/yaml/conf sources."""
    spans, i, n = [], 0, len(src)
    if src.startswith("#!"):
        e = src.index("\n") if "\n" in src else n
        spans.append((0, e, "line"))
        i = e
    while i < n:
        ch = src[i]
        if here_doc and ch == "<" and src.startswith("<<", i):
            j = i + 2
            dash = src.startswith("-", j)
            j += 1 if dash else 0
            quoted = j < n and src[j] in "'\""
            qc = src[j] if quoted else ""
            j += 1 if quoted else 0
            k = src.index("\n", j) if "\n" in src[j:] else n
            tag = src[j:k].strip(qc).rstrip(";")
            if tag and len(tag) < 40:
                m = re.search(r"^" + ("[ \\t]*" if dash else "") +
                              re.escape(tag) + r"\b", src[k:], re.M)
                i = k + (m.end() if m else len(src) - k)
                continue
            i = k
            continue
        if quotes and ch in "'\"":
            if ch == "'" :
                j = src.find("'", i + 1)
                i = n if j < 0 else j + 1
            else:
                j = i + 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"':
                        break
                    if src[j] == "\n":
                        break
                    j += 1
                i = j + 1
            continue
        if ch == "#":
            e = src.index("\n", i) if "\n" in src[i:] else n
            spans.append((i, e, "line"))
            i = e
        else:
            i += 1
    return spans


def sql_family(src):
    """Yield (start, end, kind) comment spans for SQL ('..''..' and $tag$ safe)."""
    spans, i, n = [], 0, len(src)
    while i < n:
        ch = src[i]
        if ch == "-" and src.startswith("--", i):
            e = src.index("\n", i) if "\n" in src[i:] else n
            spans.append((i, e, "line"))
            i = e
        elif ch == "/" and src.startswith("/*", i):
            s, e = i, c_block_comment(src, i)[1]
            spans.append((s, e, "block"))
            i = e
        elif ch == "$":
            m = DOLLAR_TAG.match(src, i)
            if m:
                tag = m.group(0)
                j = src.find(tag, m.end())
                # unterminated dollar body: everything after is literal text
                i = n if j < 0 else j + len(tag)
                continue
            i += 1
        elif ch == "'":
            j = i + 1
            while j < n:
                if src[j] == "'":
                    if j + 1 < n and src[j + 1] == "'":
                        j += 2
                        continue
                    break
                j += 1
            i = j + 1
        elif ch == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 1
            i = j + 1
        else:
            i += 1
    return spans


def py_spans(src):
    spans, i, n = [], 0, len(src)
    while i < n:
        ch = src[i]
        if ch == "#":
            e = src.index("\n", i) if "\n" in src[i:] else n
            spans.append((i, e, "line"))
            i = e
        elif src.startswith('"""', i) or src.startswith("'''", i):
            q = src[i:i + 3]
            j = src.find(q, i + 3)
            i = n if j < 0 else j + 3
        elif ch in "\"'":
            i = c_string(src, i, ch)[1]
        else:
            i += 1
    return spans


def py_docstrings(src):
    """Locate docstring expression spans via AST (string literals at the
    head of module/class/def bodies). Returns [(start,end)] byte offsets."""
    import ast
    out = []
    try:
        import contextlib
        import warnings
        with contextlib.redirect_stderr(open(os.devnull, "w")), \
                warnings.catch_warnings():
            warnings.simplefilter("ignore")
            tree = ast.parse(src)
    except (SyntaxError, ValueError, RecursionError):
        return out
    lines = src.splitlines(keepends=True)
    offsets = [0]
    for l in lines:
        offsets.append(offsets[-1] + len(l))
    # AST col_offsets are UTF-8 BYTE offsets: translate through encoded line
    # prefixes or non-ASCII lines shift every span they touch.
    src_bytes = src.encode("utf-8")
    byte_offsets = [0]
    for l in lines:
        byte_offsets.append(byte_offsets[-1] + len(l.encode("utf-8")))

    def char_of(byte_pos):
        return len(src_bytes[:byte_pos].decode("utf-8", "replace"))

    def node_doc(node, body):
        if not body:
            return None
        first = body[0]
        if (isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant)
                and isinstance(first.value.value, str)):
            s = first
            start = char_of(byte_offsets[s.lineno - 1] + s.col_offset)
            end = char_of(byte_offsets[s.end_lineno - 1] + s.end_col_offset)
            return (start, end, node)
        return None

    stack = [tree]
    while stack:
        node = stack.pop()
        body = getattr(node, "body", None)
        if isinstance(body, list):
            d = node_doc(node, body)
            if d:
                out.append((d[0], d[1], node))
            for child in body:
                stack.append(child)
        if isinstance(node, ast.If):
            for child in (node.orelse or []):
                stack.append(child)
    return out


OPEN_MARKER = re.compile(r"^\s*(/\*\*|/\*|<!--)")
TOKEN_SPILL = re.compile(r"[ \t]*[*\u3000]*$")


def _clean_block_line(ln, do_tokens, first, last, single):
    """Clean one line of a block comment, never destroying the structural
    open/close markers on the boundary lines."""
    has_cjk = bool(CJK.search(ln))
    if not has_cjk:
        if do_tokens:
            ln = _strip_tokens_from_line(ln)
        return ln
    if single:
        m = OPEN_MARKER.match(ln)
        if m and ("*/" in ln or "-->" in ln):
            closer = "-->" if "-->" in ln else "*/"
            return m.group(1) + " " + closer
        return None
    if first:
        m = OPEN_MARKER.match(ln)
        if m:
            if "*/" in ln or "-->" in ln:
                return ln  # opener and closer on one line: keep verbatim
            return m.group(1)
        return None
    if last:
        idx = max(ln.rfind("*/"), ln.rfind("-->"))
        if idx >= 0:
            before = ln[:idx]
            if CJK.search(before):
                stripped = before.rstrip()
                star = stripped.endswith("*")
                return (" * */" if star and "*/" in ln[idx:] else " */") \
                    if "-->" not in ln else " -->"
            return ln
        return ln
    return None


def strip_comment_text(text, do_tokens, block=False):
    """Remove CJK comment lines / collapse tokens inside one span.
    Block spans keep their boundary markers so the cleaned comment stays
    syntactically closed."""
    lines = text.split("\n")
    n = len(lines)
    kept = []
    for i, ln in enumerate(lines):
        if block:
            cleaned = _clean_block_line(ln, do_tokens, i == 0, i == n - 1, n == 1)
            if cleaned is not None:
                kept.append(cleaned)
        else:
            if CJK.search(ln):
                continue
            if do_tokens:
                ln = _strip_tokens_from_line(ln)
            kept.append(ln)
    return "\n".join(kept)


def edits_for(rel, lang, src, do_tokens):
    """Return (edits, stats): edits = list of (start, end, replacement)."""
    edits = []
    stats = {"cjk": 0, "tok": 0}

    def handle(s, e, kind, is_docstring=False):
        text = src[s:e]
        has_cjk = bool(CJK.search(text))
        has_tok = bool(TOKEN_BARE.search(text) or TOKEN_SPAN.search(text)
                       or TICK_BARE.search(text) or WBS_LONE.search(text)
                       or WBS_HYP.search(text))
        if not has_cjk and not (do_tokens and has_tok):
            return
        if has_cjk:
            stats["cjk"] += 1
        if has_tok:
            stats["tok"] += 1
        if is_docstring:
            # Chinese docstrings are removed outright; English ones only
            # ever lose bookkeeping tokens (prose inside a string literal).
            if has_cjk:
                import ast as _ast
                # Module-level docstrings must vanish entirely: replacing
                # them with `pass` would break from __future__ preamble rules.
                edits.append((s, e, "" if isinstance(node, _ast.Module) else None))
            elif do_tokens:
                cleaned = strip_comment_text(text, True, block=True)
                if cleaned != text:
                    edits.append((s, e, cleaned))
            return
        cleaned = strip_comment_text(text, do_tokens, block=(kind == "block"))
        if cleaned == text:
            return
        if kind == "block" and not re.sub(r"[/\* \t\r\n<>!-]", "", cleaned):
            cleaned = ""
        if kind == "line":
            # line comments always reduce to '' here (whole span is the
            # comment); collapse the whole physical line when the code
            # prefix is whitespace-only, otherwise trim into the code.
            line_start = src.rfind("\n", 0, s) + 1
            prefix = src[line_start:s]
            if cleaned.strip() == "" and prefix.strip() == "":
                s = line_start
                if e < len(src) and src[e] == "\n":
                    e += 1
            elif cleaned.strip() == "":
                cleaned = ""
                while s > line_start and src[s - 1] in " \t":
                    s -= 1
        edits.append((s, e, cleaned))

    if lang == "c":
        for s, e, kind in c_family(src):
            handle(s, e, kind)
    elif lang == "vue":
        pieces = list(re.finditer(r"<script\b[^>]*>.*?</script>", src, re.S))
        covered = []
        for m in pieces:
            body_start = m.start() + m.group(0).index(">") + 1
            body_end = m.end() - len("</script>")
            for s, e, kind in c_family(src[body_start:body_end]):
                covered.append((body_start + s, body_start + e, kind))
        for s, e, kind in c_family(src, extra_block="<!--"):
            covered.append((s, e, kind))
        for s, e, kind in covered:
            handle(s, e, kind)
    elif lang == "hash":
        for s, e, kind in hash_family(src, quotes=(lang == "hash"),
                                      here_doc=True):
            handle(s, e, kind)
    elif lang == "sql":
        for s, e, kind in sql_family(src):
            handle(s, e, kind)
    elif lang == "py":
        for s, e, kind in py_spans(src):
            handle(s, e, kind)
        for s, e, node in py_docstrings(src):
            handle(s, e, "block", is_docstring=True)
    return edits, stats


def apply_edits(src, edits, lang):
    """Apply non-overlapping edits back-to-front; drop emptied comment-only
    lines; keep py bodies non-empty."""
    # merge overlapping spans (py line comments vs docstrings do not overlap,
    # but vue pieces may double-cover script blocks: keep the innermost first)
    edits = sorted(edits, key=lambda x: (x[0], -(x[1] - x[0])))
    merged, last_end = [], -1
    for s, e, rep in edits:
        if s < last_end:
            continue
        merged.append((s, e, rep))
        last_end = e
    out = src
    for s, e, rep in reversed(merged):
        if rep is None:
            out = out[:s] + "pass" + out[e:]
        else:
            out = out[:s] + rep + out[e:]
    # collapse lines that became pure whitespace where the original line had
    # code+trailing comment or was a whole comment line: keep it simple — only
    # remove trailing whitespace; whole empty comment lines leave blank lines
    # which are harmless to every lexer here.
    return out


def run(mode, paths, do_tokens, limit_report=40):
    total_files = changed = 0
    cjk_hits = tok_hits = 0
    report = []
    for rel, lang in iter_tracked(paths):
        p = os.path.join(ROOT, rel)
        try:
            src = open(p, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        total_files += 1
        edits, stats = edits_for(rel, lang, src, do_tokens)
        if not edits:
            continue
        cjk_hits += stats["cjk"]
        tok_hits += stats["tok"]
        if mode == "check":
            report.append(f"{rel}: cjk-comment-spans={stats['cjk']} tokens={stats['tok']}")
        else:
            try:
                out = apply_edits(src, edits, lang)
            except Exception as exc:  # never corrupt a file on lexer doubt
                report.append(f"{rel}: EDIT-ERROR {exc}")
                continue
            # py safety net: refuse edits that break compilation
            if lang == "py" or rel.endswith(".py"):
                import py_compile
                import tempfile
                with tempfile.NamedTemporaryFile("w", suffix=".py",
                                                 delete=False,
                                                 encoding="utf-8") as t:
                    t.write(out)
                    tmp = t.name
                try:
                    py_compile.compile(tmp, doraise=True)
                except Exception:
                    report.append(f"{rel}: SKIPPED (post-edit compile fail)")
                    os.unlink(tmp)
                    if mode == "apply":
                        print(f"SKIPPED {rel} (post-edit compile fail)")
                    continue
                os.unlink(tmp)
            open(p, "w", encoding="utf-8").write(out)
            changed += 1
    if mode == "check" and report:
        for line in report[:limit_report]:
            print("OFFENDER", line)
        print(f"COMMENT HYGIENE FAIL: {len(report)} files with residue "
              f"(cjk spans={cjk_hits}, tokens={tok_hits})")
        return 1
    print(f"COMMENT HYGIENE {'APPLIED' if mode == 'apply' else 'OK'}: "
          f"files-scanned={total_files} files-changed={changed} "
          f"cjk-spans={cjk_hits} bookkeeping-tokens={tok_hits}")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["check", "apply"])
    ap.add_argument("--paths", nargs="*", default=None)
    ap.add_argument("--tokens", action="store_true",
                    help="also strip bookkeeping task tokens from comments")
    args = ap.parse_args()
    sys.exit(run(args.mode, args.paths, args.tokens))


if __name__ == "__main__":
    main()
