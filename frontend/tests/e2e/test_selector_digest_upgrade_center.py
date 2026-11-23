# -*- coding: utf-8 -*-
""" digest E2E — upgrade-center selectors searchability spectrum.

Proves the four selectors fixed by the digest retrofit (upgrade-plan-engines,
upgrade-plan-canary, upgrade-plan-manifest, upgrade-advisor-version) collapse
to the needle option under their REAL cluster ledger scale, not just that
`filterable` compiles:

- engines: descriptor catalog rows (factory-enabled set, dev ceiling 7 < 30)
 — honestly labeled as the environment-domain ceiling per the
 calibration, and the needle contract (one typed substring collapses to the
 exact option) is still hard-asserted on that ceiling.
- canary nodes: executor ledger — dev registrations are 0 (no factory rows and
 no delete path after protocol registration), so the honest ceiling is the
 empty ledger; the contract asserted is "typed unique token matches exactly
 the number of ledger names containing it (= 0), dropdown never lies".
- manifest / advisor version: worst-scale family of 32 manifests registered
 through the real admin->core chain (uuid-suffixed versions), family purged
 by exact LIKE in finally (no API delete path; same purge posture as
 test_cluster_upgrade_center).

Real browser + real backend chain (admin 8081 -> Feign -> core 8083 -> real
DB; no mocks). Three-class zero tolerance via assert_no_errors. All render
assertions are hard counts (audit law: no conditional wrappers).
"""
import json

from playwright.sync_api import expect

from conftest import (
    VISIBLE_OPTS,
    assert_no_errors,
    filter_to_needle,
    inject_token,
    open_journey_leaf,
    sql_query,
)

MANIFEST_FILLERS = 32


def _rows(resp_json):
    data = resp_json.get("data")
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        return data.get("records") or data.get("items") or data.get("list") or []
    return []


def _seed_manifest_family(api_client, uid):
    """Register 32 same-family release manifests through the real chain."""
    versions = [f"9.7.{i}-{uid}" for i in range(MANIFEST_FILLERS)]
    for v in versions:
        r = api_client.post("/cluster/release-manifests", json={
            "version": v,
            "componentMatrixJson": json.dumps(
                [{"component": "executor", "support": ">=1.0", "pinned": "1.0.0"}]),
            "breakingChangesJson": "[]",
            "source": "manual",
            "artifactsJson": json.dumps([{
                "component_family": "executor",
                "url": f"https://github.com/testpike/releases/executor-{v}.zip",
                "sha256": "a" * 64, "size": 1024}]),
        })
        assert r.json().get("code") in (0, "0"), f"register {v} failed: {r.text[:200]}"
    return versions


def _purge_manifest_family(uid):
    sql_query(f"DELETE FROM tp_release_manifest WHERE version LIKE '9.7.%-{uid}'")
    left = sql_query(f"SELECT count(*) FROM tp_release_manifest WHERE version LIKE '9.7.%-{uid}'")
    assert left.strip() == "0", f"manifest family purge leftover: {left}"


def _open_upgrade_center(page):
    open_journey_leaf(page, "menu-upgrade-center", "system")
    expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=10000)


def _go_tab(page, name):
    page.locator('[data-test="upgrade-tabs"]').get_by_role("tab", name=name).click()


def test_upgrade_plan_engine_selector_searchable_on_catalog(page, collectors, api_client):
    """Plan scope=engine: catalog rows render, 'Maestro' collapses to the one option."""
    eng = _rows(api_client.get("/appauto/engines").json())
    enabled = [e for e in eng if e.get("status") == 1]
    assert enabled, "engine descriptor catalog has no enabled rows — digest contract " \
                    "needs the factory catalog; baseline drift? run ensure_baseline.py"
    inject_token(page)
    _open_upgrade_center(page)
    _go_tab(page, "升级计划")
    expect(page.locator('[data-test="upgrade-plan-form"]')).to_be_visible(timeout=8000)
    # Default scope is engine (planForm.scope = 'engine'); the engines selector
    # renders exactly the status==1 catalog rows.
    filter_to_needle(page, "upgrade-plan-engines", len(enabled), "Maestro", "Maestro")
    chips = page.locator('[data-test="upgrade-plan-engines"]').get_by_text("Maestro")
    expect(chips.first).to_be_visible(timeout=5000)
    page.keyboard.press("Escape")
    assert_no_errors(collectors, "upgrade plan engines searchability")


def test_upgrade_plan_canary_selector_honest_empty_ledger(page, collectors, api_client):
    """Canary nodes: dev executor ledger is 0 (honest ceiling); a unique token
    must match exactly 0 options — the dropdown never lies and never crashes."""
    nodes = _rows(api_client.get("/executors").json())
    inject_token(page)
    _open_upgrade_center(page)
    _go_tab(page, "升级计划")
    expect(page.locator('[data-test="upgrade-plan-form"]')).to_be_visible(timeout=8000)
    page.locator('[data-test="upgrade-plan-canary"]').click()
    opts = page.locator(VISIBLE_OPTS)
    expect(opts).to_have_count(len(nodes), timeout=8000)
    page.keyboard.type("zzq-no-such-node")
    # Hard count: ledger names containing this unique token = 0, always.
    expect(opts).to_have_count(0, timeout=5000)
    page.keyboard.press("Escape")
    assert_no_errors(collectors, "upgrade plan canary empty-ledger honesty")


def test_upgrade_plan_and_advisor_manifest_selectors_searchable_under_worst_scale(
        page, collectors, api_client, uniq):
    """32-manifest worst-scale family: plan + advisor selectors both collapse
    to the exact needle version with one typed search."""
    try:
        versions = _seed_manifest_family(api_client, uniq)
        needle = versions[MANIFEST_FILLERS - 1]
        inject_token(page)
        _open_upgrade_center(page)
        live = _rows(api_client.get("/cluster/release-manifests").json())
        # Lower-bound pin (other suites may hold their own families open on a
        # shared dev DB): at least our 32 fixture rows are in the ledger.
        assert len(live) >= MANIFEST_FILLERS, f"ledger {len(live)} < fixture {MANIFEST_FILLERS}"

        _go_tab(page, "升级计划")
        expect(page.locator('[data-test="upgrade-plan-form"]')).to_be_visible(timeout=8000)
        # Single select: opening lists every ledger row, one exact search pins the needle.
        page.locator('[data-test="upgrade-plan-manifest"]').click()
        expect(page.locator(VISIBLE_OPTS)).to_have_count(len(live), timeout=10000)
        page.keyboard.type(needle)
        opts = page.locator(VISIBLE_OPTS)
        expect(opts).to_have_count(1, timeout=5000)
        expect(opts.first).to_contain_text(needle)
        opts.first.click()
        expect(page.locator('[data-test="upgrade-plan-manifest"]')).to_contain_text(needle)

        _go_tab(page, "升级洞察")
        page.locator('[data-test="upgrade-advisor-version"]').click()
        o2 = page.locator(VISIBLE_OPTS)
        expect(o2).to_have_count(len(live), timeout=10000)
        page.keyboard.type(needle)
        expect(o2).to_have_count(1, timeout=5000)
        expect(o2.first).to_contain_text(needle)
        o2.first.click()
        expect(page.locator('[data-test="upgrade-advisor-version"]')).to_contain_text(needle)
        page.keyboard.press("Escape")
        assert_no_errors(collectors, "upgrade center manifest selectors searchability")
    finally:
        _purge_manifest_family(uniq)
