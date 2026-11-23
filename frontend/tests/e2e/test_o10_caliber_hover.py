
import uuid

import httpx
from conftest import pick_project_in_header, API_BASE, BASE_URL, assert_no_errors
from playwright.sync_api import expect



def _mk_project(api):
    uniq = uuid.uuid4().hex[:8]
    name = f"O10口径_{uniq}"
    r = api.post("/projects", json={"name": name, "code": f"O10{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_case_reviewed(api, pid, title):
    r = api.post("/cases", json={"projectId": pid, "title": title, "precondition": "前置",
                                 "expectedResult": "通过", "priority": 1, "confidence": 2, "endType": "web"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]
    ver = api.get(f"/cases/{cid}").json()["data"]["version"]
    r = api.post(f"/cases/{cid}/review", json={"action": "pass", "version": ver})
    assert r.status_code == 200 and r.json().get("code") == "0", f"评审通过失败: {r.text[:300]}"


def test_o10_caliber_hover_tooltip(logged_in, api_client, collectors):
    pass
    page = logged_in
    pid, pname = _mk_project(api_client)
    _mk_case_reviewed(api_client, pid, "口径hover用例")
    try:
        r = httpx.get(f"{API_BASE}/reports/calibers", headers=api_client.headers, timeout=10)
        assert r.status_code == 200, f"/reports/calibers 非 200: {r.status_code}"
        calibers = {c["code"]: c["hoverText"] for c in r.json()["data"]}
        assert calibers.get("ai-accept-rate", "").startswith("AI 采纳率 v1.0="), \
            f"注册表 ai-accept-rate hoverText 异常: {calibers.get('ai-accept-rate')}"
        assert calibers.get("req-coverage", "").startswith("需求覆盖率 v1.0="), \
            f"注册表 req-coverage hoverText 异常: {calibers.get('req-coverage')}"

        page.goto(f"{BASE_URL}/#/report", wait_until="domcontentloaded")
        expect(page.locator('[data-test="report-page"]')).to_be_visible(timeout=15000)
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="personal-stats"]')).to_be_visible(timeout=15000)

        page.locator('.el-tabs__item:has-text("AI 质量")').click()
        ai_tab = page.get_by_role("tab", name="AI 质量")
        if "selected" not in (ai_tab.get_attribute("aria-selected") or ""):
            ai_tab.click()
        expect(ai_tab).to_have_attribute("aria-selected", "true", timeout=5000)
        ai_hover = page.locator('[data-test="ai-first-pass"] [data-test="caliber-hover"]')
        expect(ai_hover).to_be_visible(timeout=15000)
        assert ai_hover.get_attribute("data-caliber") == "ai-accept-rate"
        ai_hover.hover()
        tip = page.locator('.el-popper').filter(has_text="AI 采纳率 v1.0=").first
        expect(tip).to_be_visible(timeout=5000)
        expect(tip).to_contain_text(calibers["ai-accept-rate"])

        page.locator('.el-tabs__item:has-text("覆盖率")').click()
        cov_hover = page.locator('[data-test="cov-rate"] [data-test="caliber-hover"]')
        expect(cov_hover).to_be_visible(timeout=15000)
        assert cov_hover.get_attribute("data-caliber") == "req-coverage"
        cov_hover.hover()
        tip2 = page.locator('.el-popper').filter(has_text="需求覆盖率 v1.0=").first
        expect(tip2).to_be_visible(timeout=5000)
        expect(tip2).to_contain_text(calibers["req-coverage"])

        assert_no_errors(collectors, "O10 口径 hover tooltip")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
