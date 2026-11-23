# -*- coding: utf-8 -*-
"""Production wiring of the site-survey executor consumer .

Red line checks pinned here (no mocks anywhere — real Playwright against a
real in-process HTTP fixture site):
 * the dispatch envelope carries the frozen siteSurvey snapshot; the
 consumer navigates the BFS loop through the REAL WebDriverExecutor
 adapter (one browser, one page, GET navigation only);
 * every visited page emits exactly one ``site-survey-page`` timeline entry
 whose detail JSON matches the core relay wire contract
 (url/status/reason/counters + candidatePool payload of that page) —
 the kernel writes those frames back through the single recordStep door;
 * counters ride a cumulative snapshot (prefix tallies on every frame,
 full frontier counters on the last), so the run ledger never sees a
 regressed tally from an out-of-order replay;
 * the crawl never submits: the fixture site POSTs nothing back, every
 served request is a GET (read-only red line);
 * the production blacklist and a malformed envelope fail honestly without
 launching a browser.
"""
from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from driver.site_survey_loop import GUARD_REJECT_TEXT
from site_survey_worker import run_site_survey_task

# Two negative links live on the home page: a destructive-verb link and a
# foreign-origin link. Both must be counted and never followed — they feed the
# ledger columns tp_site_survey_run.excluded / tp_site_survey_run.out_of_scope.
_PAGES = {
    "/": ("""<html><body><h1>home</h1>
        <a href="/pr/a.html">A</a><a href="/pr/b.html">B</a>
        <a href="/pr/logout.html">退出登录</a>
        <a href="http://example.invalid/report">external report</a>
        <button id="go">go</button></body></html>"""),
    "/pr/a.html": """<html><body><h1>A</h1>
        <a href="/">home</a>
        <button id="save-a">save</button>
        <form method="post" action="/submit"><input name="q"/></form>
        </body></html>""",
    "/pr/b.html": """<html><body><h1>B</h1>
        <a href="/">home</a>
        <button id="save-b">save</button></body></html>""",
    # Reached only through the 301 below: the requested address loses its
    # trailing slash on the way in, the final page URL keeps it — the pool
    # relay must follow the final URL, not the queue entry.
    "/pr/": """<html><body><h1>PR index</h1>
        <a href="/pr/a.html">A</a>
        <button id="save-pr">save</button></body></html>""",
}


class _Handler(BaseHTTPRequestHandler):
    hits: list[tuple[str, str]] = []  # (method, path) seen by the server

    def do_GET(self):  # noqa: N802 — http.server naming convention
        path = self.path.split("?")[0]
        _Handler.hits.append(("GET", path))
        if path == "/pr":
            # Trailing-slash normalization redirect: the crawler reaches the
            # index through the slash-less address only (fixture of the real
            # server behaviour observed in the journey window).
            self.send_response(301)
            self.send_header("Location", "/pr/")
            self.end_headers()
            return
        body = _PAGES.get(path)
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body.encode("utf-8"))

    def do_POST(self):  # noqa: N802 — must never be reached by the crawler
        _Handler.hits.append(("POST", self.path))
        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):  # silence the per-request stderr noise
        return


@pytest.fixture(scope="module")
def site():
    _Handler.hits = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    yield base
    srv.shutdown()


def _envelope(start_url: str, **rule_overrides) -> str:
    rules = {"startUrl": start_url, "maxPages": 10, "maxDepth": 4,
             "intervalMs": 0, "deadlineSeconds": 120,
             "maxLinksPerPage": 20, "perTemplateCap": 1,
             "excludeGlobs": [], "ignoreParams": [],
             "verbsGuardEnabled": True, "guardVerbs": ["logout"],
             "allowProd": True, "denyHosts": []}
    rules.update(rule_overrides)
    return json.dumps({"kind": "explore-act", "action": "site-survey",
                       "url": start_url, "siteSurvey": rules},
                      ensure_ascii=False)


def _frames(outcome) -> list[dict]:
    return [s for s in outcome.steps_timeline
            if s.get("action") == "site-survey-page"]


