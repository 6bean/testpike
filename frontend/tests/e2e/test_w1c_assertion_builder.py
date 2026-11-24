
import json

import pytest
from playwright.sync_api import expect

from conftest import (  # anchor migration helpers (header context + rail picks)
    pick_apitest_collection,
    pick_apitest_request,
    pick_project_in_header,
)

from conftest import (
    BASE_URL,
    assert_no_errors,
    nav_to,
)

pytestmark = pytest.mark.timeout(300)


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API 失败: {r.text[:300]}"
    return r.json()["data"]


def _select(page, anchor, option_text):
    pass
    page.locator(f'[data-test="{anchor}"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=option_text).first
    expect(option).to_be_visible(timeout=10000)
    option.click()


@pytest.fixture
def w1c_project_collection(api_client, uniq):
    pass
    data = _api_ok(api_client.post("/projects", json={
        "name": f"W1C断言项目{uniq}", "code": f"W1C{uniq.upper()}"}))
    pid = data["id"] if isinstance(data, dict) else data
    coll = _api_ok(api_client.post("/apitest/collections", json={
        "projectId": pid, "parentId": 0, "name": f"集合{uniq}", "tags": ""}))
    yield pid, coll["id"]
    api_client.delete(f"/apitest/collections/{coll['id']}")
    api_client.delete(f"/projects/{pid}")


def test_w1c_assertion_builder_flow(logged_in, collectors, api_client, uniq, w1c_project_collection):
    pass
    page = logged_in
    pid, coll_id = w1c_project_collection

    # w1c fixture creates the project after logged_in injected the token —
    # reload re-runs loadMe so the header visible-project snapshot includes it.
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")

    # Pick project -> pick collection ( anchor migration: the page-level
    # project dropdown is retired — the header global context is the switch;
    # clicking the rail card auto-opens its first request)
    project_name = f"W1C断言项目{uniq}"
    pick_project_in_header(page, project_name)
    pick_apitest_collection(page, f"集合{uniq}")

    page.locator('[data-test="apitest-new-request-btn"]').click()
    page.locator('[data-test="apitest-request-name"]').fill(f"查询接口{uniq}")
    page.locator('[data-test="apitest-url-path"]').fill("/users/list")

    page.locator('[data-test="assertion-add-btn"]').click()
    _select(page, "assertion-type-0", "JSONPath 断言")
    # Blur triggers the dirty flag. Clicking the neutral description line keeps
    # the focus move free of extra requests. retired the in-page h2 (the
    # topbar is the single page-name source); the description line is now the
    # stable neutral click target on this page.
    page.locator('[data-test="api-test-page"] .tp-page-desc').click()
    err = page.locator('[data-test="assertion-error-0"]')
    expect(err).to_be_visible(timeout=5000)
    expect(err).to_contain_text("还差字段路径")
    expect(page.locator('[data-test="assertion-example-0"]')).to_contain_text("$.data.id 等于 10086")

    page.locator('[data-test="apitest-save-btn"]').click()
    expect(page.locator('[data-test="assertion-error-0"]')).to_be_visible(timeout=3000)

    page.locator('[data-test="assertion-path-0"]').fill("$.code")
    _select(page, "assertion-op-0", "contains")
    page.locator('[data-test="assertion-expect-0"]').fill("ok")

    page.locator('[data-test="assertion-add-btn"]').click()
    _select(page, "assertion-type-1", "状态码")
    page.locator('[data-test="assertion-expect-1"]').fill("200")
    page.locator('[data-test="api-test-page"] .tp-page-desc').click()
    expect(page.locator('[data-test="assertion-error-1"]')).to_have_count(0)

    page.locator('[data-test="apitest-save-btn"]').click()
    expect(page.locator(".el-message:has-text(\"已保存\")").first).to_be_visible(timeout=10000)

    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    nav_to(page, "API 测试", "/api-test", '[data-test="api-test-page"]', submenu="执行测试")
    pick_project_in_header(page, project_name)
    pick_apitest_collection(page, f"集合{uniq}")
    pick_apitest_request(page, f"查询接口{uniq}")
    expect(page.locator('[data-test="assertion-row-0"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="assertion-path-0"]')).to_have_value("$.code")
    expect(page.locator('[data-test="assertion-expect-0"]')).to_have_value("ok")
    expect(page.locator('[data-test="assertion-row-1"]')).to_be_visible()
    expect(page.locator('[data-test="assertion-expect-1"]')).to_have_value("200")

    reqs = _api_ok(api_client.get(f"/apitest/requests?collectionId={coll_id}"))
    assert len(reqs) == 1, f"集合内应有 1 条请求，实际 {len(reqs)}"
    rid = reqs[0]["id"]
    detail = _api_ok(api_client.get(f"/apitest/requests/{rid}"))
    rows = json.loads(detail["assertionsJson"])
    assert len(rows) == 2, f"断言应持久化 2 行，实际: {rows}"
    assert rows[0] == {"type": "body", "op": "contains", "path": "$.code", "expect": "ok"}, rows[0]
    assert rows[1] == {"type": "status", "expect": 200}, rows[1]

    _api_ok(api_client.delete(f"/apitest/requests/{rid}"))

    assert_no_errors(collectors, "W1c 断言构建器 E2E")
