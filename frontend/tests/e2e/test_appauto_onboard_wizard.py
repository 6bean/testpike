
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")


def sql(q):
    import subprocess
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
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


def test_appauto_onboard_wizard(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    suffix = uuid.uuid4().hex[:6]
    proj_name = f"开通向导E2E项目{suffix}"
    serial = f"obw-e2e-{suffix}"
    dev_name = f"开通向导E2E机{suffix}"
    pid = None
    try:
        pid = api.post("/api/v1/projects",
                       json={"name": proj_name, "code": f"OBW{suffix}"}).json()["data"]

        open_journey_leaf(page, "menu-device-wall", "appauto")
        expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(timeout=10000)
        # : page-level selector removed — deep link lands the project segment;
        # dashboard bounce forces a remount (query-only nav reuses the component).
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/appauto/devices?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="device-wall-page"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="device-wall-onboard-btn"]').click()
        wiz = page.locator('[data-test="onboard-wizard"]')
        expect(wiz).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="onboard-wizard"] .el-step')).to_have_count(4)

        step1 = page.locator('[data-test="onboard-step-platform"]')
        expect(step1).to_be_visible()
        cards = page.locator('[data-test="onboard-platform-cards"] .obw__card')
        expect(cards.first).to_be_visible(timeout=10000)
        page.locator('[data-test="onboard-next"]').click()
        expect(step1).to_be_visible(timeout=3000)
        expect(page.locator('.el-message')).to_contain_text("请先选择一个平台", timeout=5000)
        cards.first.click()
        page.locator('[data-test="onboard-next"]').click()

        step2 = page.locator('[data-test="onboard-step-device"]')
        expect(step2).to_be_visible(timeout=8000)
        page.locator('[data-test="onboard-form-name"]').fill(dev_name)
        page.locator('[data-test="onboard-form-serial"]').fill(serial)
        page.locator('[data-test="onboard-next"]').click()
        expect(step2).to_be_visible(timeout=3000)
        page.locator('[data-test="onboard-form-adb"]').fill("127.0.0.1:5037")
        page.locator('[data-test="onboard-next"]').click()

        step3 = page.locator('[data-test="onboard-step-engine"]')
        expect(step3).to_be_visible(timeout=8000)
        compat = page.locator('[data-test="onboard-compat-tag"]')
        expect(compat.first).to_be_visible(timeout=8000)
        step3.locator('.obw__card').first.click()
        page.locator('[data-test="onboard-next"]').click()
        step4 = page.locator('[data-test="onboard-step-tryrun"]')
        expect(step4).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="onboard-sum-device"]')).to_contain_text(serial)
        expect(page.locator('[data-test="onboard-sum-engine"]')).to_contain_text("引擎：")
        assert int(sql("SELECT count(*) FROM tp_appauto_engine WHERE status=1")) > 0

        page.locator('[data-test="onboard-done"]').click()
        expect(wiz).to_be_hidden(timeout=8000)
        row = sql(f"SELECT count(*) FROM tp_appauto_device WHERE project_id={pid} AND serial='{serial}'")
        assert row == "1", f"向导登记设备未落库: {row}"
        # : dashboard bounce again — same-route query nav would reuse the
        # mounted component and skip the deep-link setup.
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/appauto/devices?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="device-wall-table"] tbody tr').filter(has_text=dev_name)).to_have_count(1, timeout=10000)

        del_row = page.locator('tbody tr').filter(has_text=dev_name).first
        del_row.locator('[data-test="device-wall-delete-btn"]').click()
        confirm = page.locator('.el-message-box')
        expect(confirm).to_be_visible(timeout=5000)
        confirm.locator('button', has_text="删除").click()
        expect(page.locator('tbody tr').filter(has_text=dev_name)).to_have_count(0, timeout=8000)

        assert_no_errors(collectors, "appauto onboard wizard")
    finally:
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_appauto_device WHERE project_id={pid}") == "0"
