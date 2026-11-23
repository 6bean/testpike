
import httpx
from conftest import (
    ADMIN_PASSWORD,
    ADMIN_USERNAME,
    API_BASE,
    api_login,
    assert_no_errors,
    inject_user_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

APPROVE_KEY = "perm:skill:approve"
MARKET_KEY = "perm:skill:market"


def _mk_deny_role(api, uniq):
    pass
    r = api.post("/roles", json={
        "name": f"K12反例角色_{uniq}", "code": f"k12deny_{uniq}",
        "description": "T206 收口 E2E：deny 审批键", "dataScope": 2, "status": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建角色失败: {r.text[:300]}"
    role_id = r.json()["data"]
    r = api.put(f"/roles/{role_id}/permissions", json={
        "items": [
            {"permKey": MARKET_KEY, "effect": 1},
            {"permKey": APPROVE_KEY, "effect": 2},
        ],
        "confirm": False,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"覆写保存失败: {r.text[:300]}"
    return role_id


def _mk_user_with_role(api, uniq, role_id):
    pass
    username = f"t206u_{uniq}"
    r = api.post("/users", json={
        "username": username, "password": "Test@12345", "displayName": f"T206用户{uniq}",
        "email": f"{username}@test.local", "phone": "", "role": 1,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用户失败: {r.text[:300]}"
    user_id = r.json()["data"]
    r = api.put(f"/users/{user_id}/roles", json={"roleIds": [role_id]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"分配角色失败: {r.text[:300]}"
    return user_id, username


def _mk_pending_skill(api, name):
    pass
    r = api.post("/skills", json={
        "projectId": None, "name": name, "type": 1,
        "body": f"## 检查规则\n{name} T206 收口造数正文\n",
        "submitNow": True,
    })
    body = r.json()
    assert body["code"] == "0", f"创建 SKILL 失败: {body}"
    sid = body["data"]["id"]
    assert body["data"]["status"] == 2, f"应为待审批: {body['data']}"
    return sid


def _cleanup_all(api, sid, role_id, user_id):
    pass
    if sid is not None:
        cur = api.get(f"/skills/{sid}").json()["data"]
        if cur["status"] == 2:
            cur = api.post(f"/skills/{sid}/approve?expectedVersion={cur['version']}").json()["data"]
        if cur["status"] == 3:
            cur = api.post(f"/skills/{sid}/retire?expectedVersion={cur['version']}").json()["data"]
        api.delete(f"/skills/{sid}?expectedVersion={cur['version']}")
    if user_id:
        api.put(f"/users/{user_id}/roles", json={"roleIds": []})
        api.delete(f"/users/{user_id}")
    if role_id:
        api.delete(f"/roles/{role_id}")


def _search_row(page, name):
    page.fill('[data-test="skill-keyword"]', name)
    page.click('[data-test="skill-search"]')
    row = page.locator('[data-test="skill-table"] tbody tr').filter(has_text=name).first
    expect(row).to_be_visible(timeout=8000)
    return row



def test_skill_approve_denied(logged_in, collectors, api_client, uniq):
    pass
    role_id = _mk_deny_role(api_client, uniq)
    user_id, username = _mk_user_with_role(api_client, uniq, role_id)
    name = f"t206-skill-{uniq}"
    sid = _mk_pending_skill(api_client, name)
    try:
        token = api_login(username, "Test@12345")
        r = httpx.get(f"{API_BASE}/me/permissions",
                      headers={"Authorization": f"Bearer {token}"}, timeout=10)
        assert r.status_code == 200, f"/me/permissions 应 200: {r.text[:300]}"
        perms = r.json()["data"]
        assert MARKET_KEY in perms, f"allow 覆写未下发: {perms}"
        assert APPROVE_KEY not in perms, f"deny 键仍在下发集: {perms}"

        r = httpx.post(f"{API_BASE}/skills/{sid}/approve?expectedVersion=1",
                       headers={"Authorization": f"Bearer {token}"}, timeout=10)
        assert r.status_code == 403, f"无审批键直调 approve 应 403: {r.status_code} {r.text[:300]}"
        body = r.json()
        assert body.get("code") == "40301", f"业务码应 40301: {body}"
        assert "管理员" in body.get("msg", ""), f"403 应人话点名: {body}"

        page = inject_user_token(logged_in, username, "Test@12345")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        expect(row.locator('[data-test="ra-approve"]')).to_have_count(0)
        expect(row.locator('[data-test="ra-reject"]')).to_have_count(0)
        expect(row.locator('[data-test="ra-remove"]')).to_have_count(1)
        assert "待审批" in row.inner_text(), f"行状态异常: {row.inner_text()}"
        assert_no_errors(collectors, "K12 审批键 deny 反例")
    finally:
        _cleanup_all(api_client, sid, role_id, user_id)
        assert sql_query(f"SELECT count(*) FROM tp_skill WHERE id={sid} AND deleted=0") == "0", \
            "SKILL 清理残留（活跃面 deleted=0 口径，污染 L4 全表断言）"



def test_skill_approve_visible_for_admin(logged_in, collectors, api_client, uniq):
    pass
    name = f"t206-adm-{uniq}"
    sid = _mk_pending_skill(api_client, name)
    try:
        token = api_login(ADMIN_USERNAME, ADMIN_PASSWORD)
        r = httpx.get(f"{API_BASE}/me/permissions",
                      headers={"Authorization": f"Bearer {token}"}, timeout=10)
        assert r.status_code == 200, f"/me/permissions 应 200: {r.text[:300]}"
        perms = r.json()["data"]
        assert "*" not in perms and not any(p.endswith(":*") for p in perms), \
            f"admin 集含裸通配（应 147 键展开）: {perms[:10]}"
        assert APPROVE_KEY in perms, "admin 缺 perm:skill:approve"
        assert len(perms) >= 147, f"admin 展开集应≥147 新键: {len(perms)}"

        page = logged_in
        nav_to(page, "SKILL 管理", "/skills", '[data-test="skill-list-page"]', submenu="AI 能力")
        row = _search_row(page, name)
        expect(row.locator('[data-test="ra-approve"]')).to_have_count(1, timeout=8000)
        expect(row.locator('[data-test="ra-reject"]')).to_have_count(1)
        assert_no_errors(collectors, "K12 审批键 admin 正例")
    finally:
        _cleanup_all(api_client, sid, None, None)
        assert sql_query(f"SELECT count(*) FROM tp_skill WHERE id={sid} AND deleted=0") == "0", \
            "SKILL 清理残留（活跃面 deleted=0 口径，污染 L4 全表断言）"
