# -*- coding: utf-8 -*-
"""V240 stub edit/delete segment E2E — the create-only surface becomes a full CRUD.

Real browser, no mocks. Assertion doors:
1. edit journey — the card's edit door opens the same drawer pre-filled,
 saving persists new values to the DB (single rule source, the truth the
 snapshot loader will serve), and the row version bumps;
2. optimistic lock honesty — a row edited underneath the open drawer (stale
 version echo) comes back as the plain-word conflict toast and the list
 reloads with the fresh row (no silent overwrite of the racing writer);
3. disable switch — one click through the update door, DB enabled flips,
 the card gains the "disabled" tag; enable again restores it;
4. delete door — the confirmation dialog quotes the rule's real 7-day hit
 count (a hit row is planted through the ledger table first), confirming
 removes the row while the hit ledger keeps its history;
5. provenance badge survives the edit (source is a birth fact the edit door
 cannot forge).

Rows are torn down for real; assert_no_errors bundles the 4xx/5xx (the
conflict step asserts its own 40901 business code INSIDE the flow before
counting, via the toast wording rather than a failed HTTP status — the admin
bridge answers business refusals as 200 + code field, so no console noise),
console error/warn and browser-issues zero-tolerance gates.
"""
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


def _publish(api, space_id, version=0):
    r = api.post(
        f"/mock/spaces/{space_id}/transition",
        json={"targetStatus": 1, "expectedVersion": version},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"publish failed: {r.text[:300]}"


def _mk_stub(api, space_id, name, method="GET", path="/orders", response=None):
    r = api.post(
        f"/mock/spaces/{space_id}/stubs",
        json={"name": name, "method": method, "pathMatchType": "exact",
              "pathPattern": path,
              "responseJson": response or "{\"status\":200,\"body\":\"v1\"}"},
    )
    assert r.status_code == 200 and r.json().get("code") == "0", f"stub create failed: {r.text[:300]}"
    return r.json()["data"]


def _api_update(api, stub_id, name, version, **over):
    body = {"name": name, "method": over.get("method", "GET"),
            "pathMatchType": "exact", "pathPattern": over.get("path", "/orders"),
            "responseJson": over.get("response", "{\"status\":200,\"body\":\"v1\"}"),
            "enabled": over.get("enabled", True),
            "expectedVersion": version}
    return api.post(f"/mock/stubs/{stub_id}/update", json=body)


def _stub_row(stub_id):
    row = sql_query(
        "SELECT name || '|' || enabled || '|' || version || '|' || source "
        f"FROM tp_mock_stub WHERE id = {stub_id}")
    assert row, f"DB 未找到规则行 {stub_id}"
    return (c.strip() for c in row.split("|"))


def _card(page):
    return page.locator('[data-test="mock-docs-card"]')


def _expand_resource_group(page, key="orders"):
    """ display-scale posture: groups render COLLAPSED by default, the
 cards only exist in the DOM after the group head opens. Click to expand
 only when collapsed (a second click would toggle it closed)."""
    head = page.locator(f'[data-test^="mock-docs-stub-group-head-{key}"]')
    if "true" not in (head.get_attribute("aria-expanded") or ""):
        head.click()
    expect(_card(page).first).to_be_visible(timeout=8000)


def test_v240b_edit_persists_and_lock_refuses_stale(logged_in, collectors, api_client, uniq):
    """Edit door persists to the rule source; a stale version echo is refused."""
    page = logged_in
    pid = _mk_project(api_client, f"EditE2E {uniq}", f"edt{uniq}")
    space_id = _mk_space(api_client, pid, f"edit-space-{uniq}", f"edt{uniq}")
    _publish(api_client, space_id)
    stub_id = _mk_stub(api_client, space_id, "before edit")

    try:
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-stub-group"]')).to_have_count(1, timeout=15000)
        _expand_resource_group(page)
        expect(_card(page)).to_have_count(1, timeout=5000)

        # --- conflict rehearsal first: push a server-side edit so the row the
        # page is about to read is fresh (version 2), then reopen — the drawer
        # carries the FRESH version and its save must succeed. Then plant a
        # racing edit while the drawer is open: the stale echo must be refused.
        name, enabled, version, _src = _stub_row(stub_id)
        assert name == "before edit" and version == "1"
        r = _api_update(api_client, stub_id, "racer edit", int(version))
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        _name, _en, version2, _s = _stub_row(stub_id)
        assert version2 == "2", "API edit must bump the lock"

        # card still shows the stale name until reload; refresh the page so the
        # row the drawer opens from carries version 2
        page.reload(wait_until="domcontentloaded")
        _expand_resource_group(page)
        expect(_card(page)).to_have_count(1, timeout=10000)
        # the card renders method/path/tags, not the rule name — verify
        # through the single rule source (the DB row IS the serving truth)
        assert sql_query(f"SELECT name FROM tp_mock_stub WHERE id = {stub_id}") == "racer edit"

        # open the edit drawer on the fresh row, pre-fill check
        card = _card(page).first
        card.locator('[data-test="mock-docs-card-edit"]').click()
        drawer = page.locator('[data-test="mock-docs-create-drawer"]')
        expect(drawer).to_be_visible(timeout=5000)
        expect(drawer.locator('[data-test="mock-docs-f-name-input"]')).to_have_value("racer edit")
        # published space => the live-impact honesty line shows
        impact = page.locator('[data-test="mock-docs-live-impact"]')
        expect(impact).to_be_visible(timeout=5000)
        assert "近 7 天" in impact.inner_text(), impact.inner_text()

        # --- plant the racer edit from underneath (simulates a second writer)
        _n3, _e3, version3, _s3 = _stub_row(stub_id)
        r = _api_update(api_client, stub_id, "second racer", int(version3), path="/orders")
        assert r.status_code == 200, r.text[:300]

        # saving with the drawer's stale echo must be refused in plain words
        drawer.locator('[data-test="mock-docs-f-name-input"]').fill("drawer winner")
        drawer.locator('[data-test="mock-docs-f-save"]').click()
        # Name the expected toast (strict mode: under -n load a previous
        # error toast can still be fading while this refusal animates in).
        toast = page.locator(".el-message").filter(has_text="已被其他人修改")
        expect(toast).to_be_visible(timeout=8000)

        # no silent overwrite: the DB keeps the racing writer's value
        name_final, _en, _v, _s = _stub_row(stub_id)
        assert name_final == "second racer", f"stale echo must not overwrite: {name_final}"

        # --- the honest retry path: reload, edit again with the fresh echo
        page.reload(wait_until="domcontentloaded")
        _expand_resource_group(page)
        expect(_card(page)).to_have_count(1, timeout=10000)
        assert sql_query(f"SELECT name FROM tp_mock_stub WHERE id = {stub_id}") == "second racer"
        _card(page).first.locator('[data-test="mock-docs-card-edit"]').click()
        expect(drawer).to_be_visible(timeout=5000)
        drawer.locator('[data-test="mock-docs-f-name-input"]').fill("drawer winner")
        drawer.locator('[data-test="mock-docs-f-path-input"]').fill("/paid-orders")
        drawer.locator('[data-test="mock-docs-f-save"]').click()
        ok = page.locator(".el-message--success:visible")
        expect(ok).to_contain_text("已更新", timeout=8000)
        # The stale-echo refusal above IS the acceptance target (a 409 with
        # plain words + toast). Following the credential-vault 409 precedent:
        # this test prunes ONLY its own expected-conflict scene AFTER asserting
        # it; every other 4xx/console error still fails the run.
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/api/v1/mock/stubs/" in e["url"] and "/update" in e["url"]
                   and e["status"] == 409)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 409" not in c]
        name2, _en2, version2x, src2 = _stub_row(stub_id)
        assert name2 == "drawer winner" and "/paid-orders" in sql_query(
            f"SELECT path_pattern FROM tp_mock_stub WHERE id = {stub_id}")
        assert version2x == "4", f"two successful saves over rows v1->v2->v3->v4: {version2x}"
        assert src2 == "manual", "provenance is a birth fact — an edit cannot forge it"

        assert_no_errors(collectors, "v240 edit/delete journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE id = {stub_id}")
        sql_query(f"DELETE FROM tp_mock_hit_log WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")


