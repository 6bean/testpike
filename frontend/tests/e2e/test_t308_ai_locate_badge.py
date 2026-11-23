
import json

import pytest
from conftest import (
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "符合预期",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "AI定位展示", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _mk_task_with_timeline(pid, case_id, status, timeline_rows):
    pass
    tl = json.dumps(timeline_rows, ensure_ascii=False).replace("'", "''")
    err_type = "4" if status == 4 else "NULL"
    err_detail = "'AI 视觉定位兜底造种'" if status == 4 else "NULL"
    tid = int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, error_type, "
        "error_detail, steps_timeline, created_at, updated_at) "
        f"VALUES ({pid}, {case_id}, {status}, 6, {err_type}, {err_detail}, "
        f"'{tl}'::jsonb, now(), now()) RETURNING id").splitlines()[0])
    return tid


def _ai_locate_timeline(uniq):
    pass
    return [
        {"stepNo": 1, "action": "app.launch", "status": "ok", "startAt": "2026-10-02T10:00:00"},
        {"stepNo": 2, "action": "app.tap", "status": "ok", "startAt": "2026-10-02T10:00:05",
         "aiLocate": {"tier": "direct", "score": 0.92, "badge": "ai-located"}},
        {"stepNo": 3, "action": "app.tap", "status": "ok", "startAt": "2026-10-02T10:00:09",
         "aiLocate": {"tier": "selfcheck", "score": 0.71, "selfcheck": "frame-changed"}},
        {"stepNo": 4, "action": "app.tap", "status": "fail", "startAt": "2026-10-02T10:00:12",
         "detail": f"第 4 步 app.tap AI 视觉定位步内自检不一致（置信 0.55 中档区），已停步并落定位证据包供复核_{uniq}",
         "locateEvidence": {
             "stepNo": 4, "action": "app.tap", "score": 0.55,
             "screenshotKey": None, "annotated": False,
             "candidates": [
                 {"source": "vision", "label": "提交按钮", "x": 280, "y": 620, "confidence": 0.55},
                 {"source": "a11y", "label": "com.demo:id/btn_submit", "text": "提交"},
             ]}},
    ]


def _open_detail(page, proj_name, tid):
    inject_token(page)
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(has_text=proj_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    table = page.locator('[data-test="exec-task-table"]')
    expect(table).to_be_visible(timeout=15000)
    row = table.locator("tbody tr").filter(has_text=f"#{tid}").first
    expect(row).to_be_visible(timeout=20000)
    row.locator('[data-test="exec-task-detail"]').click()
    dlg = page.locator('[data-test="exec-detail-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    return dlg


def test_t308_ai_locate_badge_and_evidence(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"T308定位_{uniq}", "code": f"T308{uniq.upper()}"}).json()["data"]
        case_id = _mk_case(api_client, pid, f"T308定位用例_{uniq}")
        tid = _mk_task_with_timeline(pid, case_id, 4, _ai_locate_timeline(uniq))
        plain_rows = [{"stepNo": 1, "action": "goto", "status": "ok",
                       "startAt": "2026-10-02T10:00:00"}]
        plain_id = _mk_task_with_timeline(pid, case_id, 3, plain_rows)

        dlg = _open_detail(page, f"T308定位_{uniq}", tid)
        timeline = dlg.locator('[data-test="exec-timeline"]')
        expect(timeline).to_be_visible(timeout=5000)
        expect(timeline.locator("tbody tr")).to_have_count(4)

        badge_direct = dlg.locator('[data-test="step-ai-badge-1"]')
        expect(badge_direct).to_be_visible(timeout=5000)
        expect(badge_direct).to_contain_text("AI 定位·已直点")
        badge_self = dlg.locator('[data-test="step-ai-badge-2"]')
        expect(badge_self).to_be_visible(timeout=5000)
        expect(badge_self).to_contain_text("AI 定位·自检通过")
        expect(dlg.locator('[data-test="step-ai-badge-0"]')).to_have_count(0)

        ev = dlg.locator('[data-test="exec-locate-evidence"]')
        expect(ev).to_be_visible(timeout=5000)
        expect(ev).to_contain_text("AI 定位证据包（事后复核）")
        expect(ev.locator('[data-test="locate-evidence-step-4"]')).to_be_visible()
        expect(ev.locator('[data-test="locate-evidence-hint"]')).to_contain_text(
            "已停步保留现场")
        cand = ev.locator('[data-test="locate-evidence-step-4"]')
        expect(cand).to_contain_text("视觉模型")
        expect(cand).to_contain_text("提交按钮")
        expect(cand).to_contain_text("(280, 620)")
        expect(cand).to_contain_text("无障碍树")
        expect(cand).to_contain_text("com.demo:id/btn_submit")
        expect(cand).to_contain_text("0.55")

        page.reload(wait_until="domcontentloaded")
        inject_token(page)
        dlg2 = _open_detail(page, f"T308定位_{uniq}", tid)
        expect(dlg2.locator('[data-test="step-ai-badge-1"]')).to_be_visible(timeout=5000)
        expect(dlg2.locator('[data-test="exec-locate-evidence"]')).to_be_visible()
        dlg2.locator('[data-test="exec-detail-close"]').click()
        expect(dlg2).to_be_hidden(timeout=5000)

        dlg3 = _open_detail(page, f"T308定位_{uniq}", plain_id)
        expect(dlg3.locator('[data-test="exec-timeline"] tbody tr')).to_have_count(1)
        expect(dlg3.locator('[data-test^="step-ai-badge-"]')).to_have_count(0)
        expect(dlg3.locator('[data-test="exec-locate-evidence"]')).to_have_count(0)
        dlg3.locator('[data-test="exec-detail-close"]').click()
        expect(dlg3).to_be_hidden(timeout=5000)

        assert_no_errors(collectors, "T308 AI 定位消费位")

        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code == 200, f"purge 失败: {r.text[:200]}"
        pid = None
        left = sql_query(f"SELECT count(*) FROM tp_exec_task WHERE case_id = {case_id}")
        assert int(left.splitlines()[0]) == 0, f"清理后任务残留: {left}"
    finally:
        if pid is not None:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:
                pass
