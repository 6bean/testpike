
import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, inject_token



def _mk_project(api, code, name):
    pass
    r = api.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "预期",
        "priority": 2, "confidence": 2, "endType": "web", "phase": 1, "source": 2, "status": 1,
        "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_req(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={
        "source": 3, "sourceId": f"REQ-E2E-O1", "title": title,
        "acceptanceCriteria": "验收", "chapter": None})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建需求失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_defect(api, pid, title):
    r = api.post("/defect-library", json={
        "projectId": pid, "title": title, "description": "描述", "severity": 2})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建缺陷失败: {r.text[:300]}"
    return r.json()["data"]


def _open_search(page):
    pass
    page.keyboard.press("Control+k")
    expect(page.locator('[data-test="global-search-dialog"]')).to_be_visible(timeout=5000)
    return page.locator('[data-test="global-search-input"]')


@pytest.fixture
def o1_data(api_client, uniq):
    pass
    token = f"O1E{uniq.upper()}"
    pid = _mk_project(api_client, f"O1E{uniq.upper()}", f"O1搜索项目{token}")
    case = _mk_case(api_client, pid, f"O1搜索用例{token}")
    _mk_req(api_client, pid, f"O1搜索需求{token}")
    _mk_defect(api_client, pid, f"O1搜索缺陷{token}")
    yield {"token": token, "pid": pid, "case": case}
    api_client.delete(f"/projects/{pid}/purge?force=true")


def test_o1_ctrl_k_search(page, collectors, o1_data):
    pass
    inject_token(page)
    try:
        inp = _open_search(page)
        inp.fill(o1_data["token"])
        body = page.locator('[data-test="global-search-body"]')
        for label in ("用例", "需求点", "缺陷", "项目"):
            expect(body.locator(".gs-group-title").filter(has_text=label)).to_be_visible(timeout=10000)
        expect(body.locator("mark.gs-mark").first).to_be_visible(timeout=5000)
        # The pinned ask row legitimately sits in the first DOM slot while the
        # input is non-blank, so the contract to assert is the first RESULT row
        # via its data-test anchor (cases leads the flat band). With results on
        # screen Enter must open that top hit, not the ask row.
        first_result = body.locator('[data-test="global-search-row-cases"]').first
        expect(first_result).to_contain_text(f"O1搜索用例{o1_data['token']}", timeout=5000)
        page.keyboard.press("Enter")
        page.wait_for_url("**/cases/**", timeout=10000)
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(
            f"O1搜索用例{o1_data['token']}")
        expect(page.locator('[data-test="global-search-dialog"]')).not_to_be_visible()

        assert_no_errors(collectors, "O1 Ctrl+K 搜索与 Enter 直达")
    finally:
        pass


def test_o1_row_navigate(page, collectors, o1_data):
    pass
    inject_token(page)
    try:
        inp = _open_search(page)
        inp.fill(o1_data["token"])
        proj_row = page.locator('[data-test="global-search-row-projects"]').first
        expect(proj_row).to_be_visible(timeout=10000)
        proj_row.click()
        page.wait_for_url(f"**/projects/{o1_data['pid']}**", timeout=10000)
        expect(page.locator('[data-test="env-table"]')).to_be_visible(timeout=10000)

        # 2. Click the requirement row → keyword prefilled and filtered on the
        # host page. Assertion corrected against the live router: the tab hop
        # runs /extraction?tab=requirements -> /requirements (frontend
        # src/router/routes.ts), so a requirement row lands on /requirements
        # directly and never passes through the extraction host page.
        inp = _open_search(page)
        inp.fill(o1_data["token"])
        req_row = page.locator('[data-test="global-search-row-requirements"]').first
        expect(req_row).to_be_visible(timeout=10000)
        req_row.click()
        page.wait_for_url("**/requirements?*", timeout=10000)
        expect(page.locator('[data-test="req-filter-keyword"]')).to_have_value(
            f"O1搜索需求{o1_data['token']}", timeout=5000)
        expect(page.locator('[data-test="req-table"]')).to_contain_text(
            f"O1搜索需求{o1_data['token']}", timeout=10000)

        inp = _open_search(page)
        inp.fill("绝对不存在的关键字zzz" + o1_data["token"])
        expect(page.locator('[data-test="global-search-empty"]')).to_be_visible(timeout=10000)
        page.keyboard.press("Escape")
        expect(page.locator('[data-test="global-search-dialog"]')).not_to_be_visible()

        assert_no_errors(collectors, "O1 行直达与空态")
    finally:
        pass


def test_o1_trigger_button(page, collectors, o1_data):
    pass
    inject_token(page)
    try:
        page.locator('[data-test="global-search-trigger"]').click()
        expect(page.locator('[data-test="global-search-dialog"]')).to_be_visible(timeout=5000)
        inp = page.locator('[data-test="global-search-input"]')
        inp.fill(o1_data["token"])
        expect(page.locator('[data-test="global-search-row-cases"]').first).to_be_visible(
            timeout=10000)

        assert_no_errors(collectors, "O1 顶栏入口唤起")
    finally:
        pass