def test_happy_run_emits_relay_contract_frames(site):
    outcome = run_site_survey_task(_envelope(site + "/"), "/tmp", None)
    assert outcome.result == "pass", outcome.error_detail
    frames = _frames(outcome)
    assert [f["stepNo"] for f in frames] == list(range(1, len(frames) + 1))
    visited = {json.loads(f["detail"])["url"] for f in frames}
    assert visited == {site + "/", site + "/pr/a.html", site + "/pr/b.html"}
    last = json.loads(frames[-1]["detail"])
    assert last["counters"]["pages_total"] == 3
    assert last["counters"]["pages_ok"] == 3
    assert last["counters"].get("links_seen") is not None
    # prefix frames carry a cumulative, never-regressing pages tally
    tallies = [json.loads(f["detail"])["counters"]["pages_total"]
               for f in frames]
    assert tallies == sorted(tallies)
    # the page pools reach the relay in the serialized candidate payload
    # shape the single-page door already feeds ({"candidates": [...], "count"})
    a = next(f for f in frames
             if json.loads(f["detail"])["url"] == site + "/pr/a.html")
    pool = json.loads(json.loads(a["detail"])["candidatePool"])
    # candidate rows keep the element_candidates wire shape (tag/text/target)
    texts = [str(c.get("text") or "") for c in pool["candidates"]]
    assert pool["candidates"] and any("save" in x for x in texts)


def test_redirect_normalized_page_relays_pool_at_final_url(site):
    outcome = run_site_survey_task(_envelope(site + "/pr"), "/tmp", None)
    assert outcome.result == "pass", outcome.error_detail
    frames = _frames(outcome)
    entry = next(f for f in frames
                 if json.loads(f["detail"])["url"] == site + "/pr")
    detail = json.loads(entry["detail"])
    assert detail["status"] == "ok"
    # The pool was collected on the 301-final URL; the frame must relay it
    # anyway (keying by the queue address alone used to drop it silently).
    assert "candidatePool" in detail, "normalized page lost its pool relay"
    pool = json.loads(detail["candidatePool"])
    assert pool["candidates"] and any(
        "save" in str(c.get("text") or "") for c in pool["candidates"])


def test_crawl_is_read_only_get_navigation(site):
    outcome = run_site_survey_task(_envelope(site + "/"), "/tmp", None)
    assert outcome.result == "pass", outcome.error_detail
    methods = {m for m, _ in _Handler.hits}
    assert methods == {"GET"}  # never click, never submit (read-only red line)


def test_production_blacklist_refuses_before_browser(site):
    outcome = run_site_survey_task(
        _envelope(site + "/", denyHosts=["127.0.0.1"], allowProd=False),
        "/tmp", None)
    assert outcome.result == "fail"
    assert "生产域名" in outcome.error_detail
    assert "element.survey-site.allow-prod" in outcome.error_detail
    assert not GUARD_REJECT_TEXT.startswith("{")


def test_missing_rules_snapshot_fails_honestly(site):
    bad = json.dumps({"kind": "explore-act", "action": "site-survey",
                      "url": site + "/"})
    outcome = run_site_survey_task(bad, "/tmp", None)
    assert outcome.result == "fail"
    assert "siteSurvey" in outcome.error_detail  # names the missing key


def test_envelope_shape_guard_rejects_other_kinds(site):
    from explore_worker import _parse_envelope  # single parse point stays
    with pytest.raises(ValueError):
        _parse_envelope(json.dumps({"kind": "other"}))


def test_last_frame_carries_ledger_column_vocabulary(site):
    """The run ledger reads tp_site_survey_run columns out_of_scope / excluded.

    The crawl keeps its own internal tally names (out_of_origin, verb_guard)
    inside the frontier snapshot; the terminal frame is the boundary where the
    crawl vocabulary is translated into the ledger column vocabulary. A drift here
    silently zeroes those two columns, because a missing key is treated as
    "no fresh value" and the established tally is kept. Pinned here so the L3
    window's cross-origin and verb-guard tallies can never regress again.
    """
    outcome = run_site_survey_task(_envelope(site + "/"), "/tmp", None)
    assert outcome.result == "pass", outcome.error_detail
    frames = _frames(outcome)
    counters = json.loads(frames[-1]["detail"])["counters"]
    # ledger column vocabulary present and non-zero
    assert counters.get("out_of_scope") == 1, counters
    assert counters.get("excluded") == 1, counters
    # both negative links were counted, never followed
    visited = {json.loads(f["detail"])["url"] for f in frames}
    assert visited == {site + "/", site + "/pr/a.html", site + "/pr/b.html"}
    # the nested raw snapshot keeps the crawl-internal words untouched
    raw = counters["counters"]
    assert raw["out_of_origin"] == 1 and raw["verb_guard"] == 1
