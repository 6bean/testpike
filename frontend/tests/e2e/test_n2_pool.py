
import concurrent.futures
import json
import os
import uuid

import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, inject_token, nav_to



def _mk_project(api, uniq):
    pcode = f"n2p{uniq}"
    r = api.post("/projects", json={"name": f"N2项目{uniq}", "code": pcode, "lang": "zh-CN", "priority": "P1"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"] if isinstance(r.json()["data"], int) else r.json()["data"]["id"]
    r = api.post(f"/projects/{pid}/envs", json={
        "name": "N2测试环境", "envType": 2, "baseUrl": f"https://{pcode}.example.com",
        "deployType": "web", "browserType": "chromium", "version": "v1.0.0"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    env_id = r.json()["data"] if isinstance(r.json()["data"], int) else r.json()["data"]["id"]
    content = json.dumps({
        "poolType": "账号",
        "sql": f"INSERT INTO tp_project_env (project_id, name, env_type, base_url) VALUES "
               f"({pid}, 'E2E造号环境', 3, 'https://e2epool.example.com') RETURNING name"}, ensure_ascii=False)
    r = api.post("/data-factories", json={
        "projectId": pid, "envId": env_id, "type": 2, "name": f"N2造号{uniq}", "content": content})
    assert r.json().get("code") == "0", f"建造数脚本失败: {r.text[:300]}"
    fid = r.json()["data"]["id"]
    r = api.post(f"/data-factories/{fid}/run")
    assert r.json().get("code") == "0" and r.json()["data"]["poolCount"] >= 1, f"run 失败: {r.text[:300]}"
    r = api.get(f"/data-factories/pool-items?projectId={pid}")
    items = r.json()["data"]["list"]
    assert items, "池列表为空"
    return pid, items[0]


def _cleanup(api, pid, fid=None):
    if fid:
        api.delete(f"/data-factories/{fid}")
    api.delete(f"/projects/{pid}")


def _select_project(page, pid):
    """Land the data-factory page on the current project via the header
    project context (T396b posture: in-page selector retired — deep link +
    reload refreshes the visible-domain snapshot).

    The visibility assertion must cover both tab tables — datafactory-table
    (factory scripts) and pool-table (data pool) are separate el-tables and
    the tab state survives SPA navigation: with the pool tab active the
    factory table sits in a hidden pane (:visible filters it out) and vice
    versa (measured T80 failure root cause). Either one visible passes.
    """
    page.goto(f"{BASE_URL}/#/data-factories?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="datafactory-list-page"]')).to_be_visible(timeout=15000)
    expect(page.locator(
        '[data-test="datafactory-table"]:visible, [data-test="pool-table"]:visible'
    ).first).to_be_visible(timeout=15000)



def test_n2_pool_status_ops(page, collectors, api_client, uniq):
    pid, item = _mk_project(api_client, uniq)
    item_id = item["id"]
    fid = None
    try:
        inject_token(page)
        r = api_client.get(f"/data-factories?projectId={pid}")
        fid = r.json()["data"]["list"][0]["id"]

        nav_to(page, "自动造数", "/data-factories", '[data-test="datafactory-list-page"]', submenu="造数与资产")
        _select_project(page, pid)
        page.get_by_role("tab", name="数据池").click()
        expect(page.locator("[data-test=pool-status-tag]").first).to_be_visible(timeout=10000)
        expect(page.locator("[data-test=pool-status-tag]").first).to_contain_text("空闲")

        page.locator("[data-test=pool-abnormal-btn]").first.click()
        expect(page.locator(".el-message").first).to_contain_text("已标记失效", timeout=5000)
        page.reload(wait_until="domcontentloaded")
        _select_project(page, pid)
        page.get_by_role("tab", name="数据池").click()
        expect(page.locator("[data-test=pool-status-tag]").first).to_contain_text("已失效", timeout=10000)
        r = api_client.get(f"/data-factories/pool-items?projectId={pid}&status=3")
        assert r.json()["data"]["total"] >= 1, "状态筛选=已失效应有 1 条"

        page.locator("[data-test=pool-recycle-btn]").first.click()
        expect(page.locator(".el-message").first).to_contain_text("已回收为空闲", timeout=5000)
        page.reload(wait_until="domcontentloaded")
        _select_project(page, pid)
        page.get_by_role("tab", name="数据池").click()
        expect(page.locator("[data-test=pool-status-tag]").first).to_contain_text("空闲", timeout=10000)

        page.locator("[data-test=pool-delete-btn]").first.click()
        page.locator(".el-message-box__btns button:has-text('删除')").click()
        expect(page.locator(".el-message").first).to_contain_text("已删除", timeout=5000)
        page.reload(wait_until="domcontentloaded")
        _select_project(page, pid)
        page.get_by_role("tab", name="数据池").click()
        r = api_client.get(f"/data-factories/pool-items?projectId={pid}")
        assert all(x["id"] != item_id for x in r.json()["data"]["list"]), "删除后 API 不再返回该记录"

        assert_no_errors(collectors, "N2 数据池状态操作")
    finally:
        _cleanup(api_client, pid, fid)



def test_n2_occupy_release_api(page, collectors, api_client, uniq):
    pid, item = _mk_project(api_client, uniq)
    item_id = item["id"]
    fid = None
    api_base = os.environ.get("TP_API_BASE", "http://localhost:8082")
    try:
        inject_token(page)
        r = api_client.get(f"/data-factories?projectId={pid}")
        fid = r.json()["data"]["list"][0]["id"]

        def occupy(i):
            with httpx.Client(base_url=api_base, timeout=30) as c:
                resp = c.post(f"/internal/pool-items/{item_id}/occupy",
                              params={"projectId": pid, "lockedBy": 91000 + i})
                return resp.status_code, (resp.json() or {}).get("code")

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
            results = list(ex.map(occupy, range(8)))
        ok = sum(1 for st, code in results if st == 200 and code == "0")
        assert ok == 1, f"并发占用应仅 1 成功: {results}"
        assert sum(1 for st, _ in results if st == 409) == 7, f"其余 7 个应 409: {results}"

        with httpx.Client(base_url=api_base, timeout=30) as c:
            resp = c.post(f"/internal/pool-items/{item_id}/occupy",
                          params={"projectId": pid, "lockedBy": 91999})
        body = resp.json()
        assert resp.status_code == 409 and body.get("code") == "40902", f"占用中再占用应 40902: {resp.status_code} {body}"
        assert "已被占用" in (body.get("msg") or ""), f"人话缺失: {body.get('msg')}"

        nav_to(page, "自动造数", "/data-factories", '[data-test="datafactory-list-page"]', submenu="造数与资产")
        _select_project(page, pid)
        page.get_by_role("tab", name="数据池").click()
        expect(page.locator("[data-test=pool-status-tag]").first).to_contain_text("占用中", timeout=10000)

        with httpx.Client(base_url=api_base, timeout=30) as c:
            r1 = c.post(f"/internal/pool-items/{item_id}/release", params={"projectId": pid})
            r2 = c.post(f"/internal/pool-items/{item_id}/release", params={"projectId": pid})
        assert r1.status_code == 200 and r2.status_code == 200, f"释放/幂等应 200: {r1.status_code} {r2.status_code}"
        page.reload(wait_until="domcontentloaded")
        _select_project(page, pid)
        page.get_by_role("tab", name="数据池").click()
        expect(page.locator("[data-test=pool-status-tag]").first).to_contain_text("空闲", timeout=10000)

        assert_no_errors(collectors, "N2 并发占用+释放")
    finally:
        _cleanup(api_client, pid, fid)
