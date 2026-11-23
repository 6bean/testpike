
import httpx
import pytest
from playwright.sync_api import expect

from conftest import (
    API_BASE,
    TESTER_PASSWORD,
    api_login,
    assert_no_errors,
    inject_token,
    inject_user_token,
    mk_tester,
    sql_query,
)


pytestmark = pytest.mark.xdist_group("o6_notif_readall")

TYPES = ("AI_GEN_FAILED", "AI_GEN_DONE", "EXECUTOR_OFFLINE")


def _seed_notifs(uid, uniq):
    pass
    i = 0
    for et in TYPES:
        for read in (0, 1):
            sql_query(
                "INSERT INTO tp_notification (user_id, project_id, event_type, channel, title, "
                f"content, read_flag, dedup_key) VALUES ({uid}, NULL, '{et}', '站内', "
                f"'O6标题_{et}_{i}', 'O6E2E_{uniq}', {read}, 'o6-{uniq}-{i}')"
            )
            i += 1
    return f"o6-{uniq}"


def _cleanup(prefix):
    sql_query(f"DELETE FROM tp_notification WHERE dedup_key LIKE '{prefix}%'")


def _open_notif_center(page):
    pass
    page.locator("[data-test=notif-bell]").click()
    expect(page.locator("[data-test=notif-panel]")).to_be_visible(timeout=10000)
    page.locator("[data-test=notif-view-all]").click()
    page.wait_for_url("**/notifications", timeout=10000)
    expect(page.locator("[data-test=notif-center-page]")).to_be_visible(timeout=10000)


def _pick(page, test_id, label):
    page.locator(f"[data-test={test_id}]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=label).first.click()


def _uniq_rows(page, uniq):
    return page.locator("[data-test=notif-table] tbody tr.el-table__row").filter(has_text=uniq)


ADMIN_ID_SQL = "SELECT id FROM tp_user WHERE username='admin'"


def _admin_id():
    return int(sql_query(ADMIN_ID_SQL))


def test_o6_bell_jump_and_read_all(page, collectors, api_client, uniq):
    pass
    username = f"o6tester_{uniq}"
    uid, _ = mk_tester(api_client, uniq, prefix="o6")
    prefix = _seed_notifs(uid, uniq)
    try:
        inject_user_token(page, username, TESTER_PASSWORD)
        _open_notif_center(page)

        _pick(page, "notif-read-filter", "未读")
        expect(_uniq_rows(page, uniq)).to_have_count(3, timeout=10000)

        _pick(page, "notif-type-filter", "AI 生成失败")
        expect(_uniq_rows(page, uniq)).to_have_count(1, timeout=10000)

        read_all_btn = page.locator("[data-test=notif-read-all-btn]")
        expect(read_all_btn).to_be_enabled(timeout=10000)
        read_all_btn.click()
        confirm_box = page.locator(".el-message-box")
        expect(confirm_box).to_be_visible(timeout=5000)
        confirm_box.locator('button:has-text("全部已读")').click()
        expect(page.locator(".el-message--success")).to_contain_text("标记为已读", timeout=10000)

        expect(page.locator("[data-test=notif-bell] .el-badge__content")).to_be_hidden(timeout=10000)
        expect(read_all_btn).to_be_disabled(timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator("[data-test=notif-center-page]")).to_be_visible(timeout=10000)
        _pick(page, "notif-read-filter", "未读")
        expect(_uniq_rows(page, uniq)).to_have_count(0, timeout=10000)

        t = api_login(username, TESTER_PASSWORD)
        r = httpx.put(
            f"{API_BASE}/notifications/read-all",
            headers={"Authorization": f"Bearer {t}"},
            timeout=10,
        )
        assert r.status_code == 200 and r.json()["code"] == "0"
        assert r.json()["data"]["affected"] == 0, f"read-all 幂等失败: {r.text[:200]}"

        assert_no_errors(collectors, "O6 直达+筛选+全部已读")
    finally:
        _cleanup(prefix)
        api_client.delete(f"/users/{uid}")


def test_o6_single_read_and_goto(page, collectors, api_client, uniq):
    pass
    uid = _admin_id()
    r = api_client.post("/projects", json={
        "name": f"O6项目{uniq}", "code": f"O6E{uniq[:6]}", "description": "E2E O6"})
    assert r.status_code == 200, r.text
    pid_data = r.json()["data"]
    pid = pid_data["id"] if isinstance(pid_data, dict) else pid_data
    task_id = int(sql_query(
        "INSERT INTO tp_ai_task (project_id, operator_id, task_type, status, requirement_text, "
        "error_code, error_msg, created_at, updated_at) "
        f"VALUES ({pid}, {uid}, 1, 4, 'O6E2E_{uniq}', 'LLM_TIMEOUT', 'E2E 直达占位任务', now(), now()) "
        "RETURNING id"
    ).splitlines()[0])
    sql_query(
        "INSERT INTO tp_notification (user_id, project_id, event_type, channel, title, content, "
        f"read_flag, dedup_key) VALUES ({uid}, NULL, 'AI_GEN_FAILED', '站内', 'O6直达标题', "
        f"'O6E2E_{uniq}', 0, 'ai-gen-fail:{task_id}')"
    )
    try:
        inject_token(page)
        _open_notif_center(page)
        _pick(page, "notif-read-filter", "未读")
        row = _uniq_rows(page, uniq).first
        expect(row).to_be_visible(timeout=10000)

        row.locator("[data-test^=notif-mark-read-]").click()
        expect(row.locator(".state-read")).to_be_visible(timeout=10000)
        expect(row.locator("[data-test^=notif-mark-read-]")).to_have_count(0)

        row.locator("[data-test^=notif-goto-]").click()
        page.wait_for_url("**/ai/generate**", timeout=10000)
        expect(page.locator("[data-test=notif-bell]")).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "O6 单条已读+直达")
    finally:
        sql_query(f"DELETE FROM tp_notification WHERE content = 'O6E2E_{uniq}'")
        sql_query(f"DELETE FROM tp_ai_task WHERE requirement_text = 'O6E2E_{uniq}'")
        api_client.delete(f"/projects/{pid}/purge?force=true")
