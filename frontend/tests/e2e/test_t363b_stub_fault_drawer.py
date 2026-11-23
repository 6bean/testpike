# -*- coding: utf-8 -*-

import json

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, sql_query

SPACES_URL = f"{BASE_URL}/#/mock/spaces"


def _wb(sid, pid, tab="docs"):
    """ AN-4: the docs screen rides the space workbench tab."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _mk_project(api, name, code):
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    return r.json()["data"]


def _mk_space(api, pid, name, key):
    r = api.post("/mock/spaces", json={"projectId": pid, "name": name, "spaceKey": key})
    assert r.status_code == 200 and r.json().get("code") == "0", f"space create failed: {r.text[:300]}"
    return r.json()["data"]


def _publish(api, space_id, version):
    r = api.post(
        f"/mock/spaces/{space_id}/transition",
        json={"targetStatus": 1, "expectedVersion": version},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _mk_stub(api, space_id, name, path, fault=None, method="GET"):
    response = {"status": 200, "bodyMode": "static", "body": '{"ok":true}'}
    if fault:
        response["fault"] = fault
    r = api.post(
        f"/mock/spaces/{space_id}/stubs",
        json={
            "name": name,
            "method": method,
            "pathMatchType": "exact",
            "pathPattern": path,
            "responseJson": json.dumps(response, ensure_ascii=False),
            "source": "manual",
        },
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"stub create failed: {r.text[:300]}"
    return r.json()["data"]


def _teardown(space_id, pid):
    sql_query(f"DELETE FROM tp_mock_stub WHERE space_id = {space_id}")
    sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")


def test_t363b_drawer_fault_rule_journey(logged_in, collectors, api_client, uniq):
    """Drawer create (delay fault) -> fault chip -> reload persists -> blank-path local stop."""
    page = logged_in
    proj_name = f"FaultE2E {uniq}"
    pid = _mk_project(api_client, proj_name, f"fe{uniq}"[:12])
    space_key = f"fe{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"fault-space-{uniq}", space_key)
    _publish(api_client, space_id, 0)
    try:
        # full reload after the deep link (T396b precedent): the visible
        # project fleet must re-fetch or the freshly created id stays outside
        # the stale snapshot and the ownership echo has no name to show
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        # header context landed on the deep-linked project (context = single source)
        expect(page.locator('[data-test="project-ctx-trigger"]')).to_contain_text(
            proj_name, timeout=15000)
        # the freshly created space auto-selects (first row of the loaded list)
        expect(page.locator('[data-test="mock-docs-new-structured"]')).to_be_visible(timeout=10000)

        # --- local guard first (no server traffic expected either way) ---
        page.locator('[data-test="mock-docs-new-structured"]').click()
        drawer = page.locator('[data-test="mock-docs-create-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        # iron rule: ownership echo read-only, single-sourced from the header context
        echo = drawer.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible(timeout=8000)
        expect(echo.locator('[data-test="owner-echo-value"]')).to_have_text(proj_name, timeout=8000)
        # rule 1: no selector forked inside the drawer
        expect(drawer.locator('[data-test="owner-echo"] .el-select')).to_have_count(0)

        drawer.locator('[data-test="mock-docs-f-name-input"]').fill(f"blank-path-guard-{uniq}")
        page.locator('[data-test="mock-docs-f-save"]').click()
        hint = page.locator('[data-test="mock-docs-form-error"]')
        expect(hint).to_be_visible(timeout=5000)
        expect(hint).to_contain_text("路径", timeout=5000)
        rows = sql_query(f"SELECT count(*) FROM tp_mock_stub WHERE space_id = {space_id}")
        assert int(rows.strip().split("\n")[-1]) == 0, "local guard leaked a create to the server"

        # --- full drawer journey: GET /faulted-demo with a delay fault ---
        drawer.locator('[data-test="mock-docs-f-path-input"]').fill("/faulted-demo")
        drawer.locator('[data-test="mock-docs-f-fault-switch"] .el-switch').click()
        expect(drawer.locator('[data-test="mock-docs-f-fault-type"]')).to_be_visible(timeout=5000)
        # vision dim1 fix: the fault parameter block is separated from the
        # base-response fields by a rule line (DOM cross-check of the shot)
        divider = drawer.locator('[data-test="mock-docs-f-fault-divider"]')
        expect(divider).to_be_visible(timeout=5000)
        # default type is delay; type the probability gate and the interval
        drawer.locator('[data-test="mock-docs-f-probability"] input').fill("100")
        drawer.locator('[data-test="mock-docs-f-minms"] input').fill("1200")
        drawer.locator('[data-test="mock-docs-f-maxms"] input').fill("1800")
        page.locator('[data-test="mock-docs-f-save"]').click()
        # V240 assertion correction (precedent: t361b re-anchored by ):
        # small spaces now AUTO-EXPAND on write — a created rule that stayed
        # collapsed read as "the page lost my work". The group header still
        # counts, and the card is visible WITHOUT a manual expand click.
        # (The scale half of this suite — 33-rule fixtures — still asserts the
        # collapsed default, so the display-scale law keeps its anchor.)
        head = page.locator('[data-test="mock-docs-stub-group-head-faulted-demo"]')
        expect(head).to_be_visible(timeout=10000)
        expect(head).to_contain_text("1", timeout=5000)
        card = page.locator('[data-test="mock-docs-card"]')
        expect(card).to_have_count(1, timeout=5000)
        expect(card).to_contain_text("GET", timeout=5000)
        chip = card.locator('[data-test="mock-docs-card-fault"]')
        expect(chip).to_contain_text("延迟响应", timeout=5000)
        expect(chip).to_contain_text("100%", timeout=5000)

        # the persisted shape: fault block carries ONLY the delay-owned keys
        body = sql_query(
            f"SELECT response_json::text FROM tp_mock_stub WHERE space_id = {space_id}")
        doc = json.loads(body.strip().split("\n")[-1])
        fault = doc.get("fault") or {}
        assert fault.get("type") == "delay" and fault.get("probability") == 100 \
            and fault.get("minMs") == 1200 and fault.get("maxMs") == 1800, \
            f"fault payload drift: {fault}"
        assert set(fault.keys()) == {"type", "probability", "minMs", "maxMs"}, \
            f"payload carried foreign keys: {fault}"

        # reload keeps truth — row + chip survive. V240: the small space
        # auto-expands on load, so the card is there WITHOUT a click (the
        # old manual head.click() would now TOGGLE the auto-expanded group
        # closed — the mirrored seam of the same half-done display-scale
        # correction; asserting the auto state directly is the honest pin).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-stub-group-head-faulted-demo"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="mock-docs-card-fault"]')).to_contain_text("延迟响应", timeout=5000)

        assert_no_errors(collectors, "T363b drawer fault journey")
    finally:
        _teardown(space_id, pid)
        api_client.delete(f"/projects/{pid}/purge")


def test_t363b_worst_scale_grouping_and_search(logged_in, collectors, api_client, uniq):
    """30+ rules: collapsed groups with counts + instant search narrowing (zero extra requests)."""
    page = logged_in
    pid = _mk_project(api_client, f"ScaleE2E {uniq}", f"sc{uniq}"[:12])
    space_key = f"sc{uniq}".lower()
    space_id = _mk_space(api_client, pid, f"scale-space-{uniq}", space_key)
    _publish(api_client, space_id, 0)
    # worst-case fixture: 33 rules over 3 resource groups (display-scale iron law —
    # small samples hide the wall, so the E2E must feed the bad shape)
    for i in range(33):
        group = ("orders", "users", "catalog")[i % 3]
        _mk_stub(api_client, space_id, f"scale-{group}-{i}-{uniq}", f"/{group}/r{i}",
                 fault={"type": "status", "statusCode": 503, "probability": 50} if group == "users" else None)
    try:
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-page"]')).to_be_visible(timeout=15000)
        heads = page.locator('[data-test="mock-docs-stub-group"]')
        expect(heads).to_have_count(3, timeout=10000)
        # collapsed by default: zero cards rendered at the worst scale
        expect(page.locator('[data-test="mock-docs-card"]')).to_have_count(0, timeout=2000)
        # each header names its own count (11 per group at 33 rows)
        for g in ("orders", "users", "catalog"):
            expect(page.locator(f'[data-test="mock-docs-stub-group-head-{g}"]')).to_contain_text("11", timeout=5000)

        # instant search over the loaded window narrows the groups — no second
        # request. el-input lands data-test on the native input itself (pit 23).
        search = page.locator('[data-test="mock-docs-stub-search"]')
        search.fill("users")
        expect(page.locator('[data-test="mock-docs-stub-group"]')).to_have_count(1, timeout=5000)
        page.locator('[data-test="mock-docs-stub-group-head-users"]').click()
        cards = page.locator('[data-test="mock-docs-card"]')
        expect(cards).to_have_count(11, timeout=5000)
        # the users group is the faulted one — every card carries the chip
        chips = page.locator('[data-test="mock-docs-card-fault"]')
        expect(chips).to_have_count(11, timeout=5000)
        expect(chips.first).to_contain_text("状态码故障", timeout=5000)

        search.fill("")
        expect(page.locator('[data-test="mock-docs-stub-group"]')).to_have_count(3, timeout=5000)

        assert_no_errors(collectors, "T363b worst-scale grouping")
    finally:
        _teardown(space_id, pid)
        api_client.delete(f"/projects/{pid}/purge")
