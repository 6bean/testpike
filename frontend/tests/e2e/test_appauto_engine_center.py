
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


def test_appauto_engine_center_page(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)

    snap = sql("SELECT name, default_priority, COALESCE(install_doc_url,''), status, license"
               " FROM tp_appauto_engine WHERE code='HYPIUM'")
    assert snap, "候补位引擎行缺失（目录未种子？）"
    s_name, s_prio, s_doc, s_status, _lic = snap.split("|")
    p_note0 = sql("SELECT COALESCE(note,'') FROM tp_appauto_platform WHERE code='ANDROID'")

    try:
        open_journey_leaf(page, "menu-engine-center", "appauto")
        expect(page.locator('[data-test="engine-center-page"]')).to_be_visible(timeout=10000)

        grid = page.locator('[data-test="engine-center-grid"]')
        expect(grid).to_be_visible(timeout=10000)
        mw_card = page.locator('[data-test="engine-card-MOBILEWRIGHT"]')
        expect(mw_card).to_contain_text("Mobilewright")
        expect(mw_card).to_contain_text("Android")
        expect(mw_card.locator('[data-test="engine-driver-reqs"]')).to_be_visible()
        expect(page.locator('[data-test="engine-card-HYPIUM"]')).to_have_count(0)

        page.locator('[data-test="engine-center-show-disabled"]').click()
        expect(page.locator('[data-test="engine-card-HYPIUM"]')).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="engine-card-HYPIUM"]')).to_contain_text("已下架")

        suffix = uuid.uuid4().hex[:6]
        new_name = f"Hypium候补改{suffix}"
        new_prio = int(s_prio) + 7
        page.locator('[data-test="engine-card-HYPIUM"] [data-test="engine-manage-btn"]').click()
        drawer = page.locator('[data-test="engine-manage-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        name_input = page.locator('[data-test="engine-form-name"]')
        expect(name_input).to_have_value("Hypium（华为官方，候补位）")
        name_input.fill(new_name)
        prio_input = page.locator('[data-test="engine-form-priority"] input')
        prio_input.fill(str(new_prio))
        page.locator('[data-test="engine-drawer-save"]').click()
        expect(drawer).to_be_hidden(timeout=8000)
        assert sql("SELECT name FROM tp_appauto_engine WHERE code='HYPIUM'") == new_name
        page.goto(f"{BASE_URL}/#/appauto/engines", wait_until="domcontentloaded")
        expect(page.locator('[data-test="engine-center-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="engine-center-skeleton"]')).to_be_hidden(timeout=15000)
        cb = page.locator('[data-test="engine-center-show-disabled"]')
        expect(cb).to_be_visible(timeout=10000)
        cb.locator("input").check()
        expect(cb.locator("input")).to_be_checked()
        expect(page.locator('[data-test="engine-card-HYPIUM"]')).to_contain_text(new_name, timeout=8000)
        page.locator('[data-test="engine-card-HYPIUM"] [data-test="engine-manage-btn"]').click()
        expect(drawer).to_be_visible()
        page.locator('[data-test="engine-form-name"]').fill("   ")
        page.locator('[data-test="engine-drawer-save"]').click()
        expect(drawer).to_be_visible(timeout=3000)
        page.keyboard.press("Escape")
        expect(drawer).to_be_hidden(timeout=5000)

        pt = page.locator('[data-test="engine-platform-table"]')
        expect(pt).to_be_visible(timeout=8000)
        expect(pt).to_contain_text("HarmonyOS NEXT")
        note_new = f"E2E引导文案{suffix}（恢复原值见 teardown）"
        row = pt.locator("tbody tr").filter(has_text="ANDROID").first
        row.locator('[data-test="platform-manage-btn"]').click()
        pd = page.locator('[data-test="platform-manage-drawer"]')
        expect(pd).to_be_visible(timeout=8000)
        page.locator('[data-test="platform-form-note"]').fill(note_new)
        page.locator('[data-test="platform-drawer-save"]').click()
        expect(pd).to_be_hidden(timeout=8000)
        assert sql("SELECT note FROM tp_appauto_platform WHERE code='ANDROID'") == note_new
        page.keyboard.press("Escape")

        page.locator('[data-test="engine-center-health-btn"]').click()
        panel = page.locator('[data-test="engine-health-panel"]')
        expect(panel).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="engine-health-row-MOBILEWRIGHT"]')).to_be_visible()
        expect(page.locator('[data-test="engine-health-row-HYPIUM"]')).to_contain_text("已下架")
        expect(page.locator('[data-test="engine-health-summary"]')).to_contain_text("全绿")
        expect(page.locator('[data-test="engine-health-ai-note"]')).to_contain_text("ai.appauto.env-doctor")
        expect(page.locator('[data-test="engine-health-caliber"]')).to_contain_text("覆盖缺口")
        resp = api.get("/api/v1/appauto/engines/health", params={"includeDisabled": "true"})
        assert resp.status_code == 200, f"体检口响应码异常: {resp.status_code}"
        rows = resp.json()["data"]
        codes = {r["code"] for r in rows}
        assert "MOBILEWRIGHT" in codes and "HYPIUM" in codes, f"体检行缺位: {codes}"
        for r in rows:
            assert set(r.keys()) == {"code", "name", "enabled", "onlineExecutors",
                                     "readyExecutors", "blockingItems", "aiDoctorEnabled"}, \
                f"体检视图字段镜像漂移: {sorted(r.keys())}"
            assert r["aiDoctorEnabled"] is False, "V154 键默认关，桥面禁伪造开"

        assert_no_errors(collectors, "appauto engine center page")
    finally:
        api.put("/api/v1/appauto/engines/HYPIUM", json={
            "name": s_name, "defaultPriority": int(s_prio),
            "installDocUrl": s_doc or None, "status": int(s_status)})
        api.put("/api/v1/appauto/platforms/ANDROID", json={"note": p_note0 or None, "status": 1})
        assert sql("SELECT name, default_priority, status FROM tp_appauto_engine WHERE code='HYPIUM'") \
            == f"{s_name}|{s_prio}|{s_status}", "引擎行复原失败=目录被污染"
        assert sql("SELECT COALESCE(note,'') FROM tp_appauto_platform WHERE code='ANDROID'") == p_note0, \
            "平台 note 复原失败=目录被污染"
