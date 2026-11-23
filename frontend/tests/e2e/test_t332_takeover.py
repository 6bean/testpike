
import contextlib
import json
import time

import pytest
from conftest import (
    assert_no_errors,
    inject_token,
    nav_to,
    redis_cli,
    sql_query,
)
from playwright.sync_api import expect
# Frame payload reuses the monitor-window fixture byte-for-byte (single source).
from test_t304_monitor_window import _JPEG_B64

pytestmark = pytest.mark.timeout(900)

EXECUTOR_ID = 900001


def _push_frame(task_id: int) -> None:
    key = f"appauto-monitor:frames:{task_id}"
    frame = json.dumps({"taskId": task_id, "stepNo": 1,
                        "image": _JPEG_B64, "sensitive": False})
    redis_cli("LPUSH", key, frame)
    redis_cli("EXPIRE", key, "300")


def _input_key(request_id: int) -> str:
    return f"appauto-takeover:input:{request_id}"


def _seed_occupied_task(api_client, pid: int, dev_id: int, uniq: str) -> int:
    pass
    admin_id = int(sql_query("SELECT id FROM tp_user WHERE username='admin'").splitlines()[0])
    batch_id = int(sql_query(
        "INSERT INTO tp_exec_batch (project_id, name, batch_no, status, type, created_by) "
        f"VALUES ({pid}, '接管批次{uniq}', 'TKB{uniq}', 1, 1, {admin_id}) RETURNING id"
    ).splitlines()[0])
    sql_query(
        "INSERT INTO tp_appauto_lease_run (device_id, project_id, batch_id, holder_executor_id, "
        "state, expires_at) "
        f"VALUES ({dev_id}, {pid}, {batch_id}, '{EXECUTOR_ID}', 'ACTIVE', now() + interval '30 minutes')"
    )
    tid = int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, schema_version, "
        "executor_id, steps_timeline) "
        f"VALUES ({pid}, 0, 2, 6, 'appauto-v1', {EXECUTOR_ID}, "
        "'[{\"stepNo\": 1, \"action\": \"app.tap\"}]'::jsonb) RETURNING id"
    ).splitlines()[0])
    return tid


