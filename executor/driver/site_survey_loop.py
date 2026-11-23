# -*- coding: utf-8 -*-

from __future__ import annotations

import time
from urllib.parse import urlsplit

from driver.http_gate import host_matches
from driver.link_frontier import (DEFAULT_GUARD_VERBS, DEFAULT_IGNORE_PARAMS,
                                  LinkFrontier, page_key)

# Screen-visible message keys must stay operation-facing (W6): name the hit
# value, the consequence and the switch key the operator can act on.
GUARD_REJECT_TEXT = ("生产域名默认不做整站测绘（起始地址 {host} 命中生产黑名单）。"
                     "如确需测绘该域名，请在系统设置→参数配置中开启 "
                     "element.survey-site.allow-prod 后重新发起。")


class SiteSurveyGuardError(RuntimeError):
    """Run refused before any navigation (production blacklist fail-closed)."""


def _origin_tuple(url: str):
    sp = urlsplit(url)
    return (sp.scheme.lower(), sp.netloc.lower())


def _host_denied(url: str, deny_patterns) -> bool:
    host = (urlsplit(url).hostname or "").lower()
    if not host:
        return False
    return any(host_matches(host, p) for p in deny_patterns)


def _network_window(browser) -> dict:
    """Per-page network account from the browser protocol (optional).

    ``network_account`` is only present on guard-equipped browsers; a plain
    protocol browser (L2 fixture, older adapter) yields an empty window so
    the survey behaviour stays byte-identical for it (zero regression face).
    """
    probe = getattr(browser, "network_account", None)
    if probe is None:
        return _empty_window()
    try:
        account = probe() or {}
    except Exception:  # noqa: BLE001 — attribution never crashes a page
        return _empty_window()
    return {"writeEvents": list(account.get("writeEvents") or []),
            "writeSurface": list(account.get("writeSurface") or []),
            "failures": list(account.get("failures") or [])}


def _snapshot_only(net: dict) -> bool:
    """Single conservative rule: a write inside the page window — either an
    autosave verb in the load surface or a write after an action — keeps this
    page read-only for every later probe (never a fake-green deep probe)."""
    return bool(net["writeEvents"] or net["writeSurface"])


def _empty_window() -> dict:
    """Fresh empty window (never share the module-level list objects)."""
    return {"writeEvents": [], "writeSurface": [], "failures": []}


