
import json
import uuid

import httpx
import pytest
from playwright.sync_api import expect

from conftest import api_client
from conftest import assert_no_errors, logged_in, sql_query, uniq  # noqa: F401


pytestmark = pytest.mark.timeout(180)

CORE = "http://localhost:8083"


def _mk_project(api, uid):
    r = api.post("/projects", json={"name": f"B5项目_{uid}", "code": f"B5{uid[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uid, title="登录功能验证"):
    r = api.post("/cases", json={
        "projectId": pid,
        "title": f"{title}_{uid}",
        "precondition": f"前置：已登录系统（{uid}）",
        "expectedResult": f"预期：操作成功（{uid}）",
        "priority": 0,
        "confidence": 1,
        "endType": "web",
        "status": 1,
        "steps": [{"stepNo": 1, "description": "打开页面 /login", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _action_json(case_id, title, step_count):
    pass
    steps = [{"stepId": i, "action": "goto", "target": {"css": f"#el-{i}"},
              "params": {"url": "http://demo/login"}} for i in range(1, step_count + 1)]
    return json.dumps({
        "version": "v2", "caseId": case_id, "title": title, "endType": "web",
        "steps": steps,
        "env": {"ENV": "dev", "PACK": "retail-pack-v1", "DATA": "pool-a"},
    }, ensure_ascii=False)


def _save_script(case_id, title, step_count):
    pass
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": case_id, "schemaVersion": "v2",
                         "actionJson": _action_json(case_id, title, step_count),
                         "generatedBy": 1})
    assert r.status_code == 200, f"save 脚本失败: {r.status_code} {r.text[:200]}"
    assert r.json().get("code") == "0", f"save 脚本业务失败: {r.text[:200]}"


def _open_detail(page, case_id):
    page.goto(f"{page.url.split('#')[0]}#/cases/{case_id}", wait_until="domcontentloaded")


def _purge(api, pid, case_ids):
    pass
    if case_ids:
        ids = ",".join(str(i) for i in case_ids)
        sql_query(f"DELETE FROM tp_script_version WHERE case_id IN ({ids})")
    if pid:
        api.delete(f"/projects/{pid}/purge?force=true")


def test_b5_script_versions_diff(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq)

        _save_script(case_id, f"登录场景首版_{uniq}", 2)
        _save_script(case_id, f"登录场景改版_{uniq}", 3)

        _open_detail(page, case_id)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"登录功能验证_{uniq}")

        table = page.locator('[data-test="script-version-table"]')
        expect(table).to_be_visible()
        version_cells = page.locator('[data-test="script-version-no"]')
        expect(version_cells).to_have_count(2)
        assert version_cells.nth(0).inner_text() == "v2", "版本线非降序"
        assert version_cells.nth(1).inner_text() == "v1", "版本线非降序"
        expect(page.locator('[data-test="script-version-count"]')).to_contain_text("2")

        reasons = page.locator('[data-test="script-version-reason"]')
        assert reasons.nth(0).inner_text() == "脚本保存", f"原因 tag: {reasons.nth(0).inner_text()}"
        assert reasons.nth(1).inner_text() == "脚本保存"
        anchors = page.locator('[data-test="script-version-case-anchor"]')
        assert anchors.nth(0).inner_text() == "用例 v1"

        assert page.locator('[data-test="script-version-diff-1"]').is_disabled(), "线首 diff 未禁用"
        page.locator('[data-test="script-version-diff-2"]').click()

        dialog = page.locator('[data-test="script-version-diff-dialog"]')
        expect(dialog).to_be_visible()
        expect(dialog).to_contain_text("脚本变更对比（v1 → v2）")
        diff_table = dialog.locator('[data-test="script-version-diff-table"]')
        expect(diff_table).to_be_visible()
        assert "测试目标" in diff_table.inner_text(), "缺 title 中文 label（FIELD_LABELS=title→测试目标）"
        assert "操作步骤" in diff_table.inner_text(), "缺 steps 中文 label（FIELD_LABELS=steps→操作步骤）"

        before_cells = dialog.locator('[data-test="script-diff-before"]')
        after_cells = dialog.locator('[data-test="script-diff-after"]')
        before_text = before_cells.first.inner_text()
        assert f"登录场景首版_{uniq}" in before_text, f"title before 渲染: {before_text}"
        after_text = after_cells.first.inner_text()
        assert f"登录场景改版_{uniq}" in after_text, f"title after 渲染: {after_text}"
        steps_before = before_cells.nth(1).inner_text()
        steps_after = after_cells.nth(1).inner_text()
        assert "1. goto" in steps_before and "2. goto" in steps_before, f"steps before: {steps_before}"
        assert "3. goto" not in steps_before, "steps before 泄漏第 3 条"
        assert "3. goto" in steps_after, f"steps after: {steps_after}"
        assert '定位={"css":"#el-3"}' in steps_after, f"定位渲染: {steps_after}"

        page.keyboard.press("Escape")
        _open_detail(page, case_id)
        expect(page.locator('[data-test="script-version-no"]')).to_have_count(2)

        assert_no_errors(collectors, "B5 脚本版本线 diff")
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])


def test_b5_no_script_empty_state(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq, title="无脚本用例")

        _open_detail(page, case_id)
        expect(page.locator('[data-test="detail-title"]')).to_contain_text(f"无脚本用例_{uniq}")
        expect(page.locator('[data-test="detail-script"]')).to_be_visible()
        assert page.locator('[data-test="script-version-table"]').count() == 0, "无脚本用例渲染了版本表"
        assert page.locator('[data-test="script-version-empty"]').count() == 0, "空版本区块误渲染"
        assert_no_errors(collectors, "B5 无脚本空态")
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])


def test_b5_version_rows_purged(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_id = _mk_case(api_client, pid, uniq, title="残留守卫用例")
        _save_script(case_id, f"守卫脚本_{uniq}", 1)
        assert sql_query(
            f"SELECT count(*) FROM tp_script_version WHERE case_id = {case_id}") == "1", "快照行未写入"
    finally:
        _purge(api_client, pid, [case_id] if case_id else [])
    assert sql_query(
        f"SELECT count(*) FROM tp_script_version WHERE case_id = {case_id}") == "0", "版本行残留"
    assert sql_query(
        f"SELECT count(*) FROM tp_project WHERE id = {pid}") == "0", "项目残留"
    assert_no_errors(collectors, "B5 零残留")
