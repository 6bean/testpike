
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _pick_project(page, pid):
    """: page-level selector removed — ?projectId deep link lands the segment.
 Dashboard bounce forces a remount (query-only navigation reuses the component)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/devices?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(timeout=15000)


def test_appauto_device_wall_page(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    suffix = uuid.uuid4().hex[:6]
    proj_name = f"设备墙E2E项目{suffix}"
    serial = f"dw-e2e-{suffix}"
    dev_name = f"设备墙E2E机{suffix}"
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    pid = dev_id = None
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": proj_name, "code": f"DWE{suffix}"}).json()["data"]

        open_journey_leaf(page, "menu-device-wall", "appauto")
        expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(timeout=10000)

        _pick_project(page, pid)
        expect(page.locator('[data-test="device-wall-table"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="device-wall-create-btn"]').click()
        dlg = page.locator('[data-test="device-wall-dialog"]')
        expect(dlg).to_be_visible()
        page.locator('[data-test="device-wall-dialog-save"]').click()
        expect(dlg).to_be_visible(timeout=3000)
        page.locator('[data-test="device-wall-form-name"]').fill(dev_name)
        page.locator('[data-test="device-wall-form-serial"]').fill(serial)
        page.locator('[data-test="device-wall-dialog-save"]').click()
        expect(dlg).to_be_visible(timeout=3000)
        page.locator('[data-test="device-wall-form-adb"]').fill("127.0.0.1:5555")
        page.locator('[data-test="device-wall-dialog-save"]').click()
        expect(dlg).to_be_hidden(timeout=8000)
        row = sql(f"SELECT id FROM tp_appauto_device WHERE project_id={pid} AND serial='{serial}'")
        assert row.isdigit(), f"设备行未落库: {row}"
        dev_id = int(row)

        page.goto(f"{BASE_URL}/#/appauto/devices", wait_until="domcontentloaded")
        _pick_project(page, pid)
        expect(page.locator('[data-test="device-wall-table"] tbody tr').filter(has_text=dev_name)).to_have_count(1, timeout=10000)

        executor_id = f"dw-e2e-exec-{suffix}"
        sql("INSERT INTO tp_appauto_lease_run (device_id, project_id, batch_id, holder_executor_id,"
            " state, acquired_at, expires_at)"
            f" VALUES ({dev_id}, {pid}, NULL, '{executor_id}', 'ACTIVE', now(), now() + interval '30 minutes')"
            " RETURNING id")
        page.locator('[data-test="device-wall-refresh"]').click()
        occ = page.locator('[data-test="device-wall-occupancy"]')
        expect(occ.first).to_be_visible(timeout=10000)
        expect(occ.first).to_contain_text(executor_id)
        serial2 = f"{serial}-b"
        api.post("/api/v1/appauto/devices", headers=h, json={
            "projectId": pid, "name": f"{dev_name}B", "serial": serial2,
            "deviceType": "USB", "labels": []})
        page.locator('[data-test="device-wall-refresh"]').click()
        expect(page.locator('[data-test="device-wall-occupancy-free"]').first).to_be_visible(timeout=10000)

        sql(f"UPDATE tp_appauto_lease_run SET state='RELEASED', released_at=now(),"
            f" release_reason='正常完成，App 会话收口归还设备' WHERE holder_executor_id='{executor_id}'")
        page.locator('[data-test="device-wall-refresh"]').click()
        occupied_row = page.locator('tbody tr').filter(has_text=dev_name).first
        occupied_row.locator('[data-test="device-wall-lease-btn"]').click()
        drawer = page.locator('[data-test="device-wall-lease-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        lease_table = page.locator('[data-test="device-wall-lease-table"]')
        expect(lease_table).to_contain_text(executor_id, timeout=8000)
        expect(lease_table).to_contain_text("正常完成，App 会话收口归还设备")
        page.keyboard.press("Escape")

        edit_row = page.locator('tbody tr').filter(has_text=dev_name).first
        edit_row.locator('[data-test="device-wall-edit-btn"]').click()
        dlg2 = page.locator('[data-test="device-wall-dialog"]')
        expect(dlg2).to_be_visible()
        name_input = page.locator('[data-test="device-wall-form-name"]')
        expect(name_input).to_have_value(dev_name)
        assert page.locator('[data-test="device-wall-form-serial"]').get_attribute("disabled") is not None \
            or "is-disabled" in (page.locator('[data-test="device-wall-form-serial"]').evaluate("e => e.className") or "")
        new_name = f"{dev_name}改"
        name_input.fill(new_name)
        page.locator('[data-test="device-wall-dialog-save"]').click()
        expect(dlg2).to_be_hidden(timeout=8000)
        assert sql(f"SELECT name FROM tp_appauto_device WHERE id={dev_id}") == new_name
        page.goto(f"{BASE_URL}/#/appauto/devices", wait_until="domcontentloaded")
        _pick_project(page, pid)
        expect(page.locator('tbody tr').filter(has_text=new_name)).to_have_count(1, timeout=10000)

        del_row = page.locator('tbody tr').filter(has_text=new_name).first
        del_row.locator('[data-test="device-wall-delete-btn"]').click()
        confirm = page.locator('.el-message-box')
        expect(confirm).to_be_visible(timeout=5000)
        confirm.locator('button', has_text="删除").click()
        expect(page.locator('tbody tr').filter(has_text=new_name)).to_have_count(0, timeout=8000)
        assert sql(f"SELECT count(*) FROM tp_appauto_device WHERE id={dev_id}") == "0"

        assert_no_errors(collectors, "appauto device wall page")
    finally:
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_appauto_device WHERE project_id={pid}") == "0"
            assert sql(f"SELECT count(*) FROM tp_appauto_lease_run WHERE project_id={pid}") == "0"
