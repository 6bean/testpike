"""Deterministic link frontier for the whole-site survey deep drill.

This module discovers and normalizes candidate page URLs for the site-survey
BFS loop; it never visits anything itself — the loop feeds adjacent links and
consumes the disposition of each. Deep-drill safety invariants (read-only,
same-origin only, destructive-link guard) live here as pure decisions so the
browser loop stays dumb.

Template-folding semantics are pinned by ONE shared corpus file, which is the
authority for both languages:
    core/src/test/resources/webelement/link_fold_corpus.json
The Java single source (ContractMatchKeyRules.foldedPath) and this naive
Python fold must fold every corpus row identically; both test suites read the
same file, so drift fails red immediately. Hot-update extra segment rules
(spec.path-normalize.rules) stay Java-side by design; production surveys
consume the dispatch envelope snapshot instead of re-reading config.

Resource bounds (R57): every accumulated map here (_seen, _fold_counts,
_depths, the BFS queue) only grows when a page is enqueued, and enqueueing is
hard-capped by max_pages / max_depth / deadline, so one frontier can never
grow past its budget.
"""
import re
import time
from collections import deque, namedtuple
from urllib.parse import parse_qsl, urljoin, urlencode, urlsplit, urlunsplit

WILDCARD = "{var}"
TEMPLATE_SEG = re.compile(r"\{[^}]*\}")
NUMERIC_SEG = re.compile(r"[0-9]+")
UUID_SEG = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
LONG_HEX_SEG = re.compile(r"[0-9a-fA-F]{16,}")

# Pagination/noise params stripped before dedup (archive-editable preset, the
# factory default mirrors the seeded element.survey-site key family).
DEFAULT_IGNORE_PARAMS = ("page", "pageNum", "pageNo", "size", "limit",
                         "offset", "timestamp", "_t", "rand")
# Built-in verb guard list (hot-updatable via element.survey-site
# .default-exclude-verbs; the archive can disable it, the factory never ships
# an empty guard). Matched case-insensitively against the path and anchor text.
DEFAULT_GUARD_VERBS = ("退出", "注销", "删除", "移除", "清空", "导出", "下载",
                       "logout", "signout", "delete", "remove", "destroy",
                       "unbind", "revoke", "export", "download")

Disposition = namedtuple("Disposition", "url action reason")

COUNTER_KEYS = ("enqueued", "dup", "cap", "sibling", "out_of_origin",
                "excluded", "verb_guard", "over_budget", "depth_limit",
                "non_http", "deadline")


def _normalize_path(raw_path):
    """Same canonicalization as the Java single source: strip query at the
    first '?', ensure a leading '/', drop trailing slashes (root kept)."""
    path = (raw_path or "").strip()
    q = path.find("?")
    if q >= 0:
        path = path[:q]
    if not path.startswith("/"):
        path = "/" + path
    while len(path) > 1 and path.endswith("/"):
        path = path[:-1]
    return path


def fold_template_path(raw_path):
    """Fold variable-shaped segments to {var}, corpus-pinned parity with
    ContractMatchKeyRules.foldedPath (see module docstring)."""
    path = _normalize_path(raw_path)
    folded_segments = []
    for seg in path.split("/"):
        if not seg:
            folded_segments.append(seg)
            continue
        seg = TEMPLATE_SEG.sub(WILDCARD, seg)
        if (NUMERIC_SEG.fullmatch(seg) or UUID_SEG.fullmatch(seg)
                or LONG_HEX_SEG.fullmatch(seg)):
            seg = WILDCARD
        folded_segments.append(seg)
    return "/".join(folded_segments)


def _compile_glob(pattern):
    """Ant-style path glob: '*' within one segment, '**' across segments,
    '?' one char; anchored full match."""
    out = []
    i = 0
    while i < len(pattern):
        ch = pattern[i]
        if ch == "*":
            if pattern[i:i + 2] == "**":
                out.append(".*")
                i += 2
                continue
            out.append("[^/]*")
        elif ch == "?":
            out.append("[^/]")
        else:
            out.append(re.escape(ch))
        i += 1
    return re.compile("".join(out) + r"\Z")


def _origin_of(scheme, netloc):
    return (scheme, netloc)


def top_segment(path):
    """First non-empty path segment ('' when the path is root)."""
    for seg in path.split("/"):
        if seg:
            return seg
    return ""


def page_key(url):
    """Canonical template key of one URL (origin + folded path, SPA route kept).

    Shared by the frontier's dedup and the deep-crawl loop's journal/redirect
    judgements — one key source, never a second derivation (red line 6).
    """
    sp = urlsplit(url)
    key = sp.scheme.lower() + "://" + sp.netloc.lower() + fold_template_path(
        _normalize_path(sp.path))
    if sp.fragment.startswith("/"):
        key += "#/" + sp.fragment[1:]
    return key


