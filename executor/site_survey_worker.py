# -*- coding: utf-8 -*-
"""Whole-site survey executor consumer .

The dispatch door (core SiteSurveyDispatch) freezes the site rules into the
task envelope; this module is the executor-side consumer: it wraps the REAL
WebDriverExecutor into the browser protocol that driver.site_survey_loop
expects (goto / wait_network_idle / link_anchors / has_password_input /
collect_candidates), runs one deterministic BFS deep-crawl (zero LLM — same
read-only red line as the single-page survey: navigation only, never click,
never submit), then returns one journal frame per visited page as a
``site-survey-page`` step-timeline entry. The kernel writes those frames
back through the single explore step door (core SiteSurveyRunLedger is the
relay authority for the wire shape).

Envelope contract (03 protocol 2.3 explore branch, kind stays "explore-act"):
 {"kind": "explore-act", "action": "site-survey", "url": <start>,
 "siteSurvey": {startUrl/excludeGlobs/ignoreParams/guardVerbs/verbsEnabled
 maxPages/maxDepth/intervalMs/deadlineSeconds/
 maxLinksPerPage/perTemplateCap/allowProd/denyHosts/
 viewportClasses?/networkIdleMs?}}
The snapshot is dispatch-time truth (adjudication: the executor never
re-queries the profile). A malformed envelope or the production blacklist
fail honestly with the key and the settings path named (error guidance);
no browser launches in either case.
"""
from __future__ import annotations

import json
import time

from driver.element_candidates import collect_element_candidates
from driver.explore_network_guard import ExploreNetworkGuard
from driver.net_observer import NetObserver
from driver.site_survey_loop import SiteSurveyGuardError, run_site_survey
from driver.webdriver import RunOutcome, WebDriverExecutor

# Wire constants shared with the core relay (keep literal-synced with
# SiteSurveyRunLedger.SITE_PAGE_FRAME_ACTION and the goal-prefix door).
SITE_SURVEY_ACTION = "site-survey"
PAGE_FRAME_ACTION = "site-survey-page"

# Politeness/observability budgets of the crawl browser (site snapshot may
# override the idle budget; the assert budget stays explore-side small).
DEFAULT_NETWORK_IDLE_MS = 5000
SITE_SURVEY_SCRIPT_LIMITS = {"screenshot-mode": 1, "assert-timeout-sec": 5}

# Anchor-free link harvest: BFS needs only (href, text) pairs, no targeting.
_JS_LINKS = """() => Array.from(document.querySelectorAll('a[href]'))
    .map(a => ({href: a.getAttribute('href'),
                text: (a.textContent || '').trim().slice(0, 80)}))"""


class PlaywrightSiteBrowser:
    """Real-WebDriverExecutor adapter implementing the loop's browser protocol.

    One persistent page across the whole run (cookies/origin stay natural);
    every candidate pool collected on a page is kept in ``pools`` keyed by
    the URL that was navigated, serialized through the SAME
    collect_element_candidates single source the single-page survey feeds
    into the step relay — a second candidate assembly is forbidden (R40).
    """

    def __init__(self, page, viewport_classes=(), network_idle_ms: int =
                 DEFAULT_NETWORK_IDLE_MS):
        self.page = page
        self.viewport_classes = tuple(viewport_classes or ())
        self.network_idle_ms = max(500, int(network_idle_ms))
        self.pools: dict[str, str] = {}
        # Write-guard mount: the passive response feed is the single collector
        # (no second CDP listener, R40); the page window follows each goto and
        # closes at network idle. Subscriptions die with the page on stop().
        self.guard = ExploreNetworkGuard(page, NetObserver(page))

    def goto(self, url: str) -> str:
        self.guard.begin_page(url)
        self.page.goto(url, wait_until="domcontentloaded")
        return self.page.url

    def wait_network_idle(self) -> None:
        # Soft budget: a busy page past the idle window still gets surveyed
        # (the loop records the page it reached, never crashes on a waiter).
        try:
            self.page.wait_for_load_state("networkidle",
                                          timeout=self.network_idle_ms)
        except Exception:  # noqa: BLE001 — idle timeout is expected on chatty sites
            pass
        self.guard.collect()  # flush late arrivals into this page's window

    def network_account(self) -> dict:
        """Per-page write/failure window consumed by the survey loop (frames,
        risk-ledger rows and the interface health card all read this shape)."""
        return self.guard.page_account()

    def link_anchors(self):
        raw = self.page.evaluate(_JS_LINKS)
        return [(str(r.get("href") or ""), str(r.get("text") or ""))
                for r in raw if isinstance(r, dict) and r.get("href")]

    def has_password_input(self) -> bool:
        return bool(self.page.evaluate(
            "() => !!document.querySelector('input[type=password]')"))

    def collect_candidates(self) -> list[dict]:
        text = collect_element_candidates(self.page, self.viewport_classes)
        self.pools[self.page.url] = text
        try:
            payload = json.loads(text)
        except ValueError:
            return []
        return list(payload.get("candidates") or [])


