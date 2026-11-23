"""F4 API-contract full-chain E2E (real Playwright browser, real chain, zero mocks).

The contract panel retired from the document page, so this chain is re-anchored
on the interface inventory page: assert the document page keeps only a guidance
line → upload OpenAPI3 JSON v1 through the inventory page header (real
multipart → core channel-A parse → ledger rows) → endpoint rows render →
re-upload identical content = 409 plain-language shown inline → re-upload an
edited contract (one added, one removed endpoint) = v2 → the version-compare
drawer asserts added/removed counts → the per-row generate button deep-links
into /ai/generate with projectId + the serialized suggestion prefilled →
reload persistence + DB ground truth → finally cleans everything back to zero.

Known pitfalls: a) run with `set -a && . ./.env` first (ADMIN_INIT_PASSWORD);
b) same-URL persistence must use a real reload (hash navigation does not
re-fetch); c) upload uses the unified dialog set_input_files posture;
d) the expected 409 is exempted narrowly = POST /api-specs 409 plus the
browser's own console 409 noise, every other 4xx/5xx/console error fails the
test; e) contract name = file name without extension, uniq-suffixed to avoid
cross-project collisions.
"""
import json
import os
import re
import tempfile

from conftest import ADMIN_PASSWORD, BASE_URL, assert_no_errors, inject_user_token, sql_query  # noqa: F401
from playwright.sync_api import expect

import pytest

pytestmark = pytest.mark.timeout(240)


def _spec_v1(title: str) -> dict:
    return {
        "openapi": "3.0.1",
        "info": {"title": title, "version": "1.0.0"},
        "paths": {
            "/pets": {
                "get": {
                    "operationId": "listPets",
                    "summary": "列出宠物",
                    "parameters": [{"name": "limit", "in": "query", "required": False,
                                   "schema": {"type": "integer"}}],
                    "responses": {"200": {"description": "ok"},
                                  "400": {"description": "bad"}},
                },
                "post": {
                    "operationId": "createPet",
                    "summary": "创建宠物",
                    "requestBody": {"content": {"application/json": {
                        "schema": {"type": "object"}}}},
                    "responses": {"201": {"description": "created"}},
                },
            },
            "/pets/{petId}": {
                "get": {
                    "operationId": "getPet",
                    "summary": "查询宠物详情",
                    "parameters": [{"name": "petId", "in": "path", "required": True,
                                   "schema": {"type": "integer"}}],
                    "responses": {"200": {"description": "ok"}},
                },
            },
        },
    }


def _spec_v2(title: str) -> dict:
    """Revised contract: /pets/{petId} GET removed, /orders POST added."""
    spec = _spec_v1(title)
    spec["info"]["version"] = "2.0.0"
    del spec["paths"]["/pets/{petId}"]
    spec["paths"]["/orders"] = {
        "post": {"operationId": "createOrder", "summary": "下单",
                 "responses": {"201": {"description": "created"}}},
    }
    return spec