def run_site_survey(rules: dict, browser, *, sleep=time.sleep,
                    clock=time.monotonic) -> dict:
    """Execute one site-survey BFS run; returns the run account.

 ``rules`` is the siteSurvey snapshot (startUrl/excludeGlobs/ignoreParams/
 verbs guard/budget/denyHosts/allowProd). The account carries status,
 page tallies, sibling segment counts, frontier counters and the journal
 frames + candidate rows the relay feeds into the single upsert
 collector. Raises SiteSurveyGuardError only for the fail-closed start
 host rejection; every page-level failure lands in the account instead.
 """
    start_url = str(rules.get("startUrl") or "")
    allow_prod = bool(rules.get("allowProd", False))
    deny = tuple(rules.get("denyHosts") or ())
    if not allow_prod and deny and _host_denied(start_url, deny):
        raise SiteSurveyGuardError(GUARD_REJECT_TEXT.format(
            host=(urlsplit(start_url).hostname or start_url)))

    max_pages = int(rules.get("maxPages", 100))
    interval_sec = float(rules.get("intervalMs", 800)) / 1000.0
    frontier = LinkFrontier(
        start_url,
        exclude_globs=rules.get("excludeGlobs", ()),
        ignore_params=rules.get("ignoreParams", DEFAULT_IGNORE_PARAMS),
        guard_verbs=rules.get("guardVerbs", DEFAULT_GUARD_VERBS),
        verbs_enabled=bool(rules.get("verbsGuardEnabled", True)),
        max_pages=max_pages,
        max_depth=int(rules.get("maxDepth", 6)),
        max_links_per_page=int(rules.get("maxLinksPerPage", 60)),
        per_template_cap=int(rules.get("perTemplateCap", 1)),
        deadline_seconds=float(rules.get("deadlineSeconds", 1800)),
        clock=clock)

    origin = _origin_tuple(start_url)
    journal, rows = [], []
    attempted = ok = failed = failed_login = redirect_out = prod_denied = 0
    links_seen = 0
    stop_reason = "empty"
    first = True

    frontier.begin_page(None)
    frontier.consider(None, start_url)
    def _frame(url, status, note="", candidates=0, net=None, final=None):
        net = net or _empty_window()
        journal.append({"url": url, "pageKey": page_key(url), "status": status,
                        "note": note, "candidates": candidates,
                        # The relay consumer keys its per-page pool by the
                        # address the browser actually landed on (a server
                        # normalization redirect differs from the queue
                        # entry); carry it so the pool lookup never misses.
                        **({"finalUrl": final} if final else {}),
                        "writeEvents": net["writeEvents"],
                        "writeSurface": net["writeSurface"],
                        "netFailures": net["failures"],
                        "snapshotOnly": _snapshot_only(net)})

    while frontier.pending:
        if attempted >= max_pages:
            stop_reason = "max_pages"
            break
        pages, stop_reason = frontier.drain(limit=max_pages - attempted)
        for url, _depth in pages:
            if not allow_prod and deny and _host_denied(url, deny):
                # Fail-closed re-check: a blacklisted host that slipped the start
                # check is never navigated (same-origin BFS makes this rare).
                prod_denied += 1
                _frame(url, "excluded_prod", "production blacklist host")
                continue
            if not first:
                sleep(interval_sec)
            first = False
            attempted += 1
            try:
                final_url = browser.goto(url)
                browser.wait_network_idle()
            except Exception as exc:  # noqa: BLE001 — page failure never crashes the run
                failed += 1
                _frame(url, "error", str(exc), net=_network_window(browser))
                continue
            # The window closes at network idle: autosave POSTs and failing
            # endpoints of THIS page are attributed to THIS page's frame.
            net = _network_window(browser)
            if _origin_tuple(final_url) != origin:
                failed += 1
                redirect_out += 1
                _frame(url, "redirect_out", "3xx landed on another origin", net=net)
                continue
            if page_key(final_url) != page_key(url):
                failed += 1
                failed_login += 1
                _frame(url, "failed_login", "被重定向（登录态可能不足或无权）", net=net)
                continue
            candidates = list(browser.collect_candidates())
            if browser.has_password_input() and not candidates:
                failed += 1
                failed_login += 1
                _frame(url, "failed_login", "登录形态且零业务候选", net=net)
                continue
            frontier.begin_page(url)
            hrefs = list(browser.link_anchors())
            for href, text in hrefs:
                links_seen += 1
                frontier.consider(url, href, text)
            page_rows = [dict(item) for item in candidates]
            if _snapshot_only(net):
                # Write-risk page: every candidate is published as read-only
                # evidence so the kernel never plans an action there.
                for row in page_rows:
                    row["probeMode"] = "snapshot-only"
            for row in page_rows:
                row["pageKey"] = page_key(url)
                row.setdefault("url", url)
                rows.append(row)
            ok += 1
            _frame(url, "ok", "", len(candidates), net=net, final=final_url)
        if stop_reason in ("deadline", "max_pages"):
            break

    status = "completed"
    if attempted and failed * 2 >= attempted:
        status = "failed"
    elif stop_reason in ("deadline", "max_pages") or frontier.pending:
        status = "partial"

    counters = dict(frontier.counters)
    counters["out_of_origin"] += redirect_out
    return {
        "status": status,
        "stop_reason": stop_reason,
        "pages_total": attempted,
        "pages_ok": ok,
        "pages_failed": failed,
        "failed_login": failed_login,
        "redirect_out": redirect_out,
        "prod_denied": prod_denied,
        "links_seen": links_seen,
        "out_of_origin": counters["out_of_origin"],
        "sibling_counts": dict(frontier.sibling_counts),
        "counters": counters,
        "journal": journal,
        "candidate_rows": rows,
    }
