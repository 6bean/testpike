# -*- coding: utf-8 -*-

from __future__ import annotations

import pytest

from driver.link_frontier import DEFAULT_IGNORE_PARAMS
from driver.site_survey_loop import SiteSurveyGuardError, run_site_survey


class FakePage:
    def __init__(self, hrefs=(), password=False, candidates=None,
                 final=None, raise_on_goto=False):
        self.hrefs = list(hrefs)          # (href, anchor_text)
        self.password = password
        self.candidates = candidates if candidates is not None else [
            {"role": "button", "name": "act"}]
        self.final = final
        self.raise_on_goto = raise_on_goto


class FakeBrowser:
    """In-memory fixture site: url -> FakePage."""

    def __init__(self, pages):
        self.pages = pages
        self.visited = []
        self.idle_waits = 0

    def goto(self, url):
        page = self.pages.get(url)
        if page is None:
            raise AssertionError("fixture page missing: " + url)
        self.visited.append(url)
        if page.raise_on_goto:
            raise RuntimeError("navigation timeout")
        return page.final or url

    def wait_network_idle(self):
        self.idle_waits += 1

    def link_anchors(self):
        return list(self.pages[self.visited[-1]].hrefs)

    def has_password_input(self):
        return self.pages[self.visited[-1]].password

    def collect_candidates(self):
        return list(self.pages[self.visited[-1]].candidates)


BASE = "http://t.example/pr"


def _rules(start, **over):
    # The start URL ships with the trailing slash the user typed; the frontier
    # canonicalizes it away, so fixture pages are keyed by the canonical form.
    base = {"startUrl": start.rstrip("/") + "/", "excludeGlobs": (),
            "ignoreParams": DEFAULT_IGNORE_PARAMS,
            "verbsGuardEnabled": True, "maxPages": 100, "maxDepth": 6,
            "maxLinksPerPage": 60, "perTemplateCap": 1, "intervalMs": 800,
            "deadlineSeconds": 1800, "allowProd": False, "denyHosts": ()}
    base.update(over)
    return base


def _run(pages, rules=None, sleep=None, clock=None):
    browser = FakeBrowser(pages)
    account = run_site_survey(rules or _rules(BASE), browser,
                              sleep=sleep or (lambda s: None),
                              clock=clock or (lambda: 0.0))
    return account, browser


def test_bfs_order_and_journal():
    pages = {
        BASE: FakePage([("/pr/home", "Home"), ("/pr/list?page=1", "List")]),
        "http://t.example/pr/home": FakePage([("/pr/deep", "Deep")]),
        "http://t.example/pr/list": FakePage(),
        "http://t.example/pr/deep": FakePage(),
    }
    account, browser = _run(pages)
    assert browser.visited[0] == BASE
    assert account["status"] == "completed"
    assert account["pages_total"] == 4 and account["pages_ok"] == 4
    assert account["pages_failed"] == 0
    assert [f["status"] for f in account["journal"]] == ["ok"] * 4
    assert account["candidate_rows"], "collected rows carry pageKey"
    assert all("pageKey" in r for r in account["candidate_rows"])


def test_sibling_segment_counted_not_visited():
    pages = {
        BASE: FakePage([("/md/index", "Docs"), ("/pr/home", "Home")]),
        "http://t.example/pr/home": FakePage(),
    }
    account, browser = _run(pages)
    assert "http://t.example/md/index" not in browser.visited
    assert account["sibling_counts"] == {"md": 1}


def test_cross_origin_never_visited():
    pages = {BASE: FakePage([("https://other.example/x", "Ext"),
                             ("http://t.example/pr/a", "A")]),
             "http://t.example/pr/a": FakePage()}
    account, browser = _run(pages)
    assert not any("other.example" in u for u in browser.visited)
    assert account["out_of_origin"] >= 1


def test_verb_guard_blocks_logout():
    pages = {BASE: FakePage([("/pr/logout", "退出"), ("/pr/a", "A")]),
             "http://t.example/pr/a": FakePage()}
    account, browser = _run(pages)
    assert "http://t.example/pr/logout" not in browser.visited
    assert account["counters"]["verb_guard"] == 1