def _write(path: str, spec: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(spec, f, ensure_ascii=False)


def _upload_via_ui(page, path: str):
    # contract upload entrance lives on the inventory page header now (retired
    # from the document page; one surface, no twins)
    page.locator('[data-test="api-inventory-upload-btn"]').first.click()
    dialog = page.locator('[data-test="file-upload-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    dialog.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(path)
    submit = dialog.locator('[data-test="file-upload-submit"]')
    expect(submit).to_be_enabled(timeout=3000)
    submit.click()
    return dialog


def test_f4_api_spec_card(page, collectors, api_client, uniq):
    """Retired guidance → header upload → rows → 409 inline → v2 re-upload → compare drawer → row generate deep link → reload persistence"""
    title = f"E2E宠物契约{uniq}"
    r = api_client.post("/projects", json={"name": f"F4契约{uniq}", "code": f"F4S{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    tmpdir = tempfile.mkdtemp(prefix="f4spec-")
    # Contract name = file name without extension: v1/v2 must share the file
    # name (different dirs), otherwise they become two contracts, not a chain
    d_v1 = os.path.join(tmpdir, "v1")
    d_v2 = os.path.join(tmpdir, "v2")
    os.makedirs(d_v1)
    os.makedirs(d_v2)
    try:
        inject_user_token(page, "admin", ADMIN_PASSWORD)

        # ── 0. the document page retired the panel, keeps only the guidance line ──
        page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="doc-spec-panel"]')).to_have_count(0)
        expect(page.locator('[data-test="doc-contract-moved-hint"]')).to_be_visible(timeout=5000)

        # ── 1. upload v1 (3 endpoints) through the inventory header ──
        name = f"petstore-{uniq}"
        v1_path = os.path.join(d_v1, f"{name}.json")
        _write(v1_path, _spec_v1(title))
        page.goto(f"{BASE_URL}/#/api-inventory?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="api-inventory-page"]')).to_be_visible(timeout=15000)
        _upload_via_ui(page, v1_path)
        expect(page.locator('.el-message--success').filter(has_text="解析完成").first) \
            .to_be_visible(timeout=20000)
        # the picked chain auto-anchors the single contract of this project
        table = page.locator('[data-test="api-inventory-table"]')
        expect(table.locator("tbody tr")).to_have_count(3, timeout=10000)
        expect(table).to_contain_text("/pets/{petId}")
        expect(page.locator('[data-test="api-inventory-cov-total"]')).to_have_text("3")

        # ── 2. re-upload identical content = 409 plain-language inline (idempotent
        #        reject, the version chain must not grow) ──
        dialog = _upload_via_ui(page, v1_path)
        err = dialog.locator('[data-test="file-upload-error"]')
        expect(err).to_be_visible(timeout=15000)
        expect(err).to_contain_text("与现有版本内容一致")
        dialog.locator('button:has-text("取消")').click()
        assert int(sql_query(f"SELECT count(*) FROM tp_api_spec WHERE project_id = {pid}")) == 1, \
            "同内容重传不得新增版本行"

        # ── 3. re-upload an edited contract = v2 (same name, new content;
        #        one endpoint added, one removed → 3 anchor rows + 1 removed row) ──
        v2_path = os.path.join(d_v2, f"{name}.json")
        _write(v2_path, _spec_v2(title))
        _upload_via_ui(page, v2_path)
        expect(page.locator('.el-message--success').filter(has_text="v2").first) \
            .to_be_visible(timeout=20000)
        table = page.locator('[data-test="api-inventory-table"]')
        # honesty-first ledger: the anchor version lists its 3 endpoints and the
        # vanished one stays visible exactly once (freshness = removed), never dropped
        expect(table.locator("tbody tr")).to_have_count(4, timeout=10000)
        expect(table).to_contain_text("/orders")
        orders_row = table.locator("tbody tr").filter(has_text="/orders").first
        expect(orders_row.locator('[data-test="api-inventory-freshness"]')).to_have_text("稳定")
        removed_row = table.locator("tbody tr").filter(has_text="/pets/{petId}").first
        expect(removed_row.locator('[data-test="api-inventory-freshness"]')).to_have_text("已移除")

        # ── 4. version-compare drawer: the adjacent v1↔v2 pair renders the
        #        server-side change ledger, added 1 / removed 1 ──
        page.locator('[data-test="api-inventory-compare-btn"]').click()
        drawer = page.locator('[data-test="api-inventory-compare-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        expect(drawer.locator('.cc-added')).to_have_text("新增接口 1")
        expect(drawer.locator('.cc-removed')).to_have_text("已移除 1")
        expect(drawer.locator('[data-test="api-inventory-compare-source"]')) \
            .to_contain_text("按服务端变更记录展示")

        # ── 5. per-row generate deep link: /ai/generate prefilled with
        #        projectId + the serialized endpoint suggestion ──
        page.keyboard.press("Escape")
        expect(drawer).not_to_be_visible(timeout=5000)
        orders_row = page.locator('[data-test="api-inventory-table"] tbody tr') \
            .filter(has_text="/orders").first
        gen_btn = orders_row.locator('[data-test="api-inventory-gen-btn"]')
        expect(gen_btn).to_be_visible(timeout=5000)
        gen_btn.click()
        expect(page).to_have_url(re.compile(r"/#/ai/generate"), timeout=10000)
        gen_box = page.locator('[data-test="ai-gen-requirement"]')
        expect(gen_box).to_be_visible(timeout=8000)
        # assert the textarea value (the injected suggestion text is the
        # serialized contract summary; this fixture's responses carry no
        # content schema → core responseSchemas empty → the assertion falls
        # back to the "HTTP 2xx" wording)
        expect(gen_box).to_have_value("为接口 POST /orders（下单）生成测试用例：契约「petstore-"
                                      + uniq + "」v2；参数：无；断言：HTTP 2xx")

        # ── 6. reload persistence: back on the inventory page the anchored
        #        version is the DB ground truth ──
        page.goto(f"{BASE_URL}/#/api-inventory?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        table = page.locator('[data-test="api-inventory-table"]')
        expect(table).to_contain_text("/orders", timeout=15000)
        dbv = sql_query(f"SELECT spec_version || '|' || coalesce(diff_summary -> 'added' ->> 0,'-')"
                        f" FROM tp_api_spec WHERE project_id = {pid} ORDER BY id DESC LIMIT 1")
        assert dbv.startswith("2|POST /orders"), f"DB 版本链/diff 实钉不符: {dbv}"

        # the expected 409 (idempotent reject = the assertion subject) is
        # exempted narrowly: POST /api-specs 409 + the browser's own 409 noise
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/api-specs" in e["url"] and e["status"] == 409)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 409" not in c]
        assert_no_errors(collectors, "F4 接口契约 上传/清单/幂等409/版本对照/建议直达/持久化 全链")
    finally:
        try:
            sql_query(f"DELETE FROM tp_api_spec WHERE project_id = {pid}")
        except Exception:
            pass
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        assert sql_query(f"SELECT count(*) FROM tp_api_spec WHERE project_id = {pid}") == "0"
