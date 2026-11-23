
import os
import subprocess

import httpx
import pytest
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, attach_monitors, mk_tester, add_member, wait_tasks_settled

DB_USER = os.environ.get("DB_USERNAME", "testpike")


def inject_token(page):
    pass
    from conftest import ADMIN_PASSWORD, api_login
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError(
                    "inject_token 注入后未进入工作台（my-project-table 不可见）——"
                    "疑似 WSL 网络层抖动致 /users/me 失败被守卫踢回登录；已重试一次仍失败"
                )
    raise AssertionError("unreachable")


def sql(q):
    full = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", DB_USER,
                           "-d", "testpike", "-tAc", q], capture_output=True, text=True)
    if full.returncode != 0:
        raise RuntimeError(f"SQL 失败: {q} -> {full.stderr[:300]}")
    return full.stdout.strip()


def setup_report_dataset(api, uniq):
    pass
    code = f"L4E{uniq[:6]}"
    r = api.post("/projects", json={"name": f"L4总结报告项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    env_ids = []
    for name, etype in ((f"测试环境A{uniq[:6]}", 2), (f"预发环境B{uniq[:6]}", 3)):
        r = api.post(f"/projects/{pid}/envs", json={
            "name": name, "envType": etype, "baseUrl": f"https://t-{uniq[:6]}.tp-local.test"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_ids.append(r.json()["data"])

    case_ids = []
    for i in range(4):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"报告用例{i}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])

    r = api.post("/plans", json={
        "projectId": pid, "name": f"V2.0 全量回归{uniq[:6]}", "targetVersion": "v2.0.0",
        "envIds": env_ids,
        "caseScope": {"phases": [3], "priorities": [0, 1], "keyword": None, "tagIds": []},
        "entryCriteria": {"requireSmokePass": False},
        "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
        "planStart": "2026-09-01", "planEnd": "2026-09-10"})
    assert r.json().get("code") == "0", f"建计划失败: {r.text[:300]}"
    plan = r.json()["data"]
    plan_id = plan["id"]

    for cid in case_ids:
        sql(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
            f"VALUES ({cid}, 'v2', '{{\"caseId\": {cid}, \"setup\": [], \"steps\": []}}', 1, 1) "
            f"ON CONFLICT (case_id) DO NOTHING")

    tester, _ = mk_tester(api, uniq, prefix="l4t")
    add_member(api, pid, tester)
    r = api.post(f"/plans/{plan_id}/assign", json={"caseIds": case_ids, "assigneeId": tester})
    assert r.json().get("code") == "0" and r.json()["data"] == 4, f"分配失败: {r.text[:300]}"
    assert api.post(f"/plans/{plan_id}/publish").json().get("code") == "0", "发布失败"
    gen = api.post(f"/plans/{plan_id}/generate-batches").json()["data"]
    assert gen["batchCount"] == 2 and gen["taskCount"] == 4, f"生成批次失败: {gen}"

    wait_tasks_settled(plan_id)
    pc = sql(f"SELECT id FROM tp_plan_case WHERE plan_id = {plan_id} ORDER BY id").splitlines()
    sql(f"UPDATE tp_plan_case SET status = 3, updated_at = now() WHERE id = {pc[0]}")
    sql(f"UPDATE tp_plan_case SET status = 3, updated_at = now() WHERE id = {pc[1]}")
    sql(f"UPDATE tp_plan_case SET status = 4, updated_at = now() WHERE id = {pc[2]}")
    sql(f"UPDATE tp_plan_case SET status = 1, updated_at = now() WHERE id = {pc[3]}")
    sql(f"UPDATE tp_exec_batch SET env_version_snapshot = 'v2.0.1' WHERE plan_id = {plan_id} AND id = "
        f"(SELECT min(id) FROM tp_exec_batch WHERE plan_id = {plan_id})")
    sql(f"UPDATE tp_exec_batch SET env_version_snapshot = 'v2.0.2' WHERE plan_id = {plan_id} AND id = "
        f"(SELECT max(id) FROM tp_exec_batch WHERE plan_id = {plan_id})")
    for i, line in enumerate(sql(f"SELECT id FROM tp_exec_task WHERE batch_id IN "
                                 f"(SELECT id FROM tp_exec_batch WHERE plan_id = {plan_id}) ORDER BY id").splitlines()):
        st = 3 if i in (0, 1, 3) else 4
        sql(f"UPDATE tp_exec_task SET status = {st}, finished_at = now() WHERE id = {line}")
    sql(f"INSERT INTO tp_defect (project_id, case_id, plan_id, title, severity, status, reporter_id) "
        f"VALUES ({pid}, {case_ids[2]}, {plan_id}, '登录失败缺陷', 1, 1, 1)")
    sql(f"INSERT INTO tp_defect (project_id, case_id, plan_id, title, severity, status, reporter_id) "
        f"VALUES ({pid}, {case_ids[0]}, {plan_id}, '支付超时缺陷', 2, 2, 1)")
    sql(f"INSERT INTO tp_defect (project_id, case_id, plan_id, title, severity, status, reporter_id) "
        f"VALUES ({pid}, {case_ids[1]}, {plan_id}, '文案错误缺陷', 3, 5, 1)")
    return pid, plan_id, f"V2.0 全量回归{uniq[:6]}"


def cleanup_project(api, pid):
    api.delete(f"/projects/{pid}")


def test_l4_report_generate_detail_export(page, collectors, api_client, uniq):
    pass
    pid, plan_id, plan_name = setup_report_dataset(api_client, uniq)
    try:
        r = api_client.post("/reports/generate", json={
            "projectId": pid, "rangeType": 1, "planId": plan_id, "title": None})
        assert r.json().get("code") == "0", f"生成报告失败: {r.text[:300]}"
        report = r.json()["data"]
        report_id = report["id"]

        d = api_client.get(f"/reports/{report_id}").json()["data"]
        assert d["results"]["passed"] == 2 and d["results"]["failed"] == 1 \
            and d["results"]["notRun"] == 1, f"结果聚合错误: {d['results']}"
        assert abs(d["results"]["passRate"] - 66.7) < 0.05, f"通过率错误: {d['results']['passRate']}"
        assert d["defectDist"]["total"] == 3 and d["defectDist"]["openCount"] == 2, \
            f"缺陷分布错误: {d['defectDist']}"
        assert len(d["envVersions"]) == 2 and d["conclusion"]["pass"] is False, "环境/结论错误"

        from conftest import ADMIN_PASSWORD, api_login
        admin_token = api_login("admin", ADMIN_PASSWORD)
        with httpx.Client(base_url=API_BASE, timeout=30) as c:
            ah = {"Authorization": f"Bearer {admin_token}"}
            xlsx = c.get(f"/reports/{report_id}/export", params={"format": "xlsx"}, headers=ah).content
            assert xlsx[:4] == b"PK\x03\x04", f"xlsx 魔数错误: {xlsx[:4]!r}"
            pdf = c.get(f"/reports/{report_id}/export", params={"format": "pdf"}, headers=ah).content
            assert pdf[:4] == b"%PDF", f"pdf 魔数错误: {pdf[:4]!r}"

        # Header global context : the in-page project selector is retired —
        # deep-link ?projectId lands the report list on this project directly.
        inject_token(page)
        page.goto(f"{BASE_URL}/#/test-report?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="test-report-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="report-title-link"]').first).to_contain_text(plan_name, timeout=15000)

        # reload persistence: re-open the same deep link → report still listed
        page.goto(f"{BASE_URL}/#/test-report?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="report-title-link"]').first).to_contain_text(plan_name, timeout=15000)

        page.locator('[data-test="report-title-link"]').first.click()
        expect(page.locator('[data-test="report-detail-drawer"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="report-result-stats"]')).to_contain_text("66.7%")
        expect(page.locator('[data-test="report-defect-summary"]')).to_contain_text("3")
        expect(page.locator('[data-test="report-defect-summary"]')).to_contain_text("2")
        expect(page.locator('[data-test="report-env-table"]')).to_contain_text("v2.0.1")
        expect(page.locator('[data-test="report-conclusion"]')).to_contain_text("未达标")

        assert_no_errors(collectors, "L4 报告生成/详情/导出")
    finally:
        cleanup_project(api_client, pid)


def test_l4_report_generate_dialog(page, collectors, api_client, uniq):
    pass
    pid, plan_id, plan_name = setup_report_dataset(api_client, uniq)
    try:
        # Header global context : deep-link ?projectId lands the list here.
        inject_token(page)
        page.goto(f"{BASE_URL}/#/test-report?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="report-table"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="report-generate-btn"]').click()
        expect(page.locator('[data-test="report-gen-dialog"]')).to_be_visible()
        expect(page.locator('[data-test="report-gen-plan"]')).to_be_visible()
        page.locator('[data-test="report-gen-plan"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("{plan_name}"):visible').first.click()
        page.locator('[data-test="report-gen-title"]').fill(f"E2E生成报告{uniq[:6]}")
        page.locator('[data-test="report-gen-submit"]').click()

        expect(page.locator('[data-test="report-detail-drawer"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="report-conclusion"]')).to_be_visible()
        page.keyboard.press("Escape")
        expect(page.locator('[data-test="report-detail-drawer"]')).to_be_hidden(timeout=5000)

        # reload persistence: the deep link still lists the E2E-generated report
        page.goto(f"{BASE_URL}/#/test-report?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="report-table"]')).to_contain_text(f"E2E生成报告{uniq[:6]}", timeout=15000)

        assert_no_errors(collectors, "L4 生成弹窗")
    finally:
        cleanup_project(api_client, pid)


def test_l4_report_permission_403(api_client, uniq):
    pass
    pid, plan_id, plan_name = setup_report_dataset(api_client, uniq)
    try:
        tester_id, _ = mk_tester(api_client, uniq, prefix="l4p")
        tester_user = f"l4ptester_{uniq}"
        add_member(api_client, pid, tester_id)
        with httpx.Client(base_url=API_BASE, timeout=15) as c:
            r = c.post("/auth/login", json={"username": tester_user, "password": "Test@12345"})
            assert r.json().get("code") == "0", f"tester 登录失败: {r.text[:200]}"
            mt = r.json()["data"]["accessToken"]
            mh = {"Authorization": f"Bearer {mt}"}
            r = c.post("/reports/generate", headers=mh, json={
                "projectId": pid, "rangeType": 1, "planId": plan_id, "title": "越权"})
            assert r.json().get("code") == "40301", f"普通成员生成应 403: {r.text[:200]}"
            mk_tester(api_client, uniq, prefix="l4o")
            r = c.post("/auth/login", json={"username": f"l4otester_{uniq}", "password": "Test@12345"})
            ot = r.json()["data"]["accessToken"]
            oh = {"Authorization": f"Bearer {ot}"}
            r = c.get("/reports", headers=oh, params={"projectId": pid})
            assert r.json().get("code") == "40301", f"非成员查看应 403: {r.text[:200]}"
    finally:
        cleanup_project(api_client, pid)
