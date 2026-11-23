
import base64
import json

import pytest
from conftest import (  # noqa: F401
    BASE_URL,
    assert_no_errors,
    s3_bucket,
    s3_client,
    executor_runner_control,
    inject_token,
    nav_to,
    probe_executor_runner_unit,
    sql_query,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")

APP_THREE_STEPS = [
    {"stepNo": 1, "action": "app.launch", "target": {"pkg": "com.demo.app"}},
    {"stepNo": 2, "action": "app.tap", "target": {"text": "登录"}},
    {"stepNo": 3, "action": "app.assertText", "value": "欢迎"},
]


def _s3():
    pass
    return s3_client()


def _mk_task(pid, case_id, status, schema_version, action_json=None, timeline=None, task_type=6):
    pass
    env = (action_json or json.dumps({"steps": APP_THREE_STEPS})).replace("'", "''")
    tl = "NULL"
    if timeline is not None:
        tl = f"'{json.dumps(timeline, ensure_ascii=False).replace(chr(39), chr(39)*2)}'::jsonb"
    err_type = "4" if status == 4 else "NULL"
    err_detail = "'第 2 步 app.tap 定位失败'" if status == 4 else "NULL"
    return int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, schema_version, "
        "action_json, error_type, error_detail, steps_timeline, created_at, updated_at) "
        f"VALUES ({pid}, {case_id}, {status}, {task_type}, '{schema_version}', '{env}', "
        f"{err_type}, {err_detail}, {tl}, now(), now()) RETURNING id").splitlines()[0])


def _mk_app_case(api, pid, title, uniq):
    pass
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无",
        "expectedResult": "换引擎重跑", "priority": 1, "confidence": 2,
        "endType": "web", "status": 3, "phase": 2,
        "automationTarget": "app", "appautoEngine": "MOBILEWRIGHT",
        "steps": [{"stepNo": i + 1, "description": s["action"], "isAssert": 0}
                  for i, s in enumerate(APP_THREE_STEPS)]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case_id = r.json()["data"]["id"]
    esc = json.dumps({"steps": APP_THREE_STEPS}, ensure_ascii=False)
    esc = esc.replace("\\", "\\\\").replace("'", "''")
    sql_query("INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
              f"VALUES ({case_id}, 'v2', '{esc}', 1, 1) "
              "ON CONFLICT (case_id) DO UPDATE SET action_json = EXCLUDED.action_json")
    return case_id


def _open_task_detail(page, proj_name, tid):
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


