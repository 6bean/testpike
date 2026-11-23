


from conftest import (
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


def _sql(q: str) -> str:
    out = sql_query(q)
    return out


def _make_project(api_client, uniq: str, tag: str) -> tuple[int, str]:
    name = f"E3看板{tag}_{uniq}"
    code = f"E3V{tag}{uniq.upper()}"[:16]
    r = api_client.post("/projects", json={"name": name, "code": code})
    assert r.status_code == 200, f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], name


def _select_project(page, name: str) -> None:
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{name}")').first
    expect(option).to_be_visible(timeout=10000)
    option.click()


def _cleanup(pid: int) -> None:
    pass
    _sql(f"DELETE FROM tp_ai_call_log WHERE project_id={pid}")
    _sql(f"DELETE FROM tp_cost_budget WHERE project_id={pid}")
    _sql(f"DELETE FROM tp_user_project WHERE project_id={pid}")
    _sql(f"DELETE FROM tp_project WHERE id={pid}")


def _seed_cost_now_month(pid: int, amount: str) -> None:
    pass
    _sql(
        "INSERT INTO tp_ai_call_log (project_id, operator_id, provider, model, function,"
        " prompt_tokens, completion_tokens, cost, latency_ms, created_at)"
        f" VALUES ({pid}, (SELECT id FROM tp_user WHERE username='admin'),"
        f" 'e2e', 'e2e-cost-model', 'gen', 10, 5, {amount}, 100,"
        " date_trunc('month', now()) + interval '1 hour')"
    )



def test_cost_page_smoke(logged_in, collectors):
    page = logged_in
    nav_to(page, "成本中心", "/cost", '[data-test="cost-dashboard-page"]', submenu="系统管理")
    expect(page.locator('[data-test="cost-total"]')).to_be_visible(timeout=10000)
    for dim in ("project", "model", "caller", "day"):
        expect(page.locator(f'[data-test="cost-dim-{dim}"]')).to_be_visible()
    assert_no_errors(collectors, "成本看板页冒烟")



def test_cost_budget_override_persist(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, name = _make_project(api_client, uniq, "B")
    try:
        _seed_cost_now_month(pid, "1.50")
        nav_to(page, "成本中心", "/cost", '[data-test="cost-dashboard-page"]', submenu="系统管理")
        _select_project(page, name)

        page.click('[data-test="cost-budget-open"]')
        dialog = page.locator('[data-test="cost-budget-dialog"]')
        expect(dialog).to_be_visible()
        dialog.locator('[data-test="cost-budget-yuan"] input').fill("5.00")
        dialog.locator('[data-test="cost-budget-alert"] input').fill("30")
        with page.expect_response(
            lambda r: f"/api/v1/cost/budget/{pid}" in r.url and r.status == 200
        ):
            dialog.locator('[data-test="cost-budget-submit"]').click()

        assert _sql(f"SELECT budget_cents FROM tp_cost_budget WHERE project_id={pid}") == "500"

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="cost-dashboard-page"]')).to_be_visible(timeout=10000)
        _select_project(page, name)
        expect(page.locator('[data-test="cost-budget"]')).to_contain_text("¥5.00", timeout=10000)
        alert_banner = page.locator('[data-test="cost-banner-alert"]')
        expect(alert_banner).to_be_visible(timeout=10000)
        assert "1.50" in alert_banner.inner_text() and "5.00" in alert_banner.inner_text()
        assert_no_errors(collectors, "预算覆写持久化")
    finally:
        _cleanup(pid)



def test_cost_break_and_override_flow(logged_in, collectors, api_client, uniq):
    page = logged_in
    pid, name = _make_project(api_client, uniq, "K")
    try:
        _seed_cost_now_month(pid, "1.50")
        r = api_client.put(f"/cost/budget/{pid}", json={"budgetCents": 100, "alertPct": 80})
        assert r.status_code == 200, r.text[:200]

        nav_to(page, "成本中心", "/cost", '[data-test="cost-dashboard-page"]', submenu="系统管理")
        _select_project(page, name)
        banner = page.locator('[data-test="cost-banner-break"]')
        expect(banner).to_be_visible(timeout=10000)
        assert "熔断" in banner.inner_text()

        page.click('[data-test="cost-override-open"]')
        dialog = page.locator('[data-test="cost-override-dialog"]')
        expect(dialog).to_be_visible()

        posts_before = _sql(
            "SELECT count(*) FROM tp_cost_budget WHERE project_id="
            f"{pid} AND break_override_until IS NOT NULL")
        dialog.locator('[data-test="cost-override-submit"]').click()
        expect(page.get_by_text("请填写恢复理由").first).to_be_visible(timeout=5000)
        expect(dialog).to_be_visible()
        assert _sql(
            "SELECT count(*) FROM tp_cost_budget WHERE project_id="
            f"{pid} AND break_override_until IS NOT NULL") == posts_before

        dialog.locator('[data-test="cost-override-reason"]').fill(
            f"e2e 熔断恢复验证 {uniq}")
        with page.expect_response(
            lambda r: f"/api/v1/cost/break-override/{pid}" in r.url and r.status == 200
        ):
            dialog.locator('[data-test="cost-override-submit"]').click()
        expect(page.locator('[data-test="cost-override-dialog"]')).to_be_hidden(timeout=10000)
        until = _sql(
            "SELECT to_char(break_override_until, 'YYYY-MM-DD') || '|' ||"
            " COALESCE(break_override_reason,'') "
            f"FROM tp_cost_budget WHERE project_id={pid}")
        assert f"|e2e 熔断恢复验证 {uniq}" in until, f"override 留痕异常: {until}"
        assert_no_errors(collectors, "熔断→审批式恢复链")
    finally:
        _cleanup(pid)