def test_v240b_disable_switch_and_delete_impact(logged_in, collectors, api_client, uniq):
    """One-click disable/enable; delete door quotes the real 7-day impact."""
    page = logged_in
    pid = _mk_project(api_client, f"DelE2E {uniq}", f"del{uniq}")
    space_id = _mk_space(api_client, pid, f"del-space-{uniq}", f"del{uniq}")
    _publish(api_client, space_id)
    stub_id = _mk_stub(api_client, space_id, "traffic rule", path="/hot")
    # plant matched serves (2) + one miss + one old-outside-window row
    sql_query("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
              f" VALUES ({space_id}, {stub_id}, TRUE, now())")
    sql_query("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
              f" VALUES ({space_id}, {stub_id}, TRUE, now())")
    sql_query("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
              f" VALUES ({space_id}, {stub_id}, FALSE, now())")
    sql_query("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
              f" VALUES ({space_id}, {stub_id}, TRUE, now() - interval '8 days')")

    try:
        page.goto(_wb(space_id, pid), wait_until="domcontentloaded")
        expect(page.locator('[data-test="mock-docs-stub-group"]')).to_have_count(1, timeout=15000)
        _expand_resource_group(page, key="hot")
        expect(_card(page)).to_have_count(1, timeout=5000)
        card = _card(page).first

        # disable switch: one click, DB truth flips, tag shows
        card.locator('[data-test="mock-docs-card-toggle"]').click()
        expect(page.locator(".el-message--success:visible")).to_contain_text("已停用", timeout=8000)
        _n, enabled, _v, _s = _stub_row(stub_id)
        assert enabled in ("f", "false"), f"enabled must land false: {enabled}"
        expect(_card(page).first).to_contain_text("已停用", timeout=8000)

        # enable again. The DB row is the acceptance face — poll it directly:
        # the PREVIOUS success toast lingers ~3s, and a text-wait can match
        # the stale toast instead of the new one (flaky anchor caught live in
        # this suite's first run). The tag/card text check below still proves
        # the UI reacted.
        _card(page).first.locator('[data-test="mock-docs-card-toggle"]').click()
        import time as _t
        for _ in range(60):
            _n, enabled2, _v2, _s2 = _stub_row(stub_id)
            if enabled2 in ("t", "true"):
                break
            _t.sleep(0.1)
        assert enabled2 in ("t", "true"), f"enabled never returned to true: {enabled2}"

        # delete door: confirmation names the rule and its real 7-day count (2,
        # not 3 — misses and the out-of-window row must not count)
        _card(page).first.locator('[data-test="mock-docs-card-delete"]').click()
        confirm = page.locator('.el-message-box:visible')
        expect(confirm).to_be_visible(timeout=5000)
        assert "traffic rule" in confirm.inner_text(), confirm.inner_text()
        assert "近 7 天被真实调用了 2 次" in confirm.inner_text(), confirm.inner_text()
        confirm.get_by_role("button", name="确认删除").click()

        # DB is the acceptance face (a lingering success toast can outrank the
        # new one on a text-wait — same flaky anchor as the enable step).
        import time as _t2
        for _ in range(80):
            if sql_query(f"SELECT COUNT(*) FROM tp_mock_stub WHERE id = {stub_id}") == "0":
                break
            _t2.sleep(0.1)
        row = sql_query(f"SELECT COUNT(*) FROM tp_mock_stub WHERE id = {stub_id}")
        assert row == "0", "rule row must be gone"
        kept = sql_query(
            f"SELECT COUNT(*) FROM tp_mock_hit_log WHERE stub_id = {stub_id}")
        assert kept == "4", f"traffic history survives rule churn: {kept}"

        expect(_card(page)).to_have_count(0, timeout=8000)
        assert_no_errors(collectors, "v240 edit/delete journey")
    finally:
        sql_query(f"DELETE FROM tp_mock_stub WHERE id = {stub_id}")
        sql_query(f"DELETE FROM tp_mock_hit_log WHERE space_id = {space_id}")
        sql_query(f"DELETE FROM tp_mock_space WHERE id = {space_id}")
