
import json

import pytest
from conftest import (
    ADMIN_PASSWORD,
    BASE_URL,
    assert_no_errors,
    inject_user_token,
    open_apitest_datasource_item,
    pick_apitest_collection,
    pick_project_in_header,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _api_ok(r):
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API 失败: {r.text[:300]}"
    return r.json()["data"]


def _spec(uniq: str) -> dict:
    pass
    return {
        "openapi": "3.0.1",
        "info": {"title": f"导入契约{uniq}", "version": "1.0.0"},
        "paths": {
            "/pets": {
                "get": {
                    "operationId": "listPets", "summary": "列出宠物",
                    "tags": ["宠物"],
                    "parameters": [{"name": "limit", "in": "query", "required": False,
                                   "schema": {"type": "integer"}}],
                    "responses": {"200": {"description": "ok"}, "400": {"description": "bad"}},
                },
                "post": {
                    "operationId": "createPet", "summary": "创建宠物",
                    "tags": ["宠物"],
                    "requestBody": {"content": {"application/json": {
                        "schema": {"type": "object"}}}},
                    "responses": {"201": {"description": "created"}},
                },
            },
            "/orders": {
                "get": {
                    "operationId": "listOrders", "summary": "列出订单",
                    "tags": ["订单"],
                    "responses": {"200": {"description": "ok"}},
                },
            },
        },
    }


def _select_option(page, anchor, option_text):
    pass
    page.locator(f'[data-test="{anchor}"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=option_text).first
    expect(option).to_be_visible(timeout=10000)
    option.click()


def test_w1b_spec_import_wizard(page, collectors, api_client, uniq):
    pass
    pid = _api_ok(api_client.post("/projects", json={
        "name": f"W1B导入项目{uniq}", "code": f"W1B{uniq[:6].upper()}"}))
    project_name = f"W1B导入项目{uniq}"
    try:
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-test-page"]')).to_be_visible(timeout=15000)

        # anchor migration: page-level project selector retired — after the
        # header context lands the project, the data-source dropdown item enables.
        pick_project_in_header(page, project_name)
        open_apitest_datasource_item(page, "apitest-import-from-spec-btn")
        dialog = page.locator('[data-test="spec-import-dialog"]')
        expect(dialog).to_be_visible(timeout=5000)

        dialog.locator(".el-tabs__item").filter(has_text="粘贴文档").click()
        dialog.locator('[data-test="spec-import-paste"]').fill(json.dumps(_spec(uniq), ensure_ascii=False))
        dialog.locator('[data-test="spec-import-doc-name"]').fill(f"w1bpaste{uniq}")
        dialog.locator('[data-test="spec-import-parse-btn"]').click()

        detected = dialog.locator('[data-test="spec-import-detected"]')
        expect(detected).to_be_visible(timeout=30000)
        expect(detected).to_contain_text("3 个接口")
        expect(detected).to_contain_text("零费用")
        cards = dialog.locator('[data-test="spec-import-card"]')
        expect(cards).to_have_count(3, timeout=10000)
        expect(dialog).to_contain_text("宠物")
        expect(dialog).to_contain_text("订单")
        expect(dialog.locator('[data-test="spec-import-stat"]')).to_contain_text("已勾 3")

        dialog.locator('[data-test="spec-import-search"]').fill("/orders")
        expect(cards).to_have_count(1, timeout=5000)
        expect(cards.first).to_contain_text("列出订单")
        dialog.locator('[data-test="spec-import-search"]').fill("")
        expect(cards).to_have_count(3, timeout=5000)

        get_pets = cards.filter(has_text="GET").filter(has_text="/pets").first
        get_pets.locator('[data-test="spec-import-expand-btn"]').click()
        detail = get_pets.locator(".si-card-detail")
        expect(detail).to_be_visible(timeout=5000)
        expect(detail).to_contain_text("默认断言")

        cb = dialog.locator('[data-test="spec-import-check-GET--pets"]')
        expect(cb.locator("input")).to_be_checked(timeout=5000)
        cb.click()
        expect(dialog.locator('[data-test="spec-import-stat"]')).to_contain_text("已勾 2")

        dialog.locator('[data-test="spec-import-apply-btn"]').click()
        done = dialog.locator('[data-test="spec-import-done-created"]')
        expect(done).to_be_visible(timeout=20000)
        expect(done).to_contain_text("已写入 2 个请求")
        dialog.locator('[data-test="spec-import-close-btn"]').click()
        expect(dialog).not_to_be_visible(timeout=5000)

        # ── reload persistence: header picks the project, the tag-derived
        # collection (auto-named from the first spec tag) is on the rail and
        # its "create pet" request row is present ──
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-test-page"]')).to_be_visible(timeout=15000)
        pick_project_in_header(page, project_name)
        pick_apitest_collection(page, "宠物")
        expect(page.locator('[data-test^="apitest-request-row-"]').filter(has_text="创建宠物").first) \
            .to_be_visible(timeout=10000)
        db_names = sql_query(
            "SELECT string_agg(r.name, ',' ORDER BY r.name) FROM tp_apitest_request r "
            "JOIN tp_apitest_collection c ON c.id = r.collection_id WHERE c.project_id = " + str(pid))
        assert "创建宠物" in db_names and "列出订单" in db_names and "列出宠物" not in db_names, \
            f"DB 写入面与勾选不符: {db_names}"

        spec_id = int(sql_query(
            f"SELECT id FROM tp_api_spec WHERE project_id = {pid} ORDER BY id DESC LIMIT 1"))
        preview_id = int(sql_query(
            f"SELECT id FROM tp_api_spec_preview WHERE spec_id = {spec_id}"))
        r_apply = api_client.post(f"/api-spec-previews/{preview_id}/apply", json={
            "selectedKeys": ["POST /pets", "GET /orders"]})
        body = _api_ok(r_apply)
        assert body["createdRequests"] == 0 and body["skipped"] == 2, \
            f"重放幂等不符: {body}"

        assert_no_errors(collectors, "W1b 接口文档导入三步向导全链")
    finally:
        sql_query(f"DELETE FROM tp_api_spec_preview WHERE project_id = {pid}")
        sql_query(f"DELETE FROM tp_api_spec WHERE project_id = {pid}")
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        left = sql_query(
            f"SELECT (SELECT count(*) FROM tp_api_spec WHERE project_id = {pid}) + "
            f"(SELECT count(*) FROM tp_api_spec_preview WHERE project_id = {pid}) + "
            f"(SELECT count(*) FROM tp_apitest_collection WHERE project_id = {pid})")
        assert left == "0", f"清理未回零: {left}"
