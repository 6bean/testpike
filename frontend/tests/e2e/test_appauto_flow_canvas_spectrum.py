"""UI Flow canvas page first spectrum (Playwright real browser + real backend, no mocks).

Covers the read-only step-tree-to-flowchart canvas (W5 phase-two segment B3):
1. Worst-scale floor: a case whose script carries 33 action steps renders the
 projected mermaid flowchart in full (>=30 node groups in the SVG) with the
 stage scrolling instead of paginating — no truncation, no blanking.
2. Page state rides the URL (?projectId + ?caseId deep link): reload restores
 the same chart; the picked case comes back from the query, not memory.
3. Node click delegation: clicking the SVG group whose element id is
 `flowchart-<nodeId>` resolves the step through the emitted node mapping
 (GET /cases/{id}/flowchart served by the admin bridge) — the step card with
 the jump-to-case action appears and the clicked node gets the active marker.
4. Menu entry: the sidebar leaf [data-test=menu-flow-canvas] lands on the page;
 without a picked case the page shows its honest empty state.
5. Three-class zero tolerance (4xx/5xx, console error/warning, browser Issues
 panel) asserted by assert_no_errors — collectors mount issues_monitor.

Data isolation: uuid-suffixed scratch project + case + script, purged through
the real project-purge API in teardown (xdist-safe). The script is stored via
the core internal save channel, mirroring the C1 flowchart construction (no LLM).
"""
import json

import httpx
import pytest
from conftest import (ADMIN_PASSWORD, API_BASE, BASE_URL, _site_url, api_login,
                      assert_no_errors, open_journey_leaf)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(420)

CORE = _site_url("TP_CORE_BASE", "CORE_PORT")
PAGE = "[data-test='flow-canvas-page']"
SVG = "[data-test='flow-canvas-svg']"
NODE_SEL = f"{SVG} [id^='flowchart-']"
STEP_CARD = "[data-test='flow-canvas-step-card']"

CANVAS_PATH = "/appauto/flow-canvas"


def _token_headers():
    return {"Authorization": f"Bearer {api_login('admin', ADMIN_PASSWORD)}"}


def _inject_token(page, token):
    """Real login + token injection (same landing posture as the element library)."""
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "(t) => { localStorage.setItem('tp_access_token', t); "
        "localStorage.setItem('tp_onboarding_dismissed', '1'); }", token)
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator("[data-test='my-project-table']").first).to_be_visible(timeout=15000)


def _action_json_33(case_id, title):
    """33-step v2 action document: 29 linear leaves + if(else) + loop(2 leaves) + tail.

    Worst-scale floor (>=30 steps) so the canvas projection is exercised at a
    size where a naive single-screen layout would break.
    """
    steps = [{"stepId": i, "action": "click", "target": {"css": f"#cell-{i}"}}
             for i in range(1, 30)]
    steps.append({"stepId": 30, "action": "if", "condition": "订单列表非空",
                  "steps": [{"stepId": 31, "action": "expect", "target": {"css": ".row-first"}}],
                  "elseSteps": [{"stepId": 32, "action": "input",
                                 "target": {"text": "关键字"}, "value": "no-hit"}]})
    steps.append({"stepId": 33, "action": "loop", "count": 2,
                  "steps": [{"stepId": 34, "action": "goto", "target": {"url": "/home"}}]})
    return json.dumps({
        "version": "v2", "caseId": case_id, "title": title, "endType": "mobile",
        "steps": steps,
        "env": {"ENV": "dev", "PACK": "w5-spec", "DATA": "pool-a"},
    }, ensure_ascii=False)


