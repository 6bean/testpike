
import contextlib
from datetime import datetime, timedelta

import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    sql_query,
)

pytestmark = pytest.mark.timeout(900)

APPROVAL_KEY = "appauto.reservation.approval-required"


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S")


def _pick_project(page, pid: int):
    """: page-level project selector removed — the ?projectId deep link lands
 the global header context. Dashboard bounce forces a remount (query-only
 navigation reuses the component, so the deep-link setup would not re-run)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/reservations?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="reservation-calendar-page"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="reservation-calendar-shell"]')).to_be_visible(timeout=15000)


def _dt_input(root, name: str, tag: str = "input"):
    pass
    return root.locator(f'{tag}[data-test="{name}"], [data-test="{name}"] {tag}').first


def _drag_create(page, lane_idx: int):
    pass
    track = page.locator('[data-test="reservation-lane"] .lane-track').nth(lane_idx)
    box = track.bounding_box()
    assert box and box["width"] > 100, f"lane-track 未渲染: {box}"
    x1 = box["x"] + box["width"] * 0.55
    x2 = box["x"] + box["width"] * 0.68
    y = box["y"] + box["height"] / 2
    page.mouse.move(x1, y)
    page.mouse.down()
    page.mouse.move((x1 + x2) / 2, y, steps=4)
    page.mouse.move(x2, y, steps=4)
    page.mouse.up()
    dlg = page.locator('[data-test="reservation-create-dialog"]')
    expect(dlg).to_be_visible(timeout=8000)
    start = _dt_input(dlg, "reservation-form-start").input_value()
    end = _dt_input(dlg, "reservation-form-end").input_value()
    assert start and end, "拖拽后 dialog 时间窗未预填（吸附链路断）"
    _dt_input(dlg, "reservation-form-reason", "textarea").fill(f"E2E日历拖拽建窗{lane_idx}")
    dlg.locator('[data-test="reservation-create-submit"]').click()
    expect(dlg).to_be_hidden(timeout=10000)
    return start, end


def _reopen_calendar(page, pid: int):
    """Re-navigation = component remount → the aggregate endpoint re-reads from the
    DB (persistence posture; the deep-link bounce rides through the dashboard)."""
    _pick_project(page, pid)


def test_appauto_reservation_calendar(page, api_client, collectors, uniq):
    pass
    pid = None
    dev_ids: list[int] = []
    res_ids: list[int] = []
    batch_id = None
    approval_on = False
    try:
        proj_name = f"预约日历{uniq}"
        pid = api_client.post("/projects", json={
            "name": proj_name, "code": f"RCP{uniq.upper()}"}).json()["data"]
        port_base = 40000 + (ord(uniq[0]) * 97 + ord(uniq[1]) * 13) % 19000
        for i in (1, 2):
            r = api_client.post("/appauto/devices", json={
                "projectId": pid, "name": f"日历设备{uniq}{i}",
                "serial": f"RCS{uniq.upper()}{i}", "deviceType": "ADB",
                "adbAddress": f"127.0.0.1:{port_base + i}", "platform": "ANDROID"})
            assert r.status_code == 200 and r.json().get("code") == "0", f"注册设备失败: {r.text[:300]}"
            dev_ids.append(int(r.json()["data"]))

        r0_start = datetime.now() + timedelta(hours=3)
        r0_end = r0_start + timedelta(hours=1)
        r = api_client.post("/appauto/reservation", json={
            "projectId": pid, "deviceId": dev_ids[0],
            "startsAt": _iso(r0_start), "endsAt": _iso(r0_end),
            "reason": f"E2E预置预约{uniq}"})
        assert r.status_code == 200 and r.json().get("code") == "0", f"API 建预约失败: {r.text[:300]}"
        r0 = r.json()["data"]
        res_ids.append(int(r0["id"]))

        inject_token(page)
        # ① lane rendering (two devices, two lanes, live aggregate endpoint; the
        # deep-link bounce lands the segment)
        _pick_project(page, pid)
        expect(page.locator('[data-test="reservation-lane"]')).to_have_count(2, timeout=10000)
        expect(page.locator('[data-test="reservation-block"]')).to_have_count(1, timeout=10000)

        _drag_create(page, 0)
        expect(page.locator('[data-test="reservation-block"]')).to_have_count(2, timeout=10000)
        r1 = int(sql_query(
            "SELECT id FROM tp_appauto_reservation WHERE project_id = "
            f"{pid} AND id <> {res_ids[0]} ORDER BY id DESC LIMIT 1").splitlines()[0])
        res_ids.append(r1)

        _reopen_calendar(page, pid)
        expect(page.locator('[data-test="reservation-block"]')).to_have_count(2, timeout=15000)

        page.locator('[data-test="reservation-create-btn"]').click()
        dlg = page.locator('[data-test="reservation-create-dialog"]')
        expect(dlg).to_be_visible(timeout=8000)
        dlg.locator('[data-test="reservation-form-device"]').click()
        dev_opt = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(
            has_text=f"日历设备{uniq}1").first
        expect(dev_opt).to_be_visible(timeout=5000)
        dev_opt.click()
        _dt_input(dlg, "reservation-form-start").fill(_iso(r0_start))
        _dt_input(dlg, "reservation-form-start").press("Enter")
        _dt_input(dlg, "reservation-form-end").fill(_iso(r0_end))
        _dt_input(dlg, "reservation-form-end").press("Enter")
        _dt_input(dlg, "reservation-form-reason", "textarea").fill(f"E2E冲突{uniq}")
        dlg.locator('[data-test="reservation-create-submit"]').click()
        err = page.locator(".el-message--error")
        expect(err.first).to_be_visible(timeout=10000)
        msg = err.first.text_content() or ""
        assert "已被" in msg and "预约" in msg, f"冲突人话拒文案缺失（应点名占用者）: {msg}"
        expect(dlg).to_be_visible(timeout=3000)
        dlg.locator('[data-test="reservation-create-cancel"]').click()
        expect(dlg).to_be_hidden(timeout=8000)
        expect(page.locator('[data-test="reservation-block"]')).to_have_count(2, timeout=5000)

        _set_sys_config_value(APPROVAL_KEY, "1")
        approval_on = True
        _drag_create(page, 1)
        r2 = int(sql_query(
            "SELECT id FROM tp_appauto_reservation WHERE project_id = "
            f"{pid} AND id NOT IN ({','.join(map(str, res_ids))}) ORDER BY id DESC LIMIT 1"
        ).splitlines()[0])
        res_ids.append(r2)
        state = sql_query(f"SELECT state FROM tp_appauto_reservation WHERE id = {r2}")
        assert state == "PENDING_APPROVAL", f"审批开关开时创建应落待审批: {state}"
        expect(page.locator('[data-test="reservation-pending-badge"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="reservation-block"]').filter(has_text="待审批").first.click()
        drawer = page.locator('[data-test="reservation-detail-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        drawer.locator('[data-test="reservation-approve-btn"]').click()
        expect(drawer.locator('[data-test="reservation-approve-btn"]')).to_have_count(0, timeout=10000)
        state = sql_query(f"SELECT state FROM tp_appauto_reservation WHERE id = {r2}")
        assert state == "ACTIVE", f"批准后应转 ACTIVE: {state}"
        n_ok = sql_query(
            "SELECT count(*) FROM tp_notification WHERE dedup_key = "
            f"'RESERVATION_APPROVED:{r2}'")
        assert n_ok == "1", f"批准回执站内信缺失: {n_ok}"
        expect(page.locator('[data-test="reservation-pending-badge"]')).to_have_count(0, timeout=10000)
        _set_sys_config_value(APPROVAL_KEY, "0")
        approval_on = False

        admin_id = int(sql_query(
            "SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
        batch_id = int(sql_query(
            "INSERT INTO tp_exec_batch (project_id, name, batch_no, status, type, created_by) "
            f"VALUES ({pid}, '预约绑定批{uniq}', 'RSB{uniq.upper()}', 1, 1, {admin_id}) "
            "RETURNING id").splitlines()[0])
        page.goto(f"{BASE_URL}/#/batches/{batch_id}", wait_until="domcontentloaded")
        card = page.locator('[data-test="batch-bind-reservation-card"]')
        expect(card).to_be_visible(timeout=15000)
        card.locator('[data-test="batch-bind-pick"]').click()
        bind_opt = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").first
        expect(bind_opt).to_be_visible(timeout=5000)
        bind_opt.click()
        card.locator('[data-test="batch-bind-submit"]').click()
        expect(card.locator('[data-test="batch-bind-row"]')).to_have_count(1, timeout=10000)
        bound_id = sql_query(
            "SELECT id FROM tp_appauto_reservation WHERE batch_id = "
            f"{batch_id} AND project_id = {pid}").splitlines()[0]
        assert bound_id, "绑定后库内 batch_id 未落（假绿）"

        _reopen_calendar(page, pid)  # same re-navigation prerequisite: reload the batch page itself first
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/batches/{batch_id}", wait_until="domcontentloaded")
        expect(card.locator('[data-test="batch-bind-row"]')).to_have_count(1, timeout=15000)
        card.locator('[data-test="batch-bind-unbind-btn"]').first.click()
        expect(card.locator('[data-test="batch-bind-empty"]')).to_be_visible(timeout=10000)
        assert sql_query(
            f"SELECT count(*) FROM tp_appauto_reservation WHERE batch_id = {batch_id}") == "0"

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/api/v1/appauto/reservation" in e["url"] and e["status"] == 409)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 409" not in c]
        assert_no_errors(collectors, "T340 设备预约日历 E2E")
    finally:
        if approval_on:
            with contextlib.suppress(Exception):
                _set_sys_config_value(APPROVAL_KEY, "0")
        if pid is not None:
            sql_query(
                f"DELETE FROM tp_appauto_reservation WHERE project_id = {pid}"
                if res_ids else "SELECT 1")
        if res_ids:
            keys = ", ".join(f"'RESERVATION_APPROVED:{i}'" for i in res_ids)
            with contextlib.suppress(Exception):
                sql_query(f"DELETE FROM tp_notification WHERE dedup_key IN ({keys})")
        if batch_id is not None:
            sql_query(f"DELETE FROM tp_exec_batch WHERE id = {batch_id}")
        for dev_id in dev_ids:
            with contextlib.suppress(Exception):
                api_client.delete(f"/appauto/devices/{dev_id}")
        if pid is not None:
            with contextlib.suppress(Exception):
                api_client.delete(f"/projects/{pid}/purge?force=true")
