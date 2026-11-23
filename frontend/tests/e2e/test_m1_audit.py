
import uuid

import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, mk_tester, nav_to



def _login(username: str, password: str) -> str | None:
    r = httpx.post(f"{API_BASE}/auth/login", json={"username": username, "password": password}, timeout=10)
    if r.status_code != 200:
        return None
    body = r.json()
    if body.get("code") != "0":
        return None
    return body["data"]["accessToken"]


def _mk_config(api, key, value, group="通知", vtype="string", encrypt=0, desc="E2E 审计配置"):
    r = api.post("/configs", json={
        "configKey": key, "configValue": value, "groupName": group,
        "valueType": vtype, "encryptFlag": encrypt, "description": desc,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建配置失败: {r.text[:300]}"
    return r.json()["data"]


def _cleanup(api, keys):
    pass
    r = api.get("/configs")
    for x in r.json().get("data", []):
        if x.get("configKey") in keys:
            api.delete(f"/configs/{x['id']}")



def test_m1_audit_diff(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.m1.audit.{uniq}"
    cfg = None
    try:
        cfg = _mk_config(api_client, key, "old-value")
        r = api_client.put(f"/configs/{cfg['id']}", json={
            "configKey": key, "configValue": "new-value", "groupName": "通知",
            "valueType": "string", "encryptFlag": 0, "description": "E2E 审计配置-改",
        })
        assert r.status_code == 200 and r.json().get("code") == "0", f"更新失败: {r.text[:300]}"

        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")
        page.locator('[data-test="audit-object-id"]').fill(str(cfg["id"]))
        with page.expect_response(lambda r: "audit-logs" in r.url and f"objectId={cfg['id']}" in r.url, timeout=10000):
            page.locator('[data-test="audit-search"]').click()
        expect(page.locator('[data-test="audit-table"] tbody tr').first).to_be_visible(timeout=10000)
        row = page.locator('[data-test="audit-table"] tbody tr').first
        expect(row.locator('text=系统配置').first).to_be_visible()
        expect(row.locator('code:text-is("update")').first).to_be_visible()
        op_cell = row.locator('td').nth(1)
        assert op_cell.text_content().strip(), f"操作人列应为非空（displayName 映射）: {op_cell.text_content()!r}"

        row.locator('[data-test="audit-detail"]').click()
        expect(page.locator('[data-test="audit-detail-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="audit-diff-table"]')).to_be_visible()
        diff_row = page.locator('[data-test="audit-diff-table"] tbody tr', has_text="configValue")
        expect(diff_row.first).to_be_visible()
        assert "old-value" in diff_row.first.text_content(), f"before 应含旧值: {diff_row.first.text_content()}"
        assert "new-value" in diff_row.first.text_content(), f"after 应含新值: {diff_row.first.text_content()}"
        page.locator('.el-dialog__footer button:has-text("关闭")').first.click()

        page.goto(f"{BASE_URL}/#/audit-logs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="audit-page"]')).to_be_visible()
        page.locator('[data-test="audit-object-id"]').fill(str(cfg["id"]))
        page.locator('[data-test="audit-search"]').click()
        expect(page.locator('[data-test="audit-table"] tbody tr').first).to_be_visible(timeout=10000)
        assert page.locator('[data-test="audit-table"] tbody tr').count() >= 1

        assert_no_errors(collectors, "M1 审计查看与 diff")
    finally:
        if cfg:
            _cleanup(api_client, [key])



def test_m1_audit_filter(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    keys = [f"e2e.m1.f1.{uniq}", f"e2e.m1.f2.{uniq}"]
    ids = []
    try:
        for k in keys:
            cfg = _mk_config(api_client, k, "v1")
            ids.append(cfg["id"])
            api_client.put(f"/configs/{cfg['id']}", json={
                "configKey": k, "configValue": "v2", "groupName": "通知",
                "valueType": "string", "encryptFlag": 0, "description": "E2E 审计筛选",
            })
        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")

        page.locator('[data-test="audit-object-type"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("系统配置")').first.click()
        page.locator('[data-test="audit-search"]').click()
        expect(page.locator('[data-test="audit-table"] tbody tr').first).to_be_visible(timeout=10000)
        sys_rows = page.locator('[data-test="audit-table"] tbody tr').count()
        assert sys_rows >= 2, f"sys_config 记录应 ≥2（两个配置的 create+update）: {sys_rows}"

        page.locator('[data-test="audit-action"]').fill("update")
        with page.expect_response(lambda r: "audit-logs" in r.url and "action=update" in r.url, timeout=10000):
            page.locator('[data-test="audit-search"]').click()
        expect(page.locator('[data-test="audit-table"] tbody tr').first).to_be_visible(timeout=10000)
        n = page.locator('[data-test="audit-table"] tbody tr').count()
        assert n >= 2, f"sys_config+update 记录应 ≥2: {n}"
        for i in range(min(n, 3)):
            row = page.locator('[data-test="audit-table"] tbody tr').nth(i)
            assert "update" in row.locator('code').first.text_content(), f"行 {i} 动作应为 update"

        page.locator('[data-test="audit-object-id"]').fill(str(ids[0]))
        page.locator('[data-test="audit-search"]').click()
        expect(page.locator('[data-test="audit-table"] tbody tr')).to_have_count(1, timeout=10000)
        assert page.locator('[data-test="audit-pager"]').is_visible(), "有数据时分页控件应可见"

        assert_no_errors(collectors, "M1 审计筛选")
    finally:
        _cleanup(api_client, keys)



def test_m1_audit_permission(page, collectors, api_client, uniq):
    pass
    mk_tester(api_client, uniq, prefix="m1p")
    username = f"m1ptester_{uniq}"
    token = _login(username, "Test@12345")
    assert token, f"自建测试人员 {username} 登录失败"
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

    if page.locator('[data-test="system-menu"]').count() > 0:
        page.locator('.el-sub-menu__title:has-text("系统管理")').first.click()
        expect(page.locator('[data-test="menu-audit-logs"]')).to_have_count(0)
    else:
        expect(page.locator('[data-test="menu-audit-logs"]')).to_have_count(0)

    page.goto(f"{BASE_URL}/#/audit-logs", wait_until="domcontentloaded")
    page.wait_for_timeout(1500)
    assert "#/dashboard" in page.url or "#/audit-logs" not in page.url, f"守卫未拦截: {page.url}"

    r = httpx.get(f"{API_BASE}/audit-logs", headers={"Authorization": f"Bearer {token}"}, timeout=10)
    assert r.status_code == 403 and r.json().get("code") == "40301", f"应 403: {r.status_code} {r.text[:200]}"
    assert "perm:detail:audit-view" in r.json().get("msg", ""), f"人话应指明权限点: {r.json().get('msg')}"



def test_m1_audit_detail_no_snapshot(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.m1.audit.evt.{uniq}"
    cfg = _mk_config(api_client, key, "v0")
    try:
        r = api_client.delete(f"/configs/{cfg['id']}")
        assert r.status_code == 200 and r.json().get("code") == "0", f"删除配置失败: {r.text[:300]}"

        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")
        page.locator('[data-test="audit-object-id"]').fill(str(cfg["id"]))
        page.locator('[data-test="audit-action"]').fill("delete")
        with page.expect_response(
            lambda rr: "audit-logs" in rr.url and f"objectId={cfg['id']}" in rr.url, timeout=10000
        ):
            page.locator('[data-test="audit-search"]').click()
        row = page.locator('[data-test="audit-table"] tbody tr').first
        expect(row).to_be_visible(timeout=10000)
        expect(row.locator('code:text-is("delete")').first).to_be_visible()

        row.locator('[data-test="audit-detail"]').click()
        expect(page.locator('[data-test="audit-detail-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="audit-diff-empty"]')).to_be_visible()
        assert "事件轨迹" in page.locator('[data-test="audit-diff-empty"]').text_content(), \
            "无快照行应显示人话空态说明"
        assert page.locator('[data-test="audit-diff-table"]').count() == 0, \
            "无快照行不应渲染对比表（禁 null→null）"

        assert_no_errors(collectors, "M1 审计无快照行详情")
    finally:
        _cleanup(api_client, [key])



def test_m1_audit_row_click_opens_detail(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.m1.audit.row.{uniq}"
    cfg = None
    try:
        cfg = _mk_config(api_client, key, "v1")
        api_client.put(f"/configs/{cfg['id']}", json={
            "configKey": key, "configValue": "v2", "groupName": "通知",
            "valueType": "string", "encryptFlag": 0, "description": "E2E 审计整行点击",
        })
        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")
        page.locator('[data-test="audit-object-id"]').fill(str(cfg["id"]))
        page.locator('[data-test="audit-action"]').fill("update")
        with page.expect_response(
            lambda rr: "audit-logs" in rr.url and f"objectId={cfg['id']}" in rr.url, timeout=10000
        ):
            page.locator('[data-test="audit-search"]').click()
        row = page.locator('[data-test="audit-table"] tbody tr').first
        expect(row).to_be_visible(timeout=10000)

        row.locator('td').nth(4).click()
        expect(page.locator('[data-test="audit-detail-dialog"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="audit-diff-table"]')).to_be_visible()
        page.locator('.el-dialog__footer button:has-text("关闭")').first.click()
        expect(page.locator('[data-test="audit-detail-dialog"]')).to_be_hidden(timeout=5000)

        row.locator('[data-test="audit-detail"]').click()
        expect(page.locator('[data-test="audit-detail-dialog"]')).to_be_visible(timeout=5000)

        assert_no_errors(collectors, "M1 审计整行点开详情")
    finally:
        if cfg:
            _cleanup(api_client, [key])
