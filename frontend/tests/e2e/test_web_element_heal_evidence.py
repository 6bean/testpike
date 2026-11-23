"""Self-heal evidence replay surface E2E .

Covers (real browser + real admin 8081 -> core 8083 read path, no mocks):
- Seeding: real API project/case/web element; execution task and heal
 evidence rows inserted by SQL (data plumbing, not the tested path — the
 executor relay that writes these rows is L3-proven separately; the tested
 surface here is the frontend consumption: badge pairing, inline anchor-diff
 expansion, the two-numbers-read-together summary line and the element
 drawer's "recent heals" reference read).
- Exec detail: healed step shows the badge; clicking it expands the old/new
 anchor diff (similarity / viewport / ledger revision); evidence whose step
 number is missing from the timeline lands in the orphan note, never
 silently dropped.
- Summary line pairs the heal rate with the assertion-mismatch count in one
 row (a high rate must never be reported alone).
- reload persistence (rows come from real tp_exec_step_heal_evidence).
- Contrast task without any evidence: zero badges, no diff block (existing
 reports render unchanged).
- Page Element Library edit dialog: recent-heals table reads the same rows
 through the bounded element door (no second copy of the numbers).
- Cleanup back to zero (purge project, evidence bounded by task cascade).
"""
import json
import re

import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _mk_case(api, pid, title):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "none",
        "expectedResult": "as expected",
        "priority": 1, "confidence": 2, "endType": "web", "status": 3, "phase": 2,
        "steps": [{"stepNo": 1, "description": "heal replay", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"case create failed: {r.text[:300]}"
    return r.json()["data"]["id"]


def _mk_task(pid, case_id, status, timeline_rows):
    """Insert an execution task with a steps_timeline jsonb (data plumbing)."""
    tl = json.dumps(timeline_rows, ensure_ascii=False).replace("'", "''")
    tid = int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, "
        f"steps_timeline, created_at, updated_at) VALUES ({pid}, {case_id}, {status}, 6, "
        f"'{tl}'::jsonb, now(), now()) RETURNING id").splitlines()[0])
    return tid


def _heal_timeline():
    """Five steps: one healed (step 2), one failed assertion (step 4) so the
    summary pairs rate 1/5 with mismatch 1; step numbers 1..5 exist."""
    return [
        {"stepNo": 1, "action": "goto", "status": "ok", "startAt": "2026-11-16T10:00:00"},
        {"stepNo": 2, "action": "click", "status": "ok", "startAt": "2026-11-16T10:00:04"},
        {"stepNo": 3, "action": "input", "status": "ok", "startAt": "2026-11-16T10:00:08"},
        {"stepNo": 4, "action": "assertText", "status": "fail", "startAt": "2026-11-16T10:00:12",
         "detail": "assertion text not found after self-heal run"},
        {"stepNo": 5, "action": "assertVisible", "status": "ok", "startAt": "2026-11-16T10:00:15"},
    ]


def _insert_heal_row(task_id, element_id, step_no, from_type, from_val, used_type, used_val,
                     similarity, viewport, revision):
    step_sql = "NULL" if step_no is None else str(step_no)
    # Anchor values are free text (xpath carries single quotes); double them
    # so the SQL literal stays valid — same escaping _mk_task applies.
    from_val = from_val.replace("'", "''")
    used_val = used_val.replace("'", "''")
    sql_query(
        "INSERT INTO tp_exec_step_heal_evidence (task_id, element_id, step_no, healed, "
        "first_anchor_failed, from_anchor_type, from_anchor_value, used_anchor_type, "
        "used_anchor_value, viewport, ledger_revision, similarity_score, created_at) "
        f"VALUES ({task_id}, {element_id}, {step_sql}, true, true, "
        f"'{from_type}', '{from_val}', '{used_type}', '{used_val}', "
        f"'{viewport}', {revision}, {similarity}, now()) RETURNING id")


def _open_exec_detail(page, proj_name, tid):
    inject_token(page)
    nav_to(page, "执行与试运行", "/execution", '[data-test="execution-root"]', submenu="执行测试")
    page.locator('[data-test="project-ctx-select"]').click()
    option = page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible") \
        .filter(has_text=proj_name).first
    expect(option).to_be_visible(timeout=5000)
    option.click()
    table = page.locator('[data-test="exec-task-table"]')
    expect(table).to_be_visible(timeout=15000)
    # Boundary-anchored match, not plain substring: has_text "#91779" also
    # hits row "#917790". (?!\d) pins the full id.
    row = table.locator("tbody tr").filter(
        has_text=re.compile(f"#{re.escape(str(tid))}(?!\\d)")).first
    expect(row).to_be_visible(timeout=20000)
    row.locator('[data-test="exec-task-detail"]').click()
    dlg = page.locator('[data-test="exec-detail-dialog"]')
    expect(dlg).to_be_visible(timeout=10000)
    return dlg


