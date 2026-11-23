
import json
import os
import subprocess
import time

import pytest
from conftest import BASE_URL, assert_no_errors, inject_token, redis_cli, sql_query
from playwright.sync_api import expect

from test_t304_monitor_window import _JPEG_B64

pytestmark = pytest.mark.timeout(420)

FRAMES_KEY = "appauto-monitor:frames:{}"
META_KEY = "appauto-monitor:meta:{}"
THUMB_CACHE_KEY = "appauto-wall:thumb:{}"
_REDIS_DB = os.environ.get("REDIS_DATABASE", "0")


def _mk_task(api_client, pid: int, uniq: str, device_id: int, serial: str) -> int:
    pass
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"墙缩略用例{uniq}", "precondition": "无",
        "expectedResult": "OK", "priority": 1, "confidence": 2, "endType": "web",
        "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "缩略", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    envelope = json.dumps({
        "kind": "app-auto-run",
        "leaseRef": {"deviceId": device_id, "serial": serial},
    }, ensure_ascii=False).replace("'", "''")
    tid = int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json, started_at)"
        f" VALUES ({pid}, {case_id}, 2, 1, '{envelope}', now()) RETURNING id").splitlines()[0])
    return tid


def _device_id(serial: str) -> int:
    row = sql_query(f"SELECT id FROM tp_appauto_device WHERE serial='{serial}'")
    assert row.splitlines()[0].isdigit(), f"设备行未落库: {row}"
    return int(row.splitlines()[0])


def _row(page, dev_name: str):
    return page.locator("tbody tr").filter(has_text=dev_name).first


def test_device_wall_thumbnail_three_states(page, api_client, collectors, uniq):
    pass
    inject_token(page)
    pid = api_client.post("/projects", json={
        "name": f"墙缩略项目{uniq}", "code": f"TW{uniq[-4:]}{time.strftime('%M%S')}"}).json()["data"]
    live_tid = noframe_tid = None
    device_ids = []
    serials = {k: f"wt-{k}-{uniq}" for k in ("live", "noframe", "idle", "flaky")}
    try:
        for kind in ("live", "noframe", "idle", "flaky"):
            r = api_client.post("/appauto/devices", json={
                "projectId": pid, "name": f"墙缩略机{kind}{uniq}", "serial": serials[kind],
                "deviceType": "ADB", "adbAddress": "127.0.0.1:5555", "labels": []})
            assert r.status_code == 200, f"设备注册失败: {r.status_code} {r.text[:200]}"
        dev_live = _device_id(serials["live"])
        dev_noframe = _device_id(serials["noframe"])
        dev_flaky = _device_id(serials["flaky"])
        device_ids = [dev_live, dev_noframe, _device_id(serials["idle"]), dev_flaky]

        live_tid = _mk_task(api_client, pid, uniq, dev_live, serials["live"])
        noframe_tid = _mk_task(api_client, pid, uniq, dev_noframe, serials["noframe"])
        frame = json.dumps({"taskId": live_tid, "stepNo": 1, "image": _JPEG_B64, "sensitive": False})
        redis_cli("LPUSH", FRAMES_KEY.format(live_tid), frame)
        redis_cli("EXPIRE", FRAMES_KEY.format(live_tid), "120")

        injected_url = f"**/api/v1/appauto/devices/{dev_flaky}/thumbnail*"

        def _fault(route):
            route.fulfill(status=503, content_type="text/plain", body="injected fault")

        page.route(injected_url, _fault)
        page.goto(f"{BASE_URL}/#/appauto/devices?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(timeout=15000)

        expect(_row(page, f"墙缩略机idle{uniq}").locator('[data-test="device-wall-thumb-idle"]'))\
            .to_contain_text("无在跑任务", timeout=20000)

        expect(_row(page, f"墙缩略机noframe{uniq}").locator('[data-test="device-wall-thumb-idle"]'))\
            .to_contain_text(f"任务 #{noframe_tid}", timeout=20000)

        img = _row(page, f"墙缩略机live{uniq}").locator('[data-test="device-wall-thumb"]')
        expect(img).to_be_visible(timeout=20000)
        assert img.get_attribute("src", timeout=5000).startswith("data:image")
        assert img.evaluate("el => el.complete && el.naturalWidth > 0"), "LIVE 帧未真正解码渲染"

        expect(_row(page, f"墙缩略机flaky{uniq}").locator('[data-test="device-wall-thumb-failed"]'))\
            .to_be_visible(timeout=20000)

        page.unroute(injected_url)
        expect(_row(page, f"墙缩略机flaky{uniq}").locator('[data-test="device-wall-thumb-idle"]'))\
            .to_contain_text("无在跑任务", timeout=25000)
        expect(_row(page, f"墙缩略机flaky{uniq}").locator('[data-test="device-wall-thumb-failed"]'))\
            .to_have_count(0)

        mine = [e for e in collectors["network_errors"]
                if f"/appauto/devices/{dev_flaky}/thumbnail" in e["url"]]
        assert len(mine) >= 1, "注入的受控 503 未被采集=故障从未发生（假监控）"
        foreign = [e for e in collectors["network_errors"] if e not in mine]
        assert not foreign, f"注入故障之外的真实 5xx: {foreign}"
        # Chromium logs a generic "Failed to load resource: ... status of 503"
        # console line for the injected fault (same noise the shared 4xx helper
        # in conftest drops for business rejects). Attribute it here: the number
        # of such lines must match the attributed network occurrences, so a
        # console line from any *other* 503 source can never pass silently.
        console_503 = [c for c in collectors["console_errors"]
                       if "status of 503" in c]
        assert len(console_503) == len(mine), (
            f"503 console 行 {len(console_503)} 条 != 注入归因 {len(mine)} 条"
            f"（多余=外部故障 缺失=假监控）: {console_503}")
        collectors["network_errors"] = []
        collectors["console_errors"] = [c for c in collectors["console_errors"]
                                        if "status of 503" not in c]
        assert_no_errors(collectors, "device wall thumbnail three states")
    finally:
        if live_tid:
            redis_cli("DEL", FRAMES_KEY.format(live_tid), META_KEY.format(live_tid))
        for did in device_ids:
            redis_cli("DEL", THUMB_CACHE_KEY.format(did))
        for tid in (live_tid, noframe_tid):
            if tid:
                sql_query(f"DELETE FROM tp_exec_task WHERE id={tid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        assert sql_query(
            f"SELECT count(*) FROM tp_appauto_device WHERE project_id={pid}") == "0"
