"""Site-survey link frontier spectra (WU batch deep-drill engine).

The fold rows come from the SAME corpus file the Java contract test reads
(core/src/test/resources/webelement/link_fold_corpus.json): the Java
ContractMatchKeyRules is the folding single source, and this executor-side
naive implementation must fold every row identically or the suite goes red.

Coverage: corpus parity, segment-aligned scope (never /pr/ swallows /print/),
normalization order (relative resolve, #top dropped but #/ SPA route kept,
ignore_params stripped, trailing slash stripped), sibling-segment accounting,
cross-origin reject-only-count, verb guard + Ant-style exclude globs,
per-template cap against pagination explosion, and budget clamps
(max pages / max depth / deadline = partial stop, never a fake finish).
"""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from driver import link_frontier  # noqa: E402

CORPUS = (pathlib.Path(__file__).resolve().parents[2]
          / "core" / "src" / "test" / "resources" / "webelement" / "link_fold_corpus.json")


def _rows():
    return json.loads(CORPUS.read_text(encoding="utf-8"))["rows"]


def test_fold_corpus_parity_with_java_single_source():
    rows = _rows()
    assert len(rows) >= 12
    for row in rows:
        assert link_frontier.fold_template_path(row["path"]) == row["folded"], row["path"]


def test_scope_is_segment_aligned_never_swallows_sibling_words():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    assert f.scope_prefix == "/pr"
    assert f.in_scope("/pr") and f.in_scope("/pr/") and f.in_scope("/pr/order/1")
    assert not f.in_scope("/print/a")
    assert not f.in_scope("/md/x")
    root = link_frontier.LinkFrontier("https://xx.com")
    assert root.scope_prefix == "/"
    assert root.in_scope("/anything") and root.in_scope("/")


def test_normalize_relative_resolve_and_fragment_rules():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    d = f.consider("https://xx.com/pr/list/", "order/123?page=2")
    assert d.action == "enqueued"
    assert d.url == "https://xx.com/pr/list/order/123"
    top = f.consider("https://xx.com/pr/list/", "#top-anchor")
    assert top.action == "enqueued"  # /pr/list itself, fragment dropped
    assert top.url == "https://xx.com/pr/list"
    assert f.consider("https://xx.com/pr/list/", "#another-anchor").action == "dup"
    spa = f.consider("https://xx.com/pr/", "#/orders/12")
    assert spa.action == "enqueued"
    assert spa.url.endswith("/pr/#/orders/12") or spa.url.endswith("/pr/#/orders/{var}")
    # SPA routes are distinct pages: #/a and #/b enqueue, #/a twice is a dup
    assert f.consider("https://xx.com/pr/", "#/orders/12").action == "dup"
    assert f.consider("https://xx.com/pr/", "#/orders/13").action == "enqueued"


def test_ignore_params_stripped_others_kept():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    d = f.consider("https://xx.com/pr/list", "/pr/list?page=2&size=50&tab=all&_t=1730000000")
    assert d.action == "enqueued"
    assert d.url == "https://xx.com/pr/list?tab=all"


def test_sibling_segment_counted_not_visited():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    d = f.consider("https://xx.com/pr/home", "https://xx.com/md/doc/1")
    assert d.action == "sibling"
    assert f.sibling_counts == {"md": 1}
    assert f.counters["out_of_origin"] == 0


def test_cross_origin_counts_zero_visits():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    d = f.consider("https://xx.com/pr/home", "https://other.com/pr/x")
    assert d.action == "out_of_origin"
    assert f.counters["out_of_origin"] == 1


def test_guard_verbs_block_destructive_links():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    assert f.consider("https://xx.com/pr/home", "/pr/order/delete").action == "verb_guard"
    assert f.consider("https://xx.com/pr/home", "/pr/home", "退出登录").action == "verb_guard"
    assert f.consider("https://xx.com/pr/home", "/pr/export/x").action == "verb_guard"
    assert f.counters["verb_guard"] == 3
    off = link_frontier.LinkFrontier("https://xx.com/pr/", verbs_enabled=False)
    assert off.consider("https://xx.com/pr/home", "/pr/order/delete").action == "enqueued"


def test_exclude_globs_are_ant_style():
    f = link_frontier.LinkFrontier("https://xx.com/pr/", verbs_enabled=False,
                                   exclude_globs=["/pr/logout*", "/pr/*/export"])
    assert f.consider("https://xx.com/pr/home", "/pr/logout-all").action == "excluded"
    assert f.consider("https://xx.com/pr/home", "/pr/a/export").action == "excluded"
    assert f.consider("https://xx.com/pr/home", "/pr/a/export/x").action == "enqueued"
    assert f.counters["excluded"] == 2


def test_per_template_cap_stops_pagination_explosion():
    f = link_frontier.LinkFrontier("https://xx.com/pr/", per_template_cap=1)
    a = f.consider("https://xx.com/pr/home", "/pr/order/1")
    b = f.consider("https://xx.com/pr/home", "/pr/order/2")
    c = f.consider("https://xx.com/pr/home", "/pr/order/3")
    assert [a.action, b.action, c.action] == ["enqueued", "cap", "cap"]
    assert f.counters["cap"] == 2


def test_max_links_per_page_budget():
    f = link_frontier.LinkFrontier("https://xx.com/pr/", max_links_per_page=2,
                                   per_template_cap=10)
    f.begin_page("https://xx.com/pr/home")
    acts = [f.consider("https://xx.com/pr/home", f"/pr/p/{i}").action for i in range(3)]
    assert acts[:2] == ["enqueued", "enqueued"]
    assert acts[2] == "over_budget"


def test_max_pages_and_depth_clamps():
    f = link_frontier.LinkFrontier("https://xx.com/pr/", max_pages=2, per_template_cap=10)
    for i in range(5):
        f.consider("https://xx.com/pr/home", f"/pr/a/{i}")
    pages, stop = f.drain()
    assert stop == "max_pages"
    assert len(pages) == 2

    deep = link_frontier.LinkFrontier("https://xx.com/pr/", max_depth=2)
    assert deep.consider("https://xx.com/pr/home", "https://xx.com/pr/a").action == "enqueued"
    assert deep.consider("https://xx.com/pr/a", "https://xx.com/pr/a/b").action == "enqueued"
    assert deep.consider("https://xx.com/pr/a/b", "https://xx.com/pr/a/b/c").action == "depth_limit"


def test_deadline_turns_drain_partial_not_fake_finish():
    ticks = iter([0.0, 0.0, 5000.0])
    f = link_frontier.LinkFrontier("https://xx.com/pr/", deadline_seconds=1800,
                                   per_template_cap=10, clock=lambda: next(ticks))
    for i in range(4):
        f.consider("https://xx.com/pr/home", f"/pr/a/{i}")
    pages, stop = f.drain()
    assert stop == "deadline"
    assert len(pages) == 1


def test_non_http_schemes_never_enqueued():
    f = link_frontier.LinkFrontier("https://xx.com/pr/")
    assert f.consider("https://xx.com/pr/home", "javascript:void(0)").action == "non_http"
    assert f.consider("https://xx.com/pr/home", "mailto:me@xx.com").action == "non_http"
    assert f.counters["non_http"] == 2
