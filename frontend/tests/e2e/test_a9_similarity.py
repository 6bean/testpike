
import uuid

import httpx
from playwright.sync_api import expect

from conftest import (API_BASE, add_member, api_login, assert_no_errors,
                      sql_query)


def _rows(q):
    return [x for x in sql_query(q).splitlines() if x.strip()]


def _mk_project(api, uniq):
    name = f"A9相似_{uniq}"
    r = api.post("/projects", json={"name": name, "code": f"A9S{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"], name


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 2,
        "steps": [{"stepNo": 1, "description": "登录系统", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _similarity_check(client, pid, text, exclude=None):
    body = {"projectId": pid, "queryText": text}
    if exclude is not None:
        body["excludeCaseId"] = exclude
    return client.post("/cases/similarity-check", json=body)


def test_a9_similarity_contract_failsoft(api_client, collectors):
    pass
    uniq = uuid.uuid4().hex[:8]
    pid, _ = _mk_project(api_client, uniq)
    try:
        r = _similarity_check(api_client, pid, "登录系统输入正确账号密码")
        assert r.status_code == 200, f"similarity-check 非 200: {r.text[:300]}"
        body = r.json()
        assert body.get("code") == "0", f"响应码非 0: {body}"
        assert isinstance(body.get("data"), list), f"data 非列表: {body}"
        r2 = _similarity_check(api_client, pid, "   ")
        assert r2.json().get("code") != "0", "空白 queryText 应被拒"
        assert "文本" in r2.json().get("msg", ""), f"错误消息非人话: {r2.json()}"
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_a9_similarity_no_hit_does_not_block(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    pid, pname = _mk_project(api_client, uniq)
    base = page.url.split("#")[0]
    try:
        page.goto(f"{base}#/dashboard", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.get_by_text(pname).first).to_be_visible(timeout=15000)
        page.goto(f"{base}#/cases?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="case-create"]').first.click()
        expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible()
        page.locator('[data-test="case-create-title"]').fill(f"购物车结算用例_{uniq}")
        page.locator('[data-test="case-create-submit"]').click()
        expect(page.locator('[data-test="similarity-dialog"]')).to_have_count(0, timeout=3000)
        expect(page.locator(".el-message").filter(has_text="创建成功").first).to_be_visible(timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-table"]')).to_be_visible(timeout=15000)
        expect(page.get_by_text(f"购物车结算用例_{uniq}").first).to_be_visible(timeout=10000)

        cid_rows = _rows(f"SELECT id FROM tp_test_case WHERE project_id={pid} AND deleted=0")
        assert cid_rows, f"新建用例未落本造数项目（pid={pid}）——检查快照回落"
        cid = int(cid_rows[0])
        page.goto(f"{base}#/cases/{cid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="detail-edit"]').click()
        expect(page.locator('[data-test="detail-save-draft"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="detail-save-draft"]').click()
        expect(page.locator('[data-test="similarity-dialog"]')).to_have_count(0, timeout=3000)
        expect(page.locator(".el-message").filter(has_text="草稿已保存").first).to_be_visible(timeout=10000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=15000)

        assert_no_errors(collectors, "A9 similarity no-hit not blocking")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_a9_similarity_permission_denied(logged_in, api_client, collectors):
    pass
    uniq = uuid.uuid4().hex[:8]
    pid, _ = _mk_project(api_client, uniq)
    r = api_client.post("/roles", json={
        "name": f"A9受限角色_{uniq}", "code": f"a9no_{uniq}", "description": "A9 负例临时角色",
        "dataScope": 2, "status": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建角色失败: {r.text[:300]}"
    role_id = r.json()["data"]
    r = api_client.post("/users", json={
        "username": f"a9user_{uniq}", "password": "Test@12345", "displayName": f"A9用户{uniq[:6]}",
        "email": f"a9user_{uniq}@test.local", "phone": "", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    user_id = r.json()["data"]
    try:
        r = api_client.put(f"/users/{user_id}/roles", json={"roleIds": [role_id]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"分配角色失败: {r.text[:300]}"
        add_member(api_client, pid, user_id)
        token = api_login(f"a9user_{uniq}", "Test@12345")
        with httpx.Client(base_url=API_BASE, timeout=30) as usr:
            usr.headers["Authorization"] = f"Bearer {token}"
            r = _similarity_check(usr, pid, "任意文本")
        assert r.status_code == 403, f"无权限直调应 403，实际 {r.status_code}: {r.text[:200]}"
        body = r.json()
        assert str(body.get("code")) == "40301", f"业务码应 40301: {body}"
        assert "无操作权限" in body.get("msg", ""), f"403 应人话: {body}"
    finally:
        api_client.put(f"/users/{user_id}/roles", json={"roleIds": []})
        api_client.delete(f"/users/{user_id}")
        api_client.delete(f"/roles/{role_id}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