def _parse_site_envelope(action_json: str) -> dict:
    """Envelope shape guard (fail-closed, messages name the missing key)."""
    try:
        envelope = json.loads(action_json or "")
    except Exception as e:  # noqa: BLE001 — any parse crash is human-facing
        raise ValueError(f"任务信封不是合法 JSON: {e}") from e
    if not isinstance(envelope, dict):
        raise TypeError("任务信封必须是 JSON 对象")
    if envelope.get("action") != SITE_SURVEY_ACTION:
        raise ValueError(f"action 期望 {SITE_SURVEY_ACTION!r}，实际 "
                         f"{envelope.get('action')!r}")
    url = str(envelope.get("url") or "")
    if not url.startswith(("http://", "https://")):
        raise ValueError(f"信封 url 非 http(s) 绝对址: {url!r}")
    rules = envelope.get("siteSurvey")
    if not isinstance(rules, dict) or not rules:
        raise ValueError("信封 siteSurvey 规则快照缺失（整站测绘规则在发起时刻由"
                         "服务端冻结进信封，执行器不回查站点档案；请重新发起整站测绘）")
    rules.setdefault("startUrl", url)
    return envelope


def _prefix_counters(journal: list[dict], upto: int) -> dict:
    """Cumulative page tallies of the journal prefix (never regressing).

    Frames excluded from navigation (production blacklist) are counted in
    ``excluded_prod`` only; attempts/ok/failed follow the loop's own
    semantics so a replayed frame can never lower an established tally.
    """
    attempted = ok = failed = failed_login = redirect_out = prod_denied = 0
    for frame in journal[:upto]:
        status = frame.get("status")
        if status == "excluded_prod":
            prod_denied += 1
            continue
        attempted += 1
        if status == "ok":
            ok += 1
        elif status == "redirect_out":
            failed += 1
            redirect_out += 1
        elif status == "failed_login":
            failed += 1
            failed_login += 1
        else:
            failed += 1
    return {"pages_total": attempted, "pages_ok": ok, "pages_failed": failed,
            "failed_login": failed_login, "redirect_out": redirect_out,
            "prod_denied": prod_denied}


def _page_frame_detail(frame: dict, counters: dict, pool) -> dict:
    """One page frame -> the step-door detail json the kernel relays onward.

    The network window rides only when it carries something (light-payload
    discipline): write rows feed the page risk ledger, failures feed the
    interface health card, and ``snapshotOnly`` is the conservative probe
    mode the kernel must honour for that page.
    """
    detail: dict = {"url": frame["url"], "status": frame["status"],
                    "reason": frame.get("note") or "",
                    "counters": counters}
    if frame.get("writeEvents"):
        detail["writeEvents"] = frame["writeEvents"]
    if frame.get("writeSurface"):
        detail["writeSurface"] = frame["writeSurface"]
    if frame.get("netFailures"):
        detail["netFailures"] = frame["netFailures"]
    if frame.get("snapshotOnly"):
        detail["snapshotOnly"] = True
    if pool:
        detail["candidatePool"] = pool
    return detail


