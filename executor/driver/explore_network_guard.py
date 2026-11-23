"""Write-side guard and network-failure attribution for explore browsers.

Closes the two blind spots named by the adjudicated design (whole-site
survey write-guard, pieces 1 and 2, executor detection layer):

  1. Indirect writes. The explore action whitelist never contains a submit
     verb, but an SPA input can autosave: a POST fired by page JS is
     invisible to the whitelist. This guard keeps a per-page natural request
     surface (everything seen while the page loads) and flags any write verb
     (POST/PUT/DELETE/PATCH) that appears after an action step and was NOT
     part of that baseline as a ``write_observed`` event attributed to the
     step. Downstream consumers use the events to degrade the page to
     snapshot-only probing and to upsert the page risk ledger.
  2. Network-level failures. A page can look fine while its query endpoint
     returns 500. Every response with status >= 400 becomes a
     (url, method, status) triple with error_type=1 (product defect: broken
     reference or failing endpoint — the same taxonomy as the console
     zero-tolerance line); every requestfailed becomes error_type=3
     (environment: connection reset / timeout / name resolution).

Collection reuses ``NetObserver`` (the existing passive response feed,
driver.net_observer) as its single source — no second CDP socket, no second
listener implementation (single-implementation rule). Only ``requestfailed``
is subscribed directly on the page. Recorded urls are redacted: query values
never leave the browser process, only parameter names are kept.

Observer exceptions must never disturb the main execution chain: handlers
only append plain dicts and read safe attributes.
"""
from __future__ import annotations

from typing import Any, Optional
from urllib.parse import urlsplit

# Write-type verbs (the ones that can change server state).
WRITE_METHODS = frozenset({"POST", "PUT", "DELETE", "PATCH"})

# Product defect vs environment split of the existing three-way taxonomy
# (error_type 1 = broken page reference or failing endpoint,
# 3 = connection reset / timeout family).
ERROR_TYPE_PRODUCT = 1
ERROR_TYPE_ENVIRONMENT = 3

# Account buckets and the session-level list that holds each of them. A
# site-survey run reuses one browser across many pages, so every recorded row
# is written to the session totals AND to the page window that was open when
# it arrived (per-page risk rows, never a cross-page bleed).
_SINK_ATTR = {"baselineSurface": "_surface", "writeSurface": "_write_surface",
              "writeEvents": "_write_events", "failures": "_failures"}


def redact_url(url: str) -> str:
    """Normalize a url for evidence: keep origin+path, drop query values.

    Parameter names stay (they tell the operator which endpoint and which
    fields are involved); values are replaced by nothing after the name.
    Fragments are dropped. The result is the display/evidence url; the
    baseline dedup key additionally drops the query entirely.
    """
    try:
        sp = urlsplit(url)
    except ValueError:
        return url
    if not sp.netloc:
        return url
    names = sorted({pair.split("=", 1)[0] for pair in sp.query.split("&") if pair})
    path = sp.path or "/"
    base = f"{sp.scheme}://{sp.netloc}{path}"
    return f"{base}?{'&'.join(names)}" if names else base


def _baseline_key(entry: dict) -> tuple:
    """Load-phase identity of a request: verb + path (query-insensitive)."""
    sp = urlsplit(entry["url"])
    path = sp.path or "/"
    return (str(entry["method"]).upper(), f"{sp.scheme}://{sp.netloc}{path}")


