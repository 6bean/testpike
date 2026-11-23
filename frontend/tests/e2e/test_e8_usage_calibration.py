
import re
import subprocess

import httpx
from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, ADMIN_USERNAME, API_BASE, BASE_URL, api_login, assert_no_errors, nav_to


def _col_index(page, header_text):
    pass
    heads = page.locator('.el-table__header th').all_inner_texts()
    idx = [i for i, h in enumerate(heads) if header_text in h]
    assert idx, f"表头未找到列「{header_text}」，实际={heads}"
    return idx[0]



def test_e8_usage_page_smoke(logged_in, collectors):
    # AN-6: the Token calibration leaf retired into /cost as a gated tab
    # (menu no longer carries it) — navigation now opens the cost board and
    # switches to the calibration tab; the old /usage-calibration address is
    # asserted to still resolve through the redirect.
    page = logged_in
    nav_to(page, "成本中心", "/cost",
           '[data-test="cost-dashboard-page"]', submenu="系统管理")
    page.locator('[data-test="cost-tab-calibration"]').click()
    expect(page).to_have_url(re.compile(r"tab=calibration"))
    expect(page.locator('[data-test="usage-agg-table"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="usage-sample-table"]')).to_be_visible()
    expect(page.locator('[data-test="usage-calibration-page"]')).to_be_visible()
    # Old bookmark still lands on the tab (zero-loss face ①).
    page.goto(f"{BASE_URL}/#/usage-calibration", wait_until="domcontentloaded")
    expect(page).to_have_url(re.compile(r"/cost\?tab=calibration"), timeout=10000)
    expect(page.locator('[data-test="usage-calibration-page"]')).to_be_visible(timeout=10000)
    assert_no_errors(collectors, "E8 Token校准页冒烟")