def test_t310_triptych_and_queue_honesty(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    runner_unit = probe_executor_runner_unit()
    executor_runner_control(runner_unit, "stop")
    try:
        pid = api_client.post("/projects", json={
            "name": f"T310现场_{uniq}", "code": f"T310A{uniq.upper()}"}).json()["data"]
        case_id = _mk_app_case(api_client, pid, f"T310现场用例_{uniq}", uniq)

        s3 = _s3()
        bucket = s3_bucket()
        key_shot = f"evid/{uniq}/shot.png"
        key_tree = f"evid/{uniq}/tree.json"
        key_log = f"evid/{uniq}/logtail.txt"
        s3.put_object(Bucket=bucket, Key=key_shot, Body=PNG_1PX, ContentType="image/png")
        tree_text = f'{{"device": "e2e-{uniq}", "hierarchy": {{"node": "com.demo:id/btn_login"}}}}'
        s3.put_object(Bucket=bucket, Key=key_tree, Body=tree_text.encode(),
                      ContentType="application/json")
        log_text = f"BEGIN app.logcat tail\nFATAL E2E-{uniq}: tap target not found\nEND"
        s3.put_object(Bucket=bucket, Key=key_log, Body=log_text.encode(), ContentType="text/plain")

        tid_ev = _mk_task(pid, case_id, 4, "appauto-v1", timeline=[
            {"stepNo": 1, "action": "app.launch", "status": "ok"},
            {"stepNo": 2, "action": "app.tap", "status": "fail",
             "detail": f"第 2 步 app.tap 失败_{uniq}",
             "evidence": {"screenshot": key_shot, "view_tree": key_tree, "log_tail": key_log}},
        ])
        tid_no = _mk_task(pid, case_id, 4, "appauto-v1", timeline=[
            {"stepNo": 1, "action": "app.tap", "status": "fail", "detail": "无证据失败"}])
        dev = api_client.post("/appauto/devices", json={
            "projectId": pid, "name": f"T310设备_{uniq}", "serial": f"E2ET310{uniq}",
            "deviceType": "EMULATOR"}).json()["data"]
        holder = f"e2e-holder-{uniq}"
        sql_query("INSERT INTO tp_appauto_lease_run (device_id, project_id, holder_executor_id,"
                  " state, acquired_at, expires_at) "
                  f"VALUES ({dev}, {pid}, '{holder}', 'ACTIVE', now(), now() + interval '30 minutes')")
        tid_q = _mk_task(pid, case_id, 1, "appauto-v1")
        tid_web = _mk_task(pid, case_id, 2, "v2", task_type=1,
                           action_json=json.dumps({"steps": [{"stepNo": 1, "action": "goto"}]}))

        dlg = _open_task_detail(page, f"T310现场_{uniq}", tid_ev)
        tri = dlg.locator('[data-test="exec-triptych"]')
        expect(tri).to_be_visible(timeout=10000)
        expect(tri).to_contain_text("失败现场三联图")
        step2 = tri.locator('[data-test="triptych-step-2"]')
        expect(step2).to_be_visible()
        shot_img = step2.locator('[data-test="triptych-pane-screenshot"] img')
        expect(shot_img).to_be_visible(timeout=10000)
        src = shot_img.get_attribute("src") or ""
        assert key_shot.split("/")[-1] in src, f"截图 src 未指向本对象: {src[:160]}"
        step2.locator('[data-test="triptych-text-toggle-viewTree"]').click()
        pane_txt = step2.locator('[data-test="triptych-pane-text"]').first
        expect(pane_txt).to_contain_text(f"e2e-{uniq}", timeout=10000)
        step2.locator('[data-test="triptych-text-toggle-logTail"]').click()
        expect(step2.locator('[data-test="triptych-pane-text"]').last).to_contain_text(
            f"FATAL E2E-{uniq}", timeout=10000)
        rec_pane = step2.locator('[data-test="triptych-pane-record"]')
        expect(rec_pane).to_be_visible()
        expect(rec_pane).to_contain_text("录屏切片缺位（执行器未上传该件，诚实占位）")
        expect(step2.locator('[data-test="triptych-record-video"]')).to_have_count(0)
        dlg.locator('[data-test="exec-detail-close"]').click()
        expect(dlg).to_be_hidden(timeout=5000)

        dlgq = _open_task_detail(page, f"T310现场_{uniq}", tid_q)
        q = dlgq.locator('[data-test="queue-honesty"]')
        expect(q).to_be_visible(timeout=10000)
        expect(q).to_contain_text("排队与设备占用")
        reasons = dlgq.locator('[data-test="queue-reasons"]')
        okface = dlgq.locator('[data-test="queue-reason-ok"]')
        expect(reasons.or_(okface)).to_have_count(1, timeout=10000)
        if reasons.count():
            expect(reasons.locator("li").first).to_contain_text("执行器")
        leases = dlgq.locator('[data-test="queue-leases"]')
        expect(leases).to_contain_text(f"T310设备_{uniq}", timeout=10000)
        expect(leases).to_contain_text(holder)
        page.reload(wait_until="domcontentloaded")
        dlgq2 = _open_task_detail(page, f"T310现场_{uniq}", tid_q)
        expect(dlgq2.locator('[data-test="queue-leases"]')).to_contain_text(holder, timeout=10000)
        dlgq2.locator('[data-test="exec-detail-close"]').click()
        expect(dlgq2).to_be_hidden(timeout=5000)

        dlgn = _open_task_detail(page, f"T310现场_{uniq}", tid_no)
        expect(dlgn.locator('[data-test="exec-triptych"]')).to_have_count(0)
        expect(dlgn.locator('[data-test="queue-honesty"]')).to_have_count(0)
        dlgn.locator('[data-test="exec-detail-close"]').click()
        expect(dlgn).to_be_hidden(timeout=5000)

        dlgo = _open_task_detail(page, f"T310现场_{uniq}", tid_web)
        expect(dlgo.locator('[data-test="queue-honesty"]')).to_have_count(0)
        dlgo.locator('[data-test="exec-detail-close"]').click()
        expect(dlgo).to_be_hidden(timeout=5000)

        assert_no_errors(collectors, "T310 三联图+排队诚实首 E2E")
    finally:
        executor_runner_control(runner_unit, "start")
        sql_query(f"DELETE FROM tp_appauto_lease_run WHERE holder_executor_id = 'e2e-holder-{uniq}'")
        for k in (f"evid/{uniq}/shot.png", f"evid/{uniq}/tree.json", f"evid/{uniq}/logtail.txt"):
            _s3().delete_object(Bucket=s3_bucket(), Key=k)
        if pid is not None:
            r = api_client.delete(f"/projects/{pid}/purge?force=true")
            assert r.status_code == 200, f"清理失败: {r.text[:200]}"


def test_t310_engine_rerun_and_compare_view(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid = None
    try:
        sql_query("UPDATE tp_appauto_engine SET status = 1 WHERE code = 'HYPIUM'")
        pid = api_client.post("/projects", json={
            "name": f"T310换引擎_{uniq}", "code": f"T310B{uniq.upper()}"}).json()["data"]
        case_id = _mk_app_case(api_client, pid, f"T310换引擎用例_{uniq}", uniq)
        t_app = _mk_task(pid, case_id, 4, "appauto-v1",
                         action_json=json.dumps({"kind": "app-auto-run",
                                                 "engine": {"code": "MOBILEWRIGHT",
                                                            "name": "Mobilewright", "status": 1},
                                                 "steps": APP_THREE_STEPS}, ensure_ascii=False))
        _mk_task(pid, case_id, 3, "v2", task_type=1,
                 action_json=json.dumps({"steps": [{"stepNo": 1, "action": "http.request"}]}))

        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        table = page.locator('[data-test="case-history-table"]')
        expect(table).to_be_visible(timeout=15000)
        expect(table.locator("tbody tr")).to_have_count(2, timeout=10000)
        eng_cells = table.locator('[data-test="history-engine"]')
        expect(eng_cells).to_have_count(1, timeout=10000)
        expect(eng_cells.first).to_contain_text("MOBILEWRIGHT")
        expect(table.locator('[data-test^="engine-rerun-open-"]')).to_have_count(1)

        table.locator(f'[data-test="engine-rerun-open-{t_app}"]').click()
        dlg = page.locator('[data-test="engine-rerun-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        expect(dlg.locator('[data-test="rerun-engine-opt-HYPIUM"]')).to_be_visible(timeout=5000)
        expect(dlg.locator('[data-test="rerun-engine-opt-MOBILEWRIGHT"]')).to_have_count(0)
        dlg.locator('[data-test="rerun-engine-opt-HYPIUM"]').click()
        dlg.locator('[data-test="rerun-precheck-btn"]').click()
        verdict = dlg.locator('[data-test="rerun-verdict"]')
        expect(verdict).to_contain_text("需改写", timeout=10000)
        expect(dlg.locator('[data-test="rerun-items"]')).to_contain_text("app.launch")
        expect(dlg.locator('[data-test="rerun-rewrite-hint"]')).to_be_visible()
        expect(dlg.locator('[data-test="rerun-dispatch-btn"]')).to_be_disabled()

        dlg.locator('[data-test="rerun-engine-opt-APPIUM"]').click()
        expect(dlg.locator('[data-test="rerun-verdict"]')).to_have_count(0)
        dlg.locator('[data-test="rerun-precheck-btn"]').click()
        expect(dlg.locator('[data-test="rerun-verdict"]')).to_contain_text("降级可跑", timeout=10000)
        expect(dlg.locator('[data-test="rerun-items"]')).to_contain_text("能力收窄提示")
        dispatch = dlg.locator('[data-test="rerun-dispatch-btn"]')
        expect(dispatch).to_be_enabled()
        dispatch.click()
        expect(dlg).to_be_hidden(timeout=15000)

        page.reload(wait_until="domcontentloaded")
        inject_token(page)
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        table = page.locator('[data-test="case-history-table"]')
        expect(table).to_be_visible(timeout=15000)
        expect(table.locator('[data-test="history-engine"]')).to_have_count(2, timeout=10000)
        texts = table.locator('[data-test="history-engine"]').all_text_contents()
        assert "APPIUM" in texts and "MOBILEWRIGHT" in texts, f"双引擎行不齐: {texts}"

        rows = table.locator("tbody tr")
        rows.filter(has_text="MOBILEWRIGHT").locator(".el-checkbox").first.click()
        rows.filter(has_text="APPIUM").locator(".el-checkbox").first.click()
        cmp_btn = page.locator('[data-test="history-compare-btn"]')
        expect(cmp_btn).to_be_enabled(timeout=5000)
        cmp_btn.click()
        cmp = page.locator('[data-test="history-compare-dialog"]')
        expect(cmp).to_be_visible(timeout=10000)
        body = cmp.inner_text()
        assert "APPIUM" in body and "MOBILEWRIGHT" in body, \
            f"对照弹层缺引擎维度: {body[:200]}"
        page.locator('[data-test="history-compare-close"]').click()
        expect(cmp).to_be_hidden(timeout=5000)

        new_id = int(sql_query(
            "SELECT id FROM tp_exec_task WHERE case_id = " + str(case_id) +
            " AND task_type = 6 ORDER BY id DESC LIMIT 1").splitlines()[0])
        env_json = sql_query(f"SELECT action_json FROM tp_exec_task WHERE id = {new_id}")
        assert '"APPIUM"' in env_json, f"新行信封未指向 APPIUM: {env_json[:200]}"

        assert_no_errors(collectors, "T310 换引擎重跑+对照视图首 E2E")
    finally:
        sql_query("UPDATE tp_appauto_engine SET status = 0 WHERE code = 'HYPIUM'")
        if pid is not None:
            r = api_client.delete(f"/projects/{pid}/purge?force=true")
            assert r.status_code == 200, f"清理失败: {r.text[:200]}"
