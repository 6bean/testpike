
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, TESTER_PASSWORD, api_login, assert_no_errors, mk_tester, add_member

pytestmark = pytest.mark.timeout(300)

BASE = "http://localhost:5173"
API = "http://localhost:8081"
CORE = "http://localhost:8083"


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject(page, username, password):
    token = api_login(username, password)
    page.goto(f"{BASE}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE}/#/dashboard", wait_until="domcontentloaded")
    return token


def pick_project(page, pid):
    """: page-level selector removed — ?projectId deep link lands the segment.
 Dashboard bounce forces a remount (query-only navigation reuses the component)."""
    page.goto(f"{BASE}/#/dashboard", wait_until="domcontentloaded")
    page.locator('[data-test="my-project-table"]').first.wait_for(state="visible", timeout=15000)
    page.goto(f"{BASE}/#/appauto/devices?projectId={pid}", wait_until="domcontentloaded")
    page.locator('[data-test="device-wall-page"]').wait_for(state="visible", timeout=15000)
    tbl = page.locator('[data-test="device-wall-table"]')
    tbl.wait_for(state="visible", timeout=10000)


def go_wall(page):
    page.goto(f"{BASE}/#/appauto/devices", wait_until="domcontentloaded")
    page.locator('[data-test="device-wall-page"]').wait_for(state="visible", timeout=10000)


