
import json
import uuid

import httpx
import pytest
from conftest import (
    ADMIN_PASSWORD,
    API_ORIGIN,
    BASE_URL,
    api_login,
    assert_no_errors,
    inject_token,
    redis_cli,
    sql_query,
)
from playwright.sync_api import expect
# Frame payload reuses the monitor-window fixture byte-for-byte (single source).
from test_t304_monitor_window import _JPEG_B64

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("popup-rules")]

LEARN_KEY = "ai.appauto.popup-rule-learn"
VISION_GATE_KEY = "ai.vision.enabled"
MODEL_KEY = "ai.vision.model"


def _push_frames(task_id):
    pass
    frame = json.dumps({"taskId": task_id, "stepNo": 2,
                        "image": _JPEG_B64, "sensitive": False})
    redis_cli("LPUSH", f"appauto-monitor:frames:{task_id}", frame)
    redis_cli("EXPIRE", f"appauto-monitor:frames:{task_id}", "300")


def _cleanup_frames(task_id):
    redis_cli("DEL", f"appauto-monitor:frames:{task_id}", f"appauto-monitor:meta:{task_id}")


def _mk_failed_task(api, pid, uniq, tag):
    pass
    cid = api.post("/api/v1/cases", json={
        "projectId": pid, "title": f"弹窗自学用例{tag}_{uniq}", "precondition": "无",
        "expectedResult": "OK", "priority": 1, "confidence": 2,
        "endType": "mobile", "status": 1, "automationTarget": "app",
        "appautoEngine": "MOBILEWRIGHT",
        "steps": [{"stepNo": 1, "description": "启动应用", "isAssert": 0},
                  {"stepNo": 2, "description": "点击登录", "isAssert": 1}]}).json()["data"]["id"]
    tid = int(sql_query(
        "INSERT INTO tp_exec_task (project_id, case_id, status, task_type, schema_version, "
        "error_type, error_detail, created_at, updated_at) "
        f"VALUES ({pid}, {cid}, 4, 6, 'appauto-v1', '4', '第 2 步弹窗挡住定位_{tag}_{uniq}', "
        "now(), now()) RETURNING id").splitlines()[0])
    return tid


def _set_config(api, key, value):
    pass
    rows = api.get("/api/v1/configs").json()["data"]
    hit = [c for c in rows if c["configKey"] == key]
    assert hit, f"配置键缺失（迁移种子未落库？）: {key}"
    cfg = hit[0]
    r = api.put(f"/api/v1/configs/{cfg['id']}", json={
        "configKey": key, "configValue": value,
        "groupName": cfg.get("groupName") or "系统",
        "valueType": cfg.get("valueType") or "int",
        "encryptFlag": cfg.get("encryptFlag", 0),
        "description": cfg.get("description") or ""})
    assert r.status_code == 200, f"配置翻转失败 {key}={value}: {r.status_code} {r.text[:200]}"


def _pick_combo(page, test_id, text):
    sel = page.locator(f'[data-test="{test_id}"] input').first
    sel.click()
    sel.fill(text)
    opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible', has_text=text)
    expect(opt.first).to_be_visible(timeout=8000)
    opt.first.click()


def _open_learn_and_submit(page, task_label):
    pass
    page.locator('[data-test="popup-rule-learn-btn"]').click()
    dialog = page.locator('[data-test="popup-rule-learn-dialog"]')
    expect(dialog).to_be_visible(timeout=8000)
    _pick_combo(page, "popup-rule-learn-task-select", task_label)
    dialog.locator('[data-test="popup-rule-learn-submit"]').click()
    return dialog


def _close_learn_dialog(page):
    pass
    cancel = page.locator('[data-test="popup-rule-learn-cancel"]')
    if cancel.count():
        cancel.first.click()
        expect(page.locator('[data-test="popup-rule-learn-dialog"]')).to_be_hidden(timeout=5000)