def test_web_element_heal_evidence_replay(logged_in, api_client, collectors, uniq):
    """Badge + inline anchor diff + two-number summary + orphan note + reload
    persistence + element drawer recent-heals reference read + cleanup."""
    page = logged_in
    pid = None
    tid = None
    try:
        pid = api_client.post("/projects", json={
            "name": f"healreplay_{uniq}", "code": f"HR{uniq.upper()}"}).json()["data"]
        case_id = _mk_case(api_client, pid, f"healreplay_case_{uniq}")

        # Ledger element through the real API (the recent-heals door reads by element id).
        r = api_client.post("/web-elements", json={
            "projectId": pid,
            "name": f"提交按钮_{uniq}",
            "pageUrl": f"{BASE_URL}/heal-demo-{uniq}",
            "anchors": [
                {"type": "css", "value": ".submit-old", "strength": 0.9},
                {"type": "xpath", "value": "//button[@id='submit']", "strength": 0.6},
            ]})
        assert r.status_code == 200 and r.json().get("code") == "0", f"element create failed: {r.text[:300]}"
        element = r.json()["data"]
        element_id, page_key = element["id"], element["pageKey"]

        tid = _mk_task(pid, case_id, 3, _heal_timeline())
        _insert_heal_row(tid, element_id, 2, "css", ".submit-old", "xpath",
                         "//button[@id='submit']", 0.87, "1920x1080", 3)
        _insert_heal_row(tid, element_id, 2, "text", "提交", "testid",
                         "btn-submit", 0.74, "1440x900", 3)
        # Orphan evidence: step number missing -> must surface in the orphan note.
        _insert_heal_row(tid, element_id, None, "css", ".orphan-old", "css",
                         ".orphan-new", 0.66, "1280x720", 2)
        plain_tid = _mk_task(pid, case_id, 3, [
            {"stepNo": 1, "action": "goto", "status": "ok", "startAt": "2026-11-16T11:00:00"}])

        # ── 1. Exec detail: paired summary numbers on one line ──
        proj_name = f"healreplay_{uniq}"
        dlg = _open_exec_detail(page, proj_name, tid)
        summary = dlg.locator('[data-test="exec-heal-summary"]')
        expect(summary).to_be_visible(timeout=5000)
        # rate 1/5 steps and mismatch 1 assertion step read together (never reported alone)
        txt = summary.text_content() or ""
        assert "自愈命中率" in txt and "20%" in txt, f"summary rate missing: {txt}"
        assert "1/5" in txt, f"summary touched-steps missing: {txt}"
        assert "断言失配 1 步" in txt, f"summary mismatch missing: {txt}"

        # ── 2. Badge on the healed step, dashes elsewhere; click expands the diff ──
        badge = dlg.locator('[data-test="step-heal-badge-2"]')
        expect(badge).to_be_visible(timeout=5000)
        expect(badge).to_contain_text("自愈命中")
        expect(dlg.locator('[data-test="step-heal-badge-1"]')).to_have_count(0)
        expect(dlg.locator('[data-test="exec-heal-diff"]')).to_have_count(0)
        badge.click()
        diff = dlg.locator('[data-test="heal-diff-step-2"]')
        expect(diff).to_be_visible(timeout=5000)
        expect(diff).to_contain_text("css: .submit-old → xpath: //button[@id='submit']")
        expect(diff).to_contain_text("text: 提交 → testid: btn-submit")
        expect(diff).to_contain_text("0.87")
        expect(diff).to_contain_text("1920x1080")
        expect(diff).to_contain_text("v3")
        # Orphan evidence is named, not silently dropped.
        expect(dlg.locator('[data-test="heal-diff-orphan"]')).to_be_visible(timeout=5000)
        expect(dlg.locator('[data-test="heal-diff-orphan"]')).to_contain_text("另有 1 条")

        # ── 3. reload persistence (rows come from the real evidence table) ──
        page.reload(wait_until="domcontentloaded")
        dlg2 = _open_exec_detail(page, proj_name, tid)
        expect(dlg2.locator('[data-test="step-heal-badge-2"]')).to_be_visible(timeout=5000)
        dlg2.locator('[data-test="step-heal-badge-2"]').click()
        expect(dlg2.locator('[data-test="heal-diff-step-2"]')).to_contain_text(
            ".submit-old", timeout=5000)
        dlg2.locator('[data-test="exec-detail-close"]').click()
        expect(dlg2).to_be_hidden(timeout=5000)

        # ── 4. Contrast task without evidence: zero badges, no diff block ──
        dlg3 = _open_exec_detail(page, proj_name, plain_tid)
        expect(dlg3.locator('[data-test^="step-heal-badge-"]')).to_have_count(0)
        expect(dlg3.locator('[data-test="exec-heal-diff"]')).to_have_count(0)
        dlg3.locator('[data-test="exec-detail-close"]').click()
        expect(dlg3).to_be_hidden(timeout=5000)

        assert_no_errors(collectors, "heal evidence replay (exec detail)")

        # ── 5. Page Element Library: edit dialog recent-heals reference read ──
        nav_to(page, "页面元素库", "/web-elements",
               '[data-test="web-element-ledger-page"]', submenu="设计用例")
        page.goto(
            f"{BASE_URL}/#/web-elements?projectId={pid}",
            wait_until="domcontentloaded")
        # Same-document hash navigation does not re-run page setup; a reload
        # boots a fresh document that consumes the deep-linked projectId
        # (posture = test_web_element_ledger_page). Token was injected by the
        # logged_in fixture and no auth-snapshot data changed since —
        # re-injecting here would navigate back to the dashboard and swallow
        # the deep link (E2E root cause).
        page.reload(wait_until="domcontentloaded")
        group = page.locator(f"[data-test='ledger-pages-table'] tbody tr").filter(
            has_text=page_key).first
        expect(group).to_be_visible(timeout=15000)
        icon = group.locator(".el-table__expand-icon")
        if "expand-icon--expanded" not in (icon.get_attribute("class") or ""):
            icon.click()
        inner = page.locator(f"[data-test='ledger-elements-{page_key}'] tr.el-table__row") \
            .filter(has_text=f"提交按钮_{uniq}").first
        expect(inner).to_be_visible(timeout=10000)
        inner.locator('[data-test="ledger-edit-anchors"]').click()
        dlg4 = page.locator('[data-test="ledger-anchors-dialog"]')
        expect(dlg4).to_be_visible(timeout=10000)
        recent = dlg4.locator('[data-test="ledger-heal-recent-table"]')
        expect(recent).to_be_visible(timeout=10000)
        expect(recent.locator("tbody tr")).to_have_count(3)
        expect(recent).to_contain_text("css: .submit-old → xpath: //button[@id='submit']")
        # The ledger drawer references the same rows; the title is present, no
        # self-owned numbers (empty/failure branches stay hidden by count).
        expect(dlg4.locator('[data-test="ledger-heal-recent-empty"]')).to_have_count(0)
        dlg4.locator('[data-test="ledger-anchors-cancel"]').click()
        expect(dlg4).to_be_hidden(timeout=5000)

        assert_no_errors(collectors, "heal evidence replay (element drawer)")

        # ── 6. Cleanup back to zero ──
        r = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert r.status_code == 200, f"purge failed: {r.text[:200]}"
        pid = None
        # Task row gone -> the FK cascade must have removed its evidence rows.
        task_left = sql_query(f"SELECT count(*) FROM tp_exec_task WHERE id = {tid}")
        assert int(task_left.splitlines()[0]) == 0, f"task survived purge: {task_left}"
        ev_left = sql_query(
            f"SELECT count(*) FROM tp_exec_step_heal_evidence WHERE task_id = {tid}")
        assert int(ev_left.splitlines()[0]) == 0, f"evidence rows leaked: {ev_left}"
        tid = None
    finally:
        if tid is not None:
            try:
                sql_query(f"DELETE FROM tp_exec_step_heal_evidence WHERE task_id = {tid}")
                sql_query(f"DELETE FROM tp_exec_task WHERE id = {tid}")
            except Exception:
                pass
        if pid is not None:
            try:
                api_client.delete(f"/projects/{pid}/purge?force=true")
            except Exception:
                pass
