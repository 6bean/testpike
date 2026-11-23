
from playwright.sync_api import expect

from conftest import assert_no_errors, nav_to, sql_query


def test_q6_retention_preview_and_run(logged_in, collectors, api_client, uniq):
    pass
    r = api_client.post("/projects", json={"name": f"保留清理E2E{uniq}", "code": f"R6E{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"过期任务用例{uniq}", "precondition": "无",
        "expectedResult": "符合预期", "priority": 1, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]
    sql_query("INSERT INTO tp_exec_task (project_id, case_id, status, created_at)"
              f" VALUES ({pid}, {cid}, 3, now() - interval '200 days')")

    page = logged_in
    try:
        nav_to(page, "系统配置", "/configs", '[data-test="config-page"]', submenu="系统管理")

        # Preview dialog now carries five retention classes (run-history
        # perf.run.retention-days row added; backend list is the single source,
        # the page mirrors it verbatim — five-class L2 assertions proven).
        page.locator('[data-test="config-retention-preview"]').click()
        dialog = page.locator('[data-test="retention-dialog"]')
        expect(dialog).to_be_visible()
        expect(page.locator('[data-test="retention-table"] tbody tr')).to_have_count(5, timeout=10000)
        expect(dialog.get_by_text("录屏产物")).to_be_visible()
        expect(dialog.get_by_text("训练样本")).to_be_visible()
        expect(dialog.get_by_text("压测运行")).to_be_visible()

        def exec_count():
            row = page.locator('[data-test="retention-table"] tbody tr').filter(has_text="执行历史")
            return int(row.locator('[data-test="retention-count"]').inner_text())

        assert exec_count() >= 1, f"过期任务预览条数应 ≥1, got {exec_count()}"
        pre_count = exec_count()

        page.locator('[data-test="retention-run"]').click()
        box = page.locator(".el-message-box")
        expect(box).to_be_visible()
        expect(box).to_contain_text("删除后不可恢复")
        box.locator('button:has-text("确定清理")').click()
        toast = page.locator(".el-message--success")
        expect(toast.first).to_be_visible(timeout=15000)
        expect(toast.first).to_contain_text("清理完成，共")

        expect(page.locator('[data-test="retention-table"] tbody tr').filter(has_text="执行历史")
               .locator('[data-test="retention-count"]')).to_have_text(str(pre_count), timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="config-page"]')).to_be_visible()
        page.locator('[data-test="config-retention-preview"]').click()
        expect(page.locator('[data-test="retention-dialog"]')).to_be_visible()
        row = page.locator('[data-test="retention-table"] tbody tr').filter(has_text="执行历史")
        expect(row.locator('[data-test="retention-count"]')).to_have_text("0", timeout=10000)
        page.locator('[data-test="retention-dialog"] button:has-text("关闭")').click()

        assert_no_errors(collectors, "Q6 保留清理预览与执行")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
        sql_query(f"DELETE FROM tp_exec_task WHERE project_id = {pid}")
