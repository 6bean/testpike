
import uuid

import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, add_member, api_login, assert_no_errors,
                      inject_user_token, row_cmd)


EXCELLENT_PERM = "perm:feedback:excellent-mark"


def _mk_project(api, uniq):
    name = f"D3优秀_{uniq}"
    r = api.post("/projects", json={"name": name, "code": f"D3E{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_case(api, pid, title, status=3):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 2, "status": status,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)


def _to_case_list(page, pid, project_name, keyword=None):
    pass
    page.goto(f"{page.url.split('#')[0]}#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.get_by_text(project_name).first).to_be_visible(timeout=15000)
    url = f"#/cases?projectId={pid}"
    if keyword:
        url += f"&keyword={keyword}"
    page.goto(f"{page.url.split('#')[0]}{url}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)


def test_d3_mark_unmark_persist(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid, pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"优秀标记用例_{uniq}")
    try:
        _open_detail(page, case["id"])
        star = page.locator(f'[data-test="excellent-star-{case["id"]}"]')
        expect(star).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="excellent-star-off"]')).to_be_visible()
        star.click()
        expect(page.locator(".el-message").filter(has_text="已标记优秀")).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="excellent-star-on"]')).to_be_visible(timeout=5000)

        d = api_client.get(f"/cases/{case['id']}").json()["data"]
        assert d["excellent"] == 1, f"API 标记态: {d}"

        _open_detail(page, case["id"])
        expect(page.locator('[data-test="excellent-star-on"]')).to_be_visible(timeout=10000)

        _to_case_list(page, pid, pname, case["caseNo"])
        row = page.locator('[data-test="case-table"] tbody tr').filter(has_text=case["caseNo"])
        expect(row).to_have_count(1, timeout=15000)
        expect(row.locator(f'[data-test="case-excellent-badge-{case["id"]}"]')).to_be_visible(timeout=10000)

        row_cmd(page, row.first, "excellent-unmark")
        expect(page.locator(".el-message").filter(has_text="已取消优秀")).to_be_visible(timeout=5000)
        _to_case_list(page, pid, pname, case["caseNo"])
        row = page.locator('[data-test="case-table"] tbody tr').filter(has_text=case["caseNo"])
        expect(row.locator(f'[data-test="case-excellent-badge-{case["id"]}"]')).to_have_count(0, timeout=10000)
        d2 = api_client.get(f"/cases/{case['id']}").json()["data"]
        assert d2["excellent"] == 0, f"API 取消态: {d2}"

        assert_no_errors(collectors, "D3 标记/取消持久化")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_d3_not_approved_disabled_and_api_40001(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid, _ = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"待评审用例_{uniq}", status=1)
    try:
        _open_detail(page, case["id"])
        star = page.locator(f'[data-test="excellent-star-{case["id"]}"]')
        expect(star).to_be_visible(timeout=10000)
        expect(star).to_be_disabled()
        expect(star).to_have_attribute("title", "仅已通过用例可标记优秀")

        r = api_client.put(f"/cases/{case['id']}/excellent",
                           json={"excellent": True, "version": case["version"]})
        assert r.status_code == 400, f"直调应 400，实际 {r.status_code}: {r.text[:300]}"
        body = r.json()
        assert str(body.get("code")) == "40001", f"业务码应 40001: {body}"
        assert "仅已通过用例可标记优秀" in body.get("msg", ""), f"回显应人话: {body}"
        assert "待评审" in body.get("msg", ""), f"人话应含当前状态: {body}"

        assert_no_errors(collectors, "D3 非通过灰态+40001")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_d3_excellent_only_filter(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid, pname = _mk_project(api_client, uniq)
    good = _mk_case(api_client, pid, f"优秀筛选命中_{uniq}")
    plain = _mk_case(api_client, pid, f"普通对照用例_{uniq}")
    try:
        r = api_client.put(f"/cases/{good['id']}/excellent",
                           json={"excellent": True, "version": good["version"]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"标记失败: {r.text[:300]}"

        _to_case_list(page, pid, pname)
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(2, timeout=15000)

        page.locator('[data-test="case-filter-excellent"]').click()
        expect(page.locator(f'[data-test="case-excellent-badge-{good["id"]}"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(1, timeout=10000)
        body = page.locator('[data-test="case-table"] tbody').inner_text()
        assert good["caseNo"] in body and plain["caseNo"] not in body, f"筛选结果: {body}"

        page.locator('[data-test="case-filter-excellent"]').click()
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(2, timeout=10000)

        assert_no_errors(collectors, "D3 仅优秀筛选 AND")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def _mk_role_no_perm(api, uniq):
    pass
    r = api.post("/roles", json={
        "name": f"D3受限角色_{uniq}", "code": f"d3no_{uniq}", "description": "D3 负例临时角色",
        "dataScope": 2, "status": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建角色失败: {r.text[:300]}"
    role_id = r.json()["data"]
    aux = ["perm:testops:defect-lib", "perm:project:project-view", "perm:script:script-version",
           "perm:batch:run-history", "perm:scenario:version-history", "perm:ux:trace-view",
           "perm:detail:case-tag"]
    r = api.put(f"/roles/{role_id}/permissions",
                json={"items": [{"permKey": k, "effect": 1} for k in aux], "confirm": False})
    assert r.status_code == 200 and r.json().get("code") == "0", f"补齐辅助权限失败: {r.text[:300]}"
    return role_id


def test_d3_no_perm_user_negative(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid, _ = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"负例用例_{uniq}")
    role_id = _mk_role_no_perm(api_client, uniq)
    r = api_client.post("/users", json={
        "username": f"d3user_{uniq}", "password": "Test@12345", "displayName": f"D3用户{uniq[:6]}",
        "email": f"d3user_{uniq}@test.local", "phone": "", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    user_id = r.json()["data"]
    try:
        r = api_client.put(f"/users/{user_id}/roles", json={"roleIds": [role_id]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"分配角色失败: {r.text[:300]}"
        add_member(api_client, pid, user_id)

        inject_user_token(page, f"d3user_{uniq}", "Test@12345")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        _open_detail(page, case["id"])
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(
            f"负例用例_{uniq}", timeout=10000)
        expect(page.locator(f'[data-test="excellent-star-{case["id"]}"]')).to_have_count(0)

        token = api_login(f"d3user_{uniq}", "Test@12345")
        r = httpx.put(f"{API_BASE}/cases/{case['id']}/excellent",
                      json={"excellent": True, "version": case["version"]},
                      headers={"Authorization": f"Bearer {token}"}, timeout=10)
        assert r.status_code == 403, f"无权限 PUT 应 403，实际 {r.status_code}: {r.text[:300]}"
        body = r.json()
        assert str(body.get("code")) == "40301", f"业务码应 40301: {body}"
        assert "无操作权限" in body.get("msg", ""), f"403 应人话: {body}"

        assert_no_errors(collectors, "D3 无权限负例")
    finally:
        api_client.put(f"/users/{user_id}/roles", json={"roleIds": []})
        api_client.delete(f"/users/{user_id}")
        api_client.delete(f"/roles/{role_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