def test_appauto_device_shared_slot(page, collectors):
    suffix = uuid.uuid4().hex[:6]
    proj_a = f"共享位A项目{suffix}"
    proj_b = f"共享位B项目{suffix}"
    serial = f"ss-e2e-{suffix}"
    dev_name = f"共享位E2E机{suffix}"
    admin_h = {"Authorization": f"Bearer {api_login('admin', ADMIN_PASSWORD)}"}
    api = httpx.Client(base_url=f"{API}/api/v1", headers=admin_h, timeout=30)
    pid_a = pid_b = dev_id = uid = None
    lease = httpx.Client(base_url=CORE, timeout=30, headers={"X-Operator-Id": "1"})
    try:
        pid_a = api.post("/projects", json={"name": proj_a, "code": f"SSA{suffix}"}).json()["data"]
        pid_b = api.post("/projects", json={"name": proj_b, "code": f"SSB{suffix}"}).json()["data"]
        uid, _nick = mk_tester(api, suffix, prefix="ss")
        uname = f"sstester_{suffix}"
        add_member(api, pid_b, uid)

        inject(page, "admin", ADMIN_PASSWORD)
        go_wall(page)
        pick_project(page, pid_a)
        page.locator('[data-test="device-wall-create-btn"]').click()
        dlg = page.locator('[data-test="device-wall-dialog"]')
        dlg.wait_for(state="visible", timeout=8000)
        sw = page.locator('[data-test="device-wall-form-shared"]')
        sw.wait_for(state="visible", timeout=5000)
        page.locator('[data-test="device-wall-form-name"]').fill(dev_name)
        page.locator('[data-test="device-wall-form-serial"]').fill(serial)
        page.locator('[data-test="device-wall-form-adb"]').fill("127.0.0.1:5555")
        sw.click()
        page.locator('[data-test="device-wall-dialog-save"]').click()
        dlg.wait_for(state="hidden", timeout=8000)
        row = sql(f"SELECT id FROM tp_appauto_device WHERE serial='{serial}'")
        assert row.isdigit(), f"共享档案未落库: {row}"
        dev_id = int(row)
        assert sql(f"SELECT project_id FROM tp_appauto_device WHERE id={dev_id}") == "", \
            "勾选共享位后 project_id 应为空"
        badge = page.locator('tbody tr').filter(has_text=dev_name).locator('[data-test="device-wall-shared-badge"]')
        badge.first.wait_for(state="visible", timeout=8000)
        assert "跨项目共享" in badge.first.inner_text()

        r1 = lease.post(f"/internal/appauto/devices/{dev_id}/lease",
                        params={"projectId": pid_a, "holderExecutorId": f"exec-A-{suffix}"})
        assert r1.status_code == 200, f"A 借用占机失败: {r1.text[:120]}"
        r2 = lease.post(f"/internal/appauto/devices/{dev_id}/lease",
                        params={"projectId": pid_b, "holderExecutorId": f"exec-B-{suffix}"})
        msg2 = r2.json().get("msg", "") if r2.headers.get("content-type", "").startswith("application") else ""
        assert r2.status_code == 409 and "持有" in msg2, f"B 占用应 409 点名持有者: st={r2.status_code} msg={msg2[:80]}"

        t_token = api_login(uname, TESTER_PASSWORD)
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{t_token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE}/#/appauto/devices", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        page.locator('[data-test="device-wall-page"]').wait_for(state="visible", timeout=15000)
        pick_project(page, pid_b)
        brow = page.locator('tbody tr').filter(has_text=dev_name)
        brow.first.wait_for(state="visible", timeout=10000)
        brow.locator('[data-test="device-wall-shared-badge"]').first.wait_for(state="visible", timeout=5000)
        occ = brow.locator('[data-test="device-wall-occupancy"]')
        occ.first.wait_for(state="visible", timeout=10000)
        assert f"exec-A-{suffix}" in occ.first.inner_text(), "占用列应点名 exec-A"
        assert brow.locator('[data-test="device-wall-edit-btn"]').count() == 0, "共享行对非管理员不应有编辑钮"
        assert brow.locator('[data-test="device-wall-delete-btn"]').count() == 0, "共享行对非管理员不应有删除钮"
        page.locator('[data-test="device-wall-create-btn"]').click()
        dlg2 = page.locator('[data-test="device-wall-dialog"]')
        dlg2.wait_for(state="visible", timeout=8000)
        assert page.locator('[data-test="device-wall-form-shared"]').count() == 0, "普通用户不应见共享位开关"
        page.keyboard.press("Escape")

        r3 = lease.post(f"/internal/appauto/devices/{dev_id}/lease/release",
                        params={"holderExecutorId": f"exec-A-{suffix}", "reason": "A 项目归还"})
        assert r3.status_code == 200, f"A 归还失败: {r3.text[:120]}"
        r4 = lease.post(f"/internal/appauto/devices/{dev_id}/lease",
                        params={"projectId": pid_b, "holderExecutorId": f"exec-B-{suffix}"})
        assert r4.status_code == 200, f"归还后 B 应可占: st={r4.status_code} {r4.text[:120]}"
        page.locator('[data-test="device-wall-refresh"]').click()
        occ2 = page.locator('tbody tr').filter(has_text=dev_name).locator('[data-test="device-wall-occupancy"]')
        occ2.first.wait_for(state="visible", timeout=10000)
        assert f"exec-B-{suffix}" in occ2.first.inner_text(), "刷新后占用列应换名 exec-B"

        page.goto(f"{BASE}/#/appauto/devices", wait_until="domcontentloaded")
        page.locator('[data-test="device-wall-page"]').wait_for(state="visible", timeout=10000)
        pick_project(page, pid_b)
        brow2 = page.locator('tbody tr').filter(has_text=dev_name)
        brow2.first.wait_for(state="visible", timeout=10000)
        brow2.locator('[data-test="device-wall-shared-badge"]').first.wait_for(state="visible", timeout=5000)

        assert_no_errors(collectors, "T331 设备共享位徽章+双项目互斥")
    finally:
        if dev_id:
            for holder in (f"exec-A-{suffix}", f"exec-B-{suffix}"):
                lease.post(f"/internal/appauto/devices/{dev_id}/lease/release",
                           params={"holderExecutorId": holder, "reason": "E2E 收口"})
        for pid in (pid_a, pid_b):
            if pid:
                api.delete(f"/projects/{pid}/purge", params={"force": "true"})
        if dev_id:
            api.delete(f"/appauto/devices/{dev_id}")
        if uid:
            api.delete(f"/users/{uid}")
        if dev_id:
            assert sql(f"SELECT count(*) FROM tp_appauto_device WHERE id={dev_id}") == "0", "共享档案残留"
            assert sql(f"SELECT count(*) FROM tp_appauto_lease_run WHERE device_id={dev_id}") == "0", "流水残留"
