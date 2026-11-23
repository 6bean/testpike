
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    sql_query,
)


ADMIN_ID_SQL = "SELECT id FROM tp_user WHERE username='admin'"


def _seed_project_with_cases(api, uniq):
    pass
    r = api.post("/projects", json={
        "name": f"O7项目{uniq}", "code": f"O7E{uniq[:6]}", "description": "E2E O7"})
    assert r.status_code == 200, r.text
    pid = r.json()["data"]
    pid = pid["id"] if isinstance(pid, dict) else pid
    for i in (1, 2):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"O7用例{uniq}{i}", "precondition": "前置",
            "expectedResult": "预期", "priority": 2, "confidence": 2, "endType": "web",
            "phase": 1, "source": 2, "status": 1,
            "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}]})
        assert r.status_code == 200, r.text
    return pid, f"O7E{uniq[:6]}"


def _purge_project(api, pid):
    api.delete(f"/projects/{pid}/purge?force=true")


def _goto_dashboard(page):
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")


def test_o7_todo_cards_direct_nav(logged_in, collectors):
    pass
    page = logged_in
    _goto_dashboard(page)
    expect(page.locator("[data-test=dashboard-todo-cards]")).to_be_visible(timeout=10000)
    for card in ("todo-review-card", "todo-task-card", "todo-defect-card"):
        expect(page.locator(f"[data-test={card}] .stat-num")).not_to_have_text(
            "-", timeout=10000)

    page.locator("[data-test=todo-review-card]").click()
    page.wait_for_url("**/#/review", timeout=10000)
    _goto_dashboard(page)
    page.locator("[data-test=todo-task-card]").click()
    page.wait_for_url("**/#/execution", timeout=10000)
    _goto_dashboard(page)
    page.locator("[data-test=todo-defect-card]").click()
    page.wait_for_url("**/#/defects", timeout=10000)

    assert_no_errors(collectors, "O7 todo cards direct nav")


def test_o7_project_entry_and_activity(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, _code = _seed_project_with_cases(api_client, uniq)
    uid = int(sql_query(ADMIN_ID_SQL))
    try:
        sql_query(
            "INSERT INTO tp_notification (user_id, project_id, event_type, channel, title, "
            f"content, read_flag, dedup_key) VALUES ({uid}, {pid}, 'EXPORT_DONE', '站内', "
            f"'O7动态_{uniq}', 'O7E2E', 0, 'o7-e2e-{uniq}')")

        page.reload(wait_until="domcontentloaded")
        expect(page.locator("[data-test=dashboard-todo-cards]")).to_be_visible(timeout=15000)

        row = page.locator("[data-test=my-project-table] tbody tr",
                           has_text=f"O7项目{uniq}")
        expect(row).to_have_count(1, timeout=10000)
        expect(row.locator("[data-test=project-case-count]")).to_have_text("2")

        act = page.locator("[data-test=activity-item]").filter(
            has_text=f"O7动态_{uniq}").first
        expect(act).to_be_visible(timeout=10000)

        _goto_dashboard(page)
        expect(page.locator("[data-test=my-project-table] tbody tr",
                            has_text=f"O7项目{uniq}")).to_have_count(1, timeout=10000)

        page.locator("[data-test=activity-item]").filter(
            has_text=f"O7动态_{uniq}").first.click()
        page.wait_for_url(f"**/report?projectId={pid}", timeout=10000)

        assert_no_errors(collectors, "O7 project entry and activity")
    finally:
        sql_query(f"DELETE FROM tp_notification WHERE dedup_key = 'o7-e2e-{uniq}'")
        _purge_project(api_client, pid)