class ExploreNetworkGuard:
    """One survey/explore session: baseline -> actions -> write/failure events.

    ``observer`` is a NetObserver already attached to the page (its
    ``entries`` list is consumed cursor-style); ``page`` is only used to
    subscribe the ``requestfailed`` event the response feed does not carry.
    """

    def __init__(self, page: Any, observer: Any) -> None:
        self._observer = observer
        self._cursor = 0
        self._baselined = False
        self._action_started = False
        self._step_no = 0
        self._baseline_keys: set = set()
        self._surface: list = []
        self._write_surface: list = []
        self._write_events: list = []
        self._failures: list = []
        self._pages: list = []
        self._page: Optional[dict] = None
        page.on("requestfailed", self._on_request_failed)

    # ------------------------------------------------------------------ feed

    def begin_page(self, url: Optional[str] = None) -> None:
        """Open a per-page window (one browser, many survey navigations).

        Everything seen from now on belongs to this page's account; the
        session totals keep accumulating as before so single-action explore
        consumers are unaffected.
        """
        self._scan()  # late arrivals of the previous page stay on that page
        self._page = {"url": url, **{key: [] for key in _SINK_ATTR}}
        self._pages.append(self._page)

    def end_baseline(self) -> None:
        """Close the load phase: everything seen so far is natural surface."""
        self._scan()
        self._baselined = True

    def begin_action(self, step_no: int) -> None:
        """Attribute subsequent traffic to this action step number."""
        self._scan()  # late load-phase arrivals still belong to the baseline
        self._action_started = True
        self._step_no = step_no

    def collect(self) -> None:
        """Flush the pending tail of the observer feed into the account."""
        self._scan()

    def _scan(self) -> None:
        try:
            new = self._observer.entries[self._cursor:]
            self._cursor = len(self._observer.entries)
            for entry in new:
                self._classify(entry)
        except Exception:  # noqa: BLE001 — detection never breaks the run
            return

    def _emit(self, bucket: str, record: dict) -> None:
        """Record one row into its session bucket and the open page window."""
        getattr(self, _SINK_ATTR[bucket]).append(record)
        if self._page is not None:
            self._page[bucket].append(record)

    def _classify(self, entry: dict) -> None:
        method = str(entry.get("method") or "").upper()
        url = str(entry.get("url") or "")
        status = entry.get("status")
        if not self._action_started:
            record = {"url": redact_url(url), "method": method, "status": status}
            self._emit("baselineSurface", record)
            self._baseline_keys.add((method, _baseline_key(entry)[1]))
            # A write verb inside the load surface = the page saves by itself
            # (autosave / debounce), invisible to the action whitelist.
            if method in WRITE_METHODS:
                self._emit("writeSurface", record)
        elif method in WRITE_METHODS and _baseline_key(entry) not in self._baseline_keys:
            self._emit("writeEvents", {"stepNo": self._step_no, "method": method,
                                       "url": redact_url(url)})
        if isinstance(status, int) and status >= 400:
            self._emit("failures", {"url": redact_url(url), "method": method,
                                    "status": status,
                                    "errorType": ERROR_TYPE_PRODUCT,
                                    "failureText": None})

    def _on_request_failed(self, request: Any) -> None:
        try:
            failure = getattr(request, "failure", None)
            text = getattr(failure, "text", None) if failure is not None else None
            self._emit("failures", {
                "url": redact_url(str(getattr(request, "url", "") or "")),
                "method": str(getattr(request, "method", "") or "").upper(),
                "status": None,
                "errorType": ERROR_TYPE_ENVIRONMENT,
                "failureText": text,
            })
        except Exception:  # noqa: BLE001 — observation must not disturb the run
            return

    # ---------------------------------------------------------------- account

    def account(self) -> dict:
        """Machine-readable session account (consumers: risk-ledger upsert,
        step-timeline evidence, stats health card — packs 2/3 of the design)."""
        self._scan()
        return {
            "baselineSurface": list(self._surface),
            "writeSurface": list(self._write_surface),
            "writeEvents": list(self._write_events),
            "failures": list(self._failures),
        }

    def page_account(self) -> dict:
        """Account of the page window that is currently open (empty-shaped
        dict before any ``begin_page``, so consumers never special-case)."""
        self._scan()
        if self._page is None:
            return {"url": None, **{key: [] for key in _SINK_ATTR}}
        return {key: list(value) if isinstance(value, list) else value
                for key, value in self._page.items()}

    def has_writes(self) -> bool:
        """Conservative switch: any write observed after actions means the
        page must be downgraded to snapshot-only probing."""
        return bool(self._write_events)

    def has_write_risk(self) -> bool:
        """Page-level conservative switch: a post-action write event OR a
        write verb inside the load surface (autosave page) both mean further
        probing of this page stays snapshot-only."""
        self._scan()
        if self._page is not None:
            return bool(self._page["writeEvents"] or self._page["writeSurface"])
        return bool(self._write_events or self._write_surface)


def describe_write_event(event: dict) -> str:
    """Operation-facing one-liner for the step timeline (W6 wording: names
    the verb, the endpoint and the step, no internal governance terms)."""
    return (f"页面在动作步 {event['stepNo']} 后发出了基线外写请求 "
            f"{event['method']} {event['url']}（探查已保守降级为只读快照）")


def describe_failure(entry: dict) -> str:
    status: Optional[int] = entry.get("status")
    if status is None:
        return (f"网络请求失败 {entry['method']} {entry['url']}"
                f"（{entry.get('failureText') or '连接中断'}，归因环境问题）")
    return (f"接口返回异常 {entry['method']} {entry['url']} → {status}"
            "（归因产品缺陷）")
