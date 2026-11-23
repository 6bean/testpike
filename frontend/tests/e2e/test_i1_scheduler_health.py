
import re

from conftest import assert_no_errors, nav_to
from playwright.sync_api import expect


def test_scheduler_health_page_smoke(logged_in, collectors, api_client):
    page = logged_in
    nav_to(page, "调度健康卡", "/scheduler-health",
           '[data-test="scheduler-health-page"]', submenu="系统管理")
    expect(page.locator('[data-test="scheduler-health-table"]')).to_be_visible(timeout=10000)
    # Human-readable job catalog always renders rows. The expected count is read
# live from GET /scheduler/jobs (single source = SchedulerJobCatalog, same as
# the health page), so a catalog key addition never rots this line; the
# equality still catches pagination/filter truncation on the health page
# (history: hardcoded 31 went stale at 35 — catalog grew past it silently).
    rows = page.locator('[data-test="scheduler-health-table"] .el-table__row')
    total = int(re.search(r"\d+", page.locator(
        '[data-test="scheduler-health-pagination"] .el-pagination__total').inner_text()).group())
    jobs = api_client.get("/scheduler/jobs")
    assert jobs.status_code == 200 and jobs.json().get("code") == "0", \
        f"/scheduler/jobs 目录读取失败: {jobs.status_code} {jobs.text[:120]}"
    catalog_size = len(jobs.json()["data"])
    assert total == catalog_size, \
        f"调度健康卡在册数 {total} 与 /scheduler/jobs 目录数 {catalog_size} 不一致（分页/筛选截断候选）"
    expect(rows).to_have_count(min(total, 20), timeout=8000)
    badges = page.locator('[data-test="scheduler-health-status"]')
    expect(badges.first).to_be_visible()
    texts = badges.all_inner_texts()
    assert all(t in ("执行中", "空闲", "从未执行") for t in texts), f"状态徽标异常: {texts}"
    desc = page.locator('[data-test="scheduler-health-desc"]').first.inner_text()
    job_keys = [k.strip() for k in page.locator(
        '[data-test="scheduler-health-table"] .el-table__row td:nth-child(1)').all_inner_texts()]
    assert re.search(r"[\u4e00-\u9fff]", desc), f"首行说明非人话目录文案: {desc}"
    assert desc.strip() not in job_keys, f"首行说明为裸任务键: {desc}"
    assert_no_errors(collectors, "调度健康卡冒烟")


def test_scheduler_health_keyword_filter(logged_in, collectors):
    page = logged_in
    nav_to(page, "调度健康卡", "/scheduler-health",
           '[data-test="scheduler-health-page"]', submenu="系统管理")
    rows = page.locator('[data-test="scheduler-health-table"] .el-table__row')
    expect(rows).to_have_count(20, timeout=8000)
    kw = page.locator('[data-test="scheduler-health-keyword"]')
    kw.fill("报告")
    kw.press("Enter")
    expect(rows).to_have_count(3, timeout=8000)
    for txt in rows.all_inner_texts():
        assert "报告" in txt, f"筛选结果混入非匹配行: {txt}"
    kw.hover()
    page.locator('.el-input:has(input[data-test="scheduler-health-keyword"]) .el-input__clear').click()
    expect(rows).to_have_count(20, timeout=8000)
    assert_no_errors(collectors, "调度健康卡关键词筛选")


def test_scheduler_run_now(logged_in, collectors):
    page = logged_in
    nav_to(page, "调度健康卡", "/scheduler-health",
           '[data-test="scheduler-health-page"]', submenu="系统管理")
    page.locator('[data-test="scheduler-run-now-open"]').click()
    dialog = page.locator('[data-test="scheduler-run-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    page.locator('[data-test="scheduler-run-job-select"] input').click()
    options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible')
    expect(options.first).to_be_visible(timeout=5000)
    labels = options.all_inner_texts()
    assert any("成本额度临时调整到期清理" in x for x in labels), f"清单缺清理任务: {labels}"
    page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible', has_text="成本额度临时调整到期清理").first.click()
    page.locator('[data-test="scheduler-run-confirm"]').click()
    expect(page.locator('.el-message--success')).to_be_visible(timeout=30000)
    toast = page.locator('.el-message--success').inner_text()
    assert "成本额度临时调整到期清理" in toast or "任务已执行完成" in toast, \
        f"执行结果 toast 非人话: {toast}"
    assert_no_errors(collectors, "调度健康卡立即执行")
    expect(dialog).to_be_hidden(timeout=5000)
    expect(page.locator('[data-test="scheduler-health-table"] .el-table__row')).to_have_count(20, timeout=8000)