def test_e8_llm_prompt_limit_crud(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"e2e-e8-{uniq}"
    codes_cleanup = [name]
    try:
        nav_to(page, "大模型配置", "/llm-configs",
               '[data-test="llm-config-page"]', submenu="系统管理")
        page.locator('[data-test="llm-create"]').click()
        expect(page.locator('[data-test="llm-prompt-limit-input"]')).to_be_visible(timeout=5000)
        assert page.locator('[data-test="llm-prompt-limit-input"] input').input_value() == "1048576", \
            "提示词上限默认值应为 1,048,576（V33 契约）"
        page.locator('[data-test="llm-name-input"]').fill(name)
        page.locator('[data-test="llm-model-input"]').fill("deepseek-chat")
        page.locator('[data-test="llm-prompt-limit-input"] input').fill("262144")
        page.locator('[data-test="llm-save"]').click()
        expect(page.locator('.el-message--success')).to_be_visible(timeout=8000)

        page.goto(f"{BASE_URL}#/llm-configs", wait_until="domcontentloaded")
        row = page.locator(f'.el-table__row:has-text("{name}")')
        expect(row).to_have_count(1, timeout=8000)
        expect(row.locator("td").nth(_col_index(page, "提示词上限"))) \
            .to_contain_text("262,144")

        row.locator('[data-test="llm-edit"]').click()
        limit_input = page.locator('[data-test="llm-prompt-limit-input"] input')
        expect(limit_input).to_have_value("262144", timeout=5000)
        limit_input.fill("524288")
        page.locator('[data-test="llm-save"]').click()
        expect(page.locator('.el-message--success')).to_be_visible(timeout=8000)
        page.goto(f"{BASE_URL}#/llm-configs", wait_until="domcontentloaded")
        expect(page.locator(f'.el-table__row:has-text("{name}")').locator("td")
               .nth(_col_index(page, "提示词上限"))) \
            .to_contain_text("524,288", timeout=8000)

        r = httpx.get(f"{API_BASE}/llm-configs", headers={"Authorization": f"Bearer {api_login(ADMIN_USERNAME, ADMIN_PASSWORD)}"}, timeout=10)
        hit = [x for x in r.json()["data"] if x["name"] == name]
        assert hit and hit[0]["promptLimitTokens"] == 524288, f"API 回显失真: {hit[:1]}"

        page.locator(f'.el-table__row:has-text("{name}")').locator('[data-test="llm-delete"]').click()
        page.locator('.el-message-box__btns button:has-text("删除")').click()
        expect(page.locator('.el-table__row').filter(has_text=name)).to_have_count(0, timeout=8000)
        page.goto(f"{BASE_URL}#/llm-configs", wait_until="domcontentloaded")
        expect(page.locator(f'.el-table__row:has-text("{name}")')).to_have_count(0)

        assert_no_errors(collectors, "E8 提示词上限 CRUD")
    finally:
        r = httpx.get(f"{API_BASE}/llm-configs", headers={"Authorization": f"Bearer {api_login(ADMIN_USERNAME, ADMIN_PASSWORD)}"}, timeout=10)
        for x in r.json().get("data", []):
            if x.get("name") in codes_cleanup:
                httpx.delete(f"{API_BASE}/llm-configs/{x['id']}",
                             headers={"Authorization": f"Bearer {api_login(ADMIN_USERNAME, ADMIN_PASSWORD)}"}, timeout=10)



CORE_INTERNAL_BASE = "http://localhost:8083/internal/llm-usage"


def _psql(sql):
    return subprocess.run(
        ["docker", "exec", "-i", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", sql],
        capture_output=True, text=True, timeout=30, check=True).stdout.strip()


def _post_sample(model, cached_tokens):
    pass
    resp = httpx.post(f"{CORE_INTERNAL_BASE}/samples", json={
        "llmConfigId": None, "provider": "deepseek", "model": model, "chain": "extract",
        "projectId": None, "estTokens": 1000, "usagePromptTokens": 1000,
        "completionTokens": 10, "latencyMs": 42, "tokenizerEngine": "jtokkit-cl100k",
        "cachedTokens": cached_tokens,
    }, timeout=10)
    assert resp.status_code == 200 and resp.json()["success"] is True, f"采样写入被拒: {resp.text[:200]}"


def test_e9_cached_tokens_two_states(logged_in, collectors, uniq):
    page = logged_in
    model = f"e2e-e9-{uniq}"
    try:
        _post_sample(model, 768)
        _post_sample(model, 1)
        assert _psql(f"SELECT count(*) FROM tp_llm_usage_sample WHERE model='{model}'") == "2", \
            "两行采样未落库——载荷约束面复查"

        nav_to(page, "成本中心", "/cost",
               '[data-test="cost-dashboard-page"]', submenu="系统管理")
        page.locator('[data-test="cost-tab-calibration"]').click()
        sample_table = page.locator('[data-test="usage-sample-table"]')
        expect(sample_table).to_be_visible(timeout=10000)

        page.locator('[data-test="usage-keyword"]').fill(model)
        with page.expect_response(lambda r: "/llm-usage/samples" in r.url and r.status == 200):
            page.locator('[data-test="usage-search"]').click()
        rows = sample_table.locator('.el-table__row').filter(has_text=model)
        expect(rows).to_have_count(2, timeout=8000)

        col = _col_index(sample_table, "缓存命中")
        cell_texts = [rows.nth(i).locator("td").nth(col).inner_text() for i in range(2)]
        assert sorted(cell_texts) == ["1", "768"], \
            f"两态渲染失真（768=千分位数值、1=最小命中）: {cell_texts}"

        th_sel = f'[data-test="usage-sample-table"] thead th:nth-child({col + 1})'
        with page.expect_response(lambda r: "sortBy=cached_tokens" in r.url and "sortDir=asc" in r.url):
            page.locator(th_sel).click()
        expect(rows.locator("td").nth(col).first).to_have_text("1", timeout=8000)

        with page.expect_response(lambda r: "sortBy=cached_tokens" in r.url and "sortDir=desc" in r.url):
            page.locator(th_sel).click()
        expect(rows.locator("td").nth(col).first).to_have_text("768", timeout=8000)

        cleared = False
        for _ in range(3):
            with page.expect_response(lambda r: "/llm-usage/samples" in r.url and r.status == 200) as ei:
                page.locator(th_sel).click()
            if "sortBy=" not in ei.value.url:
                cleared = True
                break
        assert cleared, "连点 3 次未回到无 sortBy 默认序（排序清除契约断裂）"
        expect(rows).to_have_count(2, timeout=8000)

        assert_no_errors(collectors, "E9 缓存命中两态与排序")
    finally:
        _psql("DELETE FROM tp_llm_usage_sample WHERE model LIKE 'e2e-e9-%'")
        assert _psql(f"SELECT count(*) FROM tp_llm_usage_sample WHERE model LIKE 'e2e-e9-%'") == "0", \
            "本谱残留未清（R37：DELETE by marker，禁 TRUNCATE 出厂表）"