def test_pagination_collapse_single_visit():
    pages = {BASE: FakePage([("/pr/list?page=1", "L1"), ("/pr/list?page=2", "L2"),
                             ("/pr/list?page=3", "L3")]),
             "http://t.example/pr/list": FakePage()}
    account, browser = _run(pages)
    assert browser.visited.count("http://t.example/pr/list") == 1
    assert account["pages_total"] == 2


def test_login_form_zero_candidates_not_ledgered():
    pages = {
        BASE: FakePage([("/pr/secret", "S"), ("/pr/home", "H")]),
        "http://t.example/pr/secret": FakePage(password=True, candidates=[]),
        "http://t.example/pr/home": FakePage(),
    }
    account, browser = _run(pages)
    frame = [f for f in account["journal"] if f["url"].endswith("/pr/secret")][0]
    assert frame["status"] == "failed_login"
    rows_keys = {r["pageKey"] for r in account["candidate_rows"]}
    assert not any("secret" in k for k in rows_keys)
    assert account["failed_login"] == 1
    assert account["status"] == "completed"  # 1 of 3 failed: below half


def test_failed_majority_marks_run_failed():
    pages = {
        BASE: FakePage([("/pr/a", "A"), ("/pr/b", "B")]),
        "http://t.example/pr/a": FakePage(password=True, candidates=[]),
        "http://t.example/pr/b": FakePage(final="http://t.example/login"),
    }
    account, _browser = _run(pages)
    assert account["status"] == "failed"
    assert account["pages_failed"] >= 2


def test_redirect_within_site_counts_failed_login():
    pages = {BASE: FakePage(final="http://t.example/login")}
    account, _browser = _run(pages)
    assert account["journal"][0]["status"] == "failed_login"


def test_redirect_out_of_origin_recorded():
    pages = {BASE: FakePage(final="https://external.example/gate")}
    account, _browser = _run(pages)
    assert account["journal"][0]["status"] == "redirect_out"
    assert account["out_of_origin"] >= 1


def test_goto_error_is_failed_page_not_crash():
    pages = {BASE: FakePage([("/pr/a", "A")]),
             "http://t.example/pr/a": FakePage(raise_on_goto=True)}
    account, _browser = _run(pages)
    assert account["journal"][1]["status"] == "error"
    assert account["pages_failed"] == 1


def test_interval_sleep_between_pages():
    calls = []
    pages = {BASE: FakePage([("/pr/a", "A")]), "http://t.example/pr/a": FakePage()}
    _account, _browser = _run(pages, sleep=lambda s: calls.append(s))
    assert calls == [0.8]  # one gap between two attempted pages


def test_deadline_partial_not_fake_complete():
    tick = [0.0]

    def clock():
        tick[0] += 4.0
        return tick[0]

    pages = {BASE: FakePage([("/pr/a", "A")]), "http://t.example/pr/a": FakePage()}
    account, _browser = _run(pages, rules=_rules(BASE, deadlineSeconds=5),
                             clock=clock)
    assert account["status"] == "partial"
    assert account["stop_reason"] == "deadline"


def test_max_pages_budget_partial():
    # Non-numeric segment names: "/pr/0"-style paths would template-fold into
    # one page and be stopped by the cap before the budget matters.
    hrefs = [("/pr/pg%d" % i, "P") for i in range(5)]
    pages = {BASE: FakePage(hrefs)}
    pages.update({"http://t.example/pr/pg%d" % i: FakePage() for i in range(5)})
    account, browser = _run(pages, rules=_rules(BASE, maxPages=2))
    assert account["status"] == "partial"
    assert account["stop_reason"] == "max_pages"
    assert len(browser.visited) == 2


def test_prod_guard_rejects_blacklisted_start():
    rules = _rules("https://shop.prod.example/pr/",
                   denyHosts=("*.prod.example",))
    with pytest.raises(SiteSurveyGuardError) as exc:
        run_site_survey(rules, FakeBrowser({}))
    text = str(exc.value)
    assert "shop.prod.example" in text          # names the hit domain
    assert "element.survey-site.allow-prod" in text  # names the switch key


def test_prod_guard_allows_when_explicitly_opened():
    rules = _rules(BASE, denyHosts=("*.prod.example",), allowProd=True)
    account, _browser = _run({BASE: FakePage()}, rules=rules)
    assert account["status"] == "completed"


if __name__ == "__main__":
    import sys
    sys.exit(pytest.main([__file__, "-q"]))