def _open_rules_page(page, pid):
    """: page-level selector removed — ?projectId deep link lands the segment.
 Dashboard bounce forces a remount (query-only navigation reuses the component,
 so the setup that consumes the deep link would otherwise never re-run)."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/popup-rules?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="popup-rule-page"]')).to_be_visible(timeout=15000)


def _login(page):
    pass
    token = api_login("admin", ADMIN_PASSWORD)
    page = inject_token(page)
    return page, token


def test_popup_rules_gate_off(page, collectors):
    pass
    page, token = _login(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API_ORIGIN, timeout=30, headers=h)
    uniq = uuid.uuid4().hex[:6]
    pid = None
    tid = None
    try:
        pid = api.post("/api/v1/projects", json={
            "name": f"弹窗审批E2E项目{uniq}", "code": f"PR{uniq[:6].upper()}"}).json()["data"]
        tid = _mk_failed_task(api, pid, uniq, "关态")
        _set_config(api, LEARN_KEY, "0")
        _open_rules_page(page, pid)

        _open_learn_and_submit(page, f"#{tid} ·")
        alert = page.locator('[data-test="popup-rule-gate-off-alert"]')
        expect(alert).to_be_visible(timeout=10000)
        expect(alert).to_contain_text("系统设置")
        expect(alert).to_contain_text("APP 自动化")
        expect(alert).to_contain_text("弹窗规则自学")
        assert sql_query(f"SELECT count(*) FROM tp_appauto_popup_rule WHERE project_id={pid}") == "0", \
            "关态自学必须零落草稿（零外呼零写库）"

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/popup-rules/learn" in e["url"] and e["status"] == 403)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 403" not in c]
        assert_no_errors(collectors, "popup rules gate-off page")
    finally:
        _cleanup_frames(tid) if tid else None
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql_query(f"SELECT count(*) FROM tp_appauto_popup_rule WHERE project_id={pid}") == "0"


def test_popup_rules_open_negative_faces_and_approval(page, collectors):
    pass
    page, token = _login(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API_ORIGIN, timeout=30, headers=h)
    uniq = uuid.uuid4().hex[:6]
    pid = None
    tid_frames = tid_noframes = None
    flipped = False
    try:
        pid = api.post("/api/v1/projects", json={
            "name": f"弹窗审批E2E项目{uniq}", "code": f"PR{uniq[:6].upper()}"}).json()["data"]
        tid_noframes = _mk_failed_task(api, pid, uniq, "无帧")
        tid_frames = _mk_failed_task(api, pid, uniq, "有帧")
        _push_frames(tid_frames)
        _set_config(api, LEARN_KEY, "1")
        _set_config(api, VISION_GATE_KEY, "1")
        flipped = True
        assert sql_query(f"SELECT config_value FROM tp_sys_config WHERE config_key='{MODEL_KEY}'") == "", \
            "前置假设：ai.vision.model 本地未配置（成功链归 L3 桩面）"

        _open_rules_page(page, pid)

        _open_learn_and_submit(page, f"#{tid_noframes} ·")
        toast = page.locator('.el-message')
        expect(toast).to_be_visible(timeout=10000)
        expect(toast).to_contain_text("无可学习的监看帧")
        assert sql_query(f"SELECT count(*) FROM tp_appauto_popup_rule WHERE project_id={pid}") == "0"
        _close_learn_dialog(page)

        _open_learn_and_submit(page, f"#{tid_frames} ·")
        toast2 = page.locator('.el-message').last
        expect(toast2).to_contain_text("视觉模型未配置", timeout=15000)
        assert sql_query(f"SELECT count(*) FROM tp_appauto_popup_rule WHERE project_id={pid}") == "0"
        _close_learn_dialog(page)

        rule_json = json.dumps({"detect": {"text": f"更新提示{uniq}"},
                                "dismiss": {"action": "app.tap",
                                            "target": {"text": "以后再说"}}}, ensure_ascii=False)
        esc = rule_json.replace("'", "''")
        r1 = int(sql_query("INSERT INTO tp_appauto_popup_rule (project_id, source_task_id, title, rule, "
                     f"status, source) VALUES ({pid}, {tid_frames}, '批准候选{uniq}', "
                     f"'{esc}'::jsonb, 1, 'AI') RETURNING id").splitlines()[0])
        r2 = int(sql_query("INSERT INTO tp_appauto_popup_rule (project_id, source_task_id, title, rule, "
                     f"status, source) VALUES ({pid}, {tid_frames}, '驳回候选{uniq}', "
                     f"'{esc}'::jsonb, 1, 'AI') RETURNING id").splitlines()[0])
        page.locator('[data-test="popup-rule-refresh"]').click()
        table = page.locator('[data-test="popup-rule-table"]')
        expect(table.locator("tbody tr")).to_have_count(2, timeout=10000)

        approve_row = table.locator("tbody tr").filter(has_text=f"批准候选{uniq}")
        approve_row.locator('[data-test="popup-rule-confirm-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        box.locator('button', has_text="确定").click()
        expect(page.locator('.el-message').filter(has_text="已批准入库")).to_be_visible(timeout=10000)
        assert sql_query(f"SELECT status FROM tp_appauto_popup_rule WHERE id={r1}") == "2"

        # RELOAD via deep link -> stored tag persists and handled rows hide
        # the approve buttons (state-machine UX gate)
        _open_rules_page(page, pid)
        done_row = page.locator("tbody tr").filter(has_text=f"批准候选{uniq}")
        expect(done_row.locator('[data-test="popup-rule-status-tag"]')).to_contain_text("已入库")
        assert done_row.locator('[data-test="popup-rule-confirm-btn"]').count() == 0

        reject_row = page.locator("tbody tr").filter(has_text=f"驳回候选{uniq}")
        reject_row.locator('[data-test="popup-rule-reject-btn"]').click()
        box2 = page.locator('.el-message-box')
        expect(box2).to_be_visible(timeout=5000)
        box2.locator('button', has_text="确定").click()
        assert sql_query(f"SELECT status FROM tp_appauto_popup_rule WHERE id={r2}") == "3"
        _open_rules_page(page, pid)
        rej_row = page.locator("tbody tr").filter(has_text=f"驳回候选{uniq}")
        expect(rej_row.locator('[data-test="popup-rule-status-tag"]')).to_contain_text("已驳回")

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/popup-rules/learn" in e["url"] and e["status"] in (403, 404))]
        collectors["network_errors"] = [
            e for e in collectors["network_errors"] if "/popup-rules/learn" not in e["url"]]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"]
            if not any(s in c for s in ("status of 403", "status of 404", "status of 503"))]
        assert_no_errors(collectors, "popup rules open faces + approval")
    finally:
        if flipped:
            # restore factory default (K8 26-key family = ON); closed state is self-provisioned in-test
            _set_config(api, LEARN_KEY, "1")
            _set_config(api, VISION_GATE_KEY, "1")
        for t in (tid_frames, tid_noframes):
            if t:
                _cleanup_frames(t)
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql_query(f"SELECT count(*) FROM tp_appauto_popup_rule WHERE project_id={pid}") == "0", \
                "purge 必须级联清弹窗规则草稿（2d89ede0 修复面）"