def _ledger_columns(counters: dict) -> dict:
    """Translate crawl-internal tallies into the run-ledger column names.

    The crawl names its rejections ``out_of_origin`` (cross-origin, outside the
    scope prefix or redirected away) and ``verb_guard`` (destructive-verb link),
    while the run row stores them as ``out_of_scope`` and ``excluded``. This
    frame boundary is the only place the two vocabularies meet, so the mapping
    lives here and nowhere else: a missing key on the wire is read as "no fresh
    value" by the ledger and would silently keep the column at zero.
    """
    return {"out_of_scope": int(counters.get("out_of_origin") or 0),
            "excluded": int(counters.get("excluded") or 0)
            + int(counters.get("verb_guard") or 0)}


def _outcome(result: str, duration_ms: int, *, detail=None,
             timeline=None, error_type: int = 3) -> RunOutcome:
    return RunOutcome(result=result, error_type=None if result == "pass"
                      else error_type, error_detail=detail,
                      steps_timeline=timeline or [], duration_ms=duration_ms)


def run_site_survey_task(action_json: str, screenshots_dir: str,
                         session_state: dict | None = None) -> RunOutcome:
    """Run one whole-site deep-crawl from the envelope; never raises.

    Terminal accounting (honest, no half-green): completed/partial pass with
    the stop reason recorded; failed runs and the production blacklist return
    ``fail`` with a message naming the hit value and the actionable key.
    """
    started = time.monotonic()

    def _elapsed() -> int:
        return int((time.monotonic() - started) * 1000)

    try:
        envelope = _parse_site_envelope(action_json)
    except (ValueError, TypeError) as e:
        return _outcome("fail", _elapsed(), detail=f"整站测绘信封非法: {e}")
    rules = envelope["siteSurvey"]

    executor: WebDriverExecutor | None = None
    try:
        executor = WebDriverExecutor(env_base_url=None, headless=True,
                                     screenshots_dir=screenshots_dir,
                                     session_state=session_state,
                                     script_limits=dict(SITE_SURVEY_SCRIPT_LIMITS))
        executor.start()
        browser = PlaywrightSiteBrowser(
            executor.open_page(),
            viewport_classes=rules.get("viewportClasses") or (),
            network_idle_ms=int(rules.get("networkIdleMs",
                                         DEFAULT_NETWORK_IDLE_MS)))
        account = run_site_survey(rules, browser)
    except SiteSurveyGuardError as refused:
        return _outcome("fail", _elapsed(), detail=str(refused))
    except Exception as e:  # noqa: BLE001 — crash becomes the recorded result
        return _outcome("fail", _elapsed(),
                        detail=f"整站测绘执行异常: {e}")
    finally:
        if executor is not None:
            try:
                executor.stop()
            except Exception:  # noqa: BLE001 — shutdown noise never masks results
                pass

    journal = account["journal"]
    timeline: list[dict] = []
    for index, frame in enumerate(journal, start=1):
        counters = _prefix_counters(journal, index)
        if index == len(journal):
            # the last frame rides the loop's authoritative full snapshot
            # (frontier counters + links seen included; later frames win)
            counters = {**counters,
                        **_ledger_columns(account["counters"]),
                        "links_seen": account["links_seen"],
                        "out_of_origin": account["out_of_origin"],
                        "sibling_counts": account["sibling_counts"],
                        "counters": account["counters"],
                        "status": account["status"],
                        "stop_reason": account["stop_reason"]}
        detail = _page_frame_detail(frame, counters, browser.pools.get(
            frame.get("finalUrl") or frame["url"]))
        timeline.append({"stepNo": index, "action": PAGE_FRAME_ACTION,
                         "status": "ok" if frame["status"] == "ok" else "warn",
                         "detail": json.dumps(detail, ensure_ascii=False)})

    if account["status"] == "failed":
        first_failure = next((f for f in journal if f["status"] != "ok"), {})
        return _outcome("fail", _elapsed(),
                        detail=(f"整站测绘失败：{account['pages_failed']} 页异常 / "
                                f"{account['pages_total']} 页尝试"
                                f"（首个异常页 {first_failure.get('url', '')} "
                                f"{first_failure.get('note', '')}）"),
                        timeline=timeline)
    return _outcome("pass", _elapsed(), timeline=timeline)