class LinkFrontier:
    pass

    def __init__(self, start_url, *, exclude_globs=(),
                 ignore_params=DEFAULT_IGNORE_PARAMS,
                 guard_verbs=DEFAULT_GUARD_VERBS, verbs_enabled=True,
                 max_pages=100, max_depth=6, max_links_per_page=60,
                 per_template_cap=1, deadline_seconds=1800.0,
                 clock=time.monotonic):
        sp = urlsplit(start_url)
        self.scheme = sp.scheme.lower()
        self.origin = _origin_of(self.scheme, sp.netloc.lower())
        scope = _normalize_path(sp.path)
        # Segment-aligned scope: "/pr" (root stays "/"). Matching on segment
        # boundaries is what keeps /pr/ from ever swallowing /print/.
        self.scope_prefix = scope if scope != "/" else "/"
        self.ignore_params = set(ignore_params)
        self.guard_verbs = tuple(v.lower() for v in guard_verbs)
        self.verbs_enabled = bool(verbs_enabled)
        self.max_pages = int(max_pages)
        self.max_depth = int(max_depth)
        self.max_links_per_page = int(max_links_per_page)
        self.per_template_cap = max(1, int(per_template_cap))
        self.deadline_seconds = float(deadline_seconds)
        self._clock = clock
        self._start = clock()
        self._globs = [_compile_glob(g) for g in exclude_globs]
        self._seen = set()
        self._fold_counts = {}
        self._depths = {}
        self._queue = deque()
        self.sibling_counts = {}
        self.counters = {k: 0 for k in COUNTER_KEYS}
        self._page_links = 0

    # ---- scope ----------------------------------------------------------

    def in_scope(self, path):
        if self.scope_prefix == "/":
            return True
        return path == self.scope_prefix or path.startswith(self.scope_prefix + "/")

    # ---- per-page politeness budget -------------------------------------

    def begin_page(self, url):
        """Called by the loop before feeding one page's adjacent links."""
        self._page_links = 0

    # ---- main entry -------------------------------------------------------

    def consider(self, base_url, href, anchor_text=""):
        self._page_links += 1
        absolute = urljoin(base_url or "", href or "")
        sp = urlsplit(absolute)
        scheme = sp.scheme.lower()
        if scheme not in ("http", "https"):
            return self._reject(absolute, "non_http", scheme or "empty")
        if _origin_of(scheme, sp.netloc.lower()) != self.origin:
            # Cross-origin links are counted, never visited (read-only red line).
            return self._reject(absolute, "out_of_origin", "different site origin")

        route = ""
        if sp.fragment.startswith("/"):
            route = sp.fragment[1:]  # SPA route is its own page, kept verbatim
        path = _normalize_path(sp.path)
        kept_query = [(k, v) for k, v in
                      parse_qsl(sp.query, keep_blank_values=True)
                      if k not in self.ignore_params]
        canonical = urlunsplit((scheme, sp.netloc, path,
                                urlencode(kept_query), ""))
        if route:
            if not canonical.endswith("/"):
                canonical += "/"
            canonical += "#/" + route

        if self.max_links_per_page and self._page_links > self.max_links_per_page:
            return self._reject(canonical, "over_budget", "per-page link budget")
        if not self.in_scope(path):
            if self.scope_prefix != "/":
                seg = top_segment(path)
                self.sibling_counts[seg] = self.sibling_counts.get(seg, 0) + 1
                return self._reject(canonical, "sibling", "other path segment " + seg)
            return self._reject(canonical, "out_of_origin", "outside scope")
        for glob in self._globs:
            if glob.match(path):
                return self._reject(canonical, "excluded", "exclude rule " + glob.pattern)
        if self.verbs_enabled:
            haystack = (path + " " + (anchor_text or "")).lower()
            for verb in self.guard_verbs:
                if verb in haystack:
                    # Destructive-verb link: counted, never followed.
                    self.counters["verb_guard"] += 1
                    return Disposition(canonical, "verb_guard", "guard verb " + verb)
        if canonical in self._seen:
            return self._reject(canonical, "dup", "already queued")
        fold_key = page_key(canonical)
        count = self._fold_counts.get(fold_key, 0)
        if count >= self.per_template_cap:
            # Pagination explosion stop: the template page is already sampled.
            return self._reject(canonical, "cap", "template cap " + fold_key)
        depth = self._depths.get(base_url, 0) + 1
        if depth > self.max_depth:
            return self._reject(canonical, "depth_limit", "depth " + str(depth))

        self._seen.add(canonical)
        self._fold_counts[fold_key] = count + 1
        self._depths[canonical] = depth
        self._queue.append((canonical, depth))
        self.counters["enqueued"] += 1
        return Disposition(canonical, "enqueued", "")

    # ---- queue ------------------------------------------------------------

    def drain(self, limit=None):
        """Pop queued pages honouring the deadline and the max-pages budget.
        ``limit`` (when given) caps this call's pop count — the loop passes
        the remaining whole-run budget so max_pages is cumulative, not
        per-batch. Stop reasons: "empty" (finished), "deadline" (partial,
        honest), "max_pages" (budget reached)."""
        budget = self.max_pages if limit is None else min(limit, self.max_pages)
        pages = []
        while self._queue:
            if self._clock() - self._start > self.deadline_seconds:
                self.counters["deadline"] += 1
                return pages, "deadline"
            if len(pages) >= budget:
                return pages, "max_pages"
            pages.append(self._queue.popleft())
        return pages, "empty"

    @property
    def pending(self):
        """True while queued pages remain — the loop's continuation test."""
        return bool(self._queue)

    # ---- internals ----------------------------------------------------------

    def _reject(self, url, action, reason):
        self.counters[action] += 1
        return Disposition(url, action, reason)