def _open_task_detail(page, proj_name: str, tid: int):
    inject_token(page)
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=proj_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    table = page.locator('[data-test="exec-task-table"]')
    expect(table).to_be_visible(timeout=15000)
    row = table.locator("tbody tr").filter(has_text=f"#{tid}").first
    expect(row).to_be_visible(timeout=20000)
    row.locator('[data-test="exec-task-detail"]').click()
    dlg = page.locator('[data-test="exec-detail-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    return dlg


def test_t332_takeover_web_operations(page, api_client, collectors, uniq):
    pass
    pid = None
    tid = None
    dev_id = None
    tk_id = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"接管{uniq}", "code": f"TKP{uniq.upper()}"}).json()["data"]
        adb_port = 50000 + (ord(uniq[0]) * 97 + ord(uniq[1]) * 13) % 9999
        r = api_client.post("/appauto/devices", json={
            "projectId": pid, "name": f"接管设备{uniq}", "serial": f"TKS{uniq.upper()}",
            "deviceType": "ADB", "adbAddress": f"127.0.0.1:{adb_port}", "platform": "ANDROID"})
        assert r.status_code == 200 and r.json().get("code") == "0", f"注册设备失败: {r.text[:300]}"
        dev_id = int(r.json()["data"])
        tid = _seed_occupied_task(api_client, pid, dev_id, uniq)
        _push_frame(tid)

        dlg = _open_task_detail(page, f"接管{uniq}", tid)
        win = dlg.locator('[data-test="exec-monitor-window"]')
        expect(win.locator('[data-test="monitor-frame"]')).to_be_visible(timeout=15000)

        zone = win.locator('[data-test="takeover-zone"]')
        expect(zone).to_be_visible(timeout=15000)

        zone.locator('[data-test="takeover-reason-input"]').fill(f"E2E联调借用{uniq}")
        zone.locator('[data-test="takeover-request-btn"]').click()
        expect(zone.locator('[data-test="takeover-pending-wait"]')).to_be_visible(timeout=10000)

        row = sql_query(
            "SELECT id, state, occupier_id FROM tp_appauto_takeover_request "
            f"WHERE device_id = {dev_id} ORDER BY id DESC LIMIT 1")
        parts = row.split("|")
        tk_id = int(parts[0])
        assert parts[1] == "pending", f"申请后应为 pending: {row}"
        n_req = sql_query(
            f"SELECT count(*) FROM tp_notification WHERE dedup_key = 'TAKEOVER_REQ:{tk_id}'")
        assert n_req == "1", f"占用者 TAKEOVER_REQUEST 站内信缺失: {n_req}"

        agree = zone.locator('[data-test="takeover-agree-btn"]')
        expect(agree).to_be_visible(timeout=5000)
        agree.click()
        expect(zone.locator('[data-test="takeover-active-hint"]')).to_be_visible(timeout=10000)
        state = sql_query(f"SELECT state FROM tp_appauto_takeover_request WHERE id = {tk_id}")
        assert state == "active", f"同意后应为 active: {state}"
        n_dec = sql_query(
            f"SELECT count(*) FROM tp_notification WHERE dedup_key = 'TAKEOVER_DECIDED:{tk_id}'")
        assert n_dec == "1", f"申请人回执站内信缺失: {n_dec}"

        img = win.locator('[data-test="monitor-frame"]')
        box = img.bounding_box()
        assert box is not None and box["width"] > 0
        cx, cy = box["x"] + box["width"] / 2, box["y"] + box["height"] / 2
        page.mouse.move(cx, cy)
        page.mouse.down()
        page.mouse.up()
        key = _input_key(tk_id)

        def _wait_queue(n: int) -> list:
            deadline = time.time() + 15
            while time.time() < deadline:
                raw = redis_cli("LRANGE", key, "0", "-1")
                lines = [x for x in raw.splitlines() if x.strip()]
                if len(lines) >= n:
                    return lines
                time.sleep(0.5)
            return [x for x in redis_cli("LRANGE", key, "0", "-1").splitlines() if x.strip()]

        cmds = _wait_queue(1)
        tap = json.loads(cmds[0])
        assert tap["verb"] == "tap" and tap["platform"] == "ANDROID", f"tap 指令体异常: {cmds[0]}"
        assert 0 <= tap["x"] <= 10000 and 0 <= tap["y"] <= 10000

        zone.locator('[data-test="takeover-key-back-btn"]').click()
        zone.locator('[data-test="takeover-text-input"]').fill(f"E2E文本{uniq}")
        zone.locator('[data-test="takeover-text-send"]').click()
        cmds = _wait_queue(3)
        verbs = [json.loads(c)["verb"] for c in cmds]
        assert verbs == ["tap", "key", "text"], f"队列 FIFO 三类指令不符: {verbs}"
        key_cmd = json.loads(cmds[1])
        assert key_cmd["keycode"] == 4, f"Back 键码错: {cmds[1]}"
        assert uniq in json.loads(cmds[2])["text"]


        zone.locator('[data-test="takeover-end-btn"]').click()
        expect(zone.locator('[data-test="takeover-request-btn"]')).to_be_visible(timeout=10000)
        state = sql_query(f"SELECT state FROM tp_appauto_takeover_request WHERE id = {tk_id}")
        assert state == "ended", f"结束后应为 ended: {state}"
        leftover = redis_cli("EXISTS", key)
        assert leftover == "0", "结束未清残留输入队列（安全红线破口）"

        assert_no_errors(collectors, "T332 设备接管网页操作 E2E")
    finally:
        if tk_id is not None:
            redis_cli("DEL", _input_key(tk_id))
            sql_query(f"DELETE FROM tp_appauto_takeover_request WHERE id = {tk_id}")
            sql_query(
                f"DELETE FROM tp_notification WHERE dedup_key IN ('TAKEOVER_REQ:{tk_id}',"
                f" 'TAKEOVER_DECIDED:{tk_id}')")
        if tid is not None:
            redis_cli("DEL", f"appauto-monitor:frames:{tid}", f"appauto-monitor:meta:{tid}")
            sql_query(f"DELETE FROM tp_exec_task WHERE id = {tid}")
        sql_query(
            f"DELETE FROM tp_appauto_lease_run WHERE holder_executor_id = '{EXECUTOR_ID}'"
            f" AND project_id = {pid}" if pid is not None else "SELECT 1")
        if pid is not None:
            sql_query(f"DELETE FROM tp_exec_batch WHERE project_id = {pid}")
        if dev_id is not None:
            with contextlib.suppress(Exception):
                api_client.delete(f"/appauto/devices/{dev_id}")
        if pid is not None:
            with contextlib.suppress(Exception):
                api_client.delete(f"/projects/{pid}/purge?force=true")