def _seed_case(api, uid):
    """Scratch project + case shell, then the script lands via the core internal save."""
    r = api.post("/projects", json={"name": f"fc-spec-{uid}", "code": f"FC{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    r = api.post("/cases", json={
        "projectId": pid, "title": f"画布谱_{uid}",
        "precondition": "前置：设备已连接", "expectedResult": "预期：流程可投影",
        "priority": 1, "confidence": 1, "endType": "mobile", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开应用", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    case = r.json()["data"]
    case_id = case["id"] if isinstance(case, dict) else case
    title = f"画布谱_{uid}"
    s = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": _action_json_33(case_id, title), "generatedBy": 1})
    assert s.status_code == 200, f"save script failed: {s.status_code} {s.text[:200]}"
    assert s.json().get("code") == "0", f"save script business error: {s.text[:200]}"
    return pid, case_id, title


def test_flow_canvas_full_spectrum(page, collectors, api_client, uniq):
    """Worst-scale render + URL state restore + node click delegation -> jump action."""
    pid, case_id, _title = _seed_case(api_client, uniq)
    token = api_login("admin", ADMIN_PASSWORD)
    try:
        # The emitted node mapping is the contract the DOM must honour.
        r = httpx.get(f"{API_BASE}/cases/{case_id}/flowchart",
                      headers={"Authorization": f"Bearer {token}"}, timeout=15)
        assert r.status_code == 200, r.text[:300]
        fc = r.json()["data"]
        mapped = [n for n in (fc.get("nodes") or []) if n.get("stepIndex") is not None]
        assert len(mapped) >= 30, f"node mapping below worst-scale floor: {len(mapped)}"

        _inject_token(page, token)
        deep = f"{BASE_URL}/#{CANVAS_PATH}?projectId={pid}&caseId={case_id}"
        page.goto(deep, wait_until="domcontentloaded")
        expect(page.locator(PAGE)).to_be_visible(timeout=15000)

        # mermaid renders asynchronously: the SVG block appears once the render
        # settles (web-first wait, no hard sleep).
        svg = page.locator(SVG)
        expect(svg).to_be_visible(timeout=30000)
        assert svg.locator("g.node").count() >= 30, \
            "canvas truncated below the 30-node worst-scale floor"

        # URL page state: reload from the same deep link restores the chart.
        page.reload(wait_until="domcontentloaded")
        expect(svg).to_be_visible(timeout=30000)
        assert svg.locator("g.node").count() >= 30, \
            "chart lost after reload (page state not restored from URL)"

        # Click delegation: mermaid 11 renders the node group with the id
        # `<renderId>-flowchart-<nodeId>-<counter>`; the page parser trims it.
        hit = next(n for n in mapped if n["stepIndex"] == 1)
        nid = hit["id"]
        page.locator(f'{SVG} g.node[id*="flowchart-{nid}-"]').first.click()
        card = page.locator(STEP_CARD)
        expect(card).to_be_visible(timeout=10000)
        expect(page.locator("[data-test='flow-canvas-jump-btn']")).to_be_visible()
        expect(page.locator("[data-canvas-active]")).to_have_count(1)

        # Handover: jump navigates to the owning case detail (steps live there).
        page.locator("[data-test='flow-canvas-jump-btn']").click()
        expect(page).to_have_url(f"{BASE_URL}/#/cases/{case_id}", timeout=15000)

        assert_no_errors(collectors, "flow canvas full spectrum")
    finally:
        if pid:
            httpx.delete(f"{API_BASE}/projects/{pid}/purge?force=true",
                         headers=_token_headers(), timeout=30)


def test_flow_canvas_menu_entry_and_empty_state(page, collectors, uniq):
    """Sidebar leaf lands on the canvas; without a picked case the empty state shows."""
    token = api_login("admin", ADMIN_PASSWORD)
    _inject_token(page, token)
    open_journey_leaf(page, "menu-flow-canvas", "appauto")
    expect(page.locator(PAGE)).to_be_visible(timeout=15000)
    expect(page.locator("[data-test='flow-canvas-empty']")).to_be_visible(timeout=10000)
    expect(page.locator("[data-test='flow-canvas-query-btn']")).to_be_visible()
    assert "caseId" not in page.url
    assert_no_errors(collectors, "flow canvas menu entry")
