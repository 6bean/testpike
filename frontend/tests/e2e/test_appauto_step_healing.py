
import json
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")
CORE = "http://localhost:8083"
HEALING_KEY = "ai.appauto.step-healing"


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def inject_token(page):
    pass
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page, token
        except AssertionError:
            if attempt == 2:
                raise AssertionError("inject_token 注入后未进入工作台")
    raise AssertionError("unreachable")


def _pick_project(page, pid):
    """: page-level project selector removed — the ?projectId deep link lands
 the global header context. Dashboard bounce is required because vue-router
 reuses the mounted component across query-only navigation."""
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    page.goto(f"{BASE_URL}/#/appauto/healing?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="step-healing-page"]')).to_be_visible(timeout=15000)


def _pick_combo(page, test_id, text):
    sel = page.locator(f'[data-test="{test_id}"] input').first
    sel.click()
    sel.fill(text)
    opt = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible', has_text=text)
    expect(opt.first).to_be_visible(timeout=8000)
    opt.first.click()


def _mk_project_case(api, uniq):
    pid = api.post("/api/v1/projects",
                   json={"name": f"自愈单E2E项目{uniq}", "code": f"SH{uniq[:6].upper()}"}).json()["data"]
    cid = api.post("/api/v1/cases", json={
        "projectId": pid,
        "title": f"首屏登录入口_{uniq}",
        "precondition": f"前置：设备已连接（{uniq}）",
        "expectedResult": f"预期：App 启动成功（{uniq}）",
        "priority": 0, "confidence": 1, "endType": "mobile", "status": 1,
        "steps": [{"stepNo": 1, "description": f"启动应用（{uniq}）", "isAssert": 0},
                  {"stepNo": 2, "description": f"登录入口可见（{uniq}）", "isAssert": 1}],
    }).json()["data"]["id"]
    return pid, cid


def _seed_script(cid, uniq):
    pass
    doc = {"version": "v2", "caseId": cid, "title": f"seed_{uniq}", "endType": "mobile",
           "steps": [{"stepId": 1, "action": "launch", "target": {"text": f"旧锚点{uniq}", "chain": ["text"]}},
                     {"stepId": 2, "action": "assert", "target": {"text": "登录入口"}}],
           "env": {"ENV": "dev"}}
    r = httpx.post(f"{CORE}/internal/scripts", timeout=30,
                   json={"caseId": cid, "schemaVersion": "v2",
                         "actionJson": json.dumps(doc, ensure_ascii=False), "generatedBy": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"落脚本基线失败: {r.text[:200]}"


def _mk_suggestion(pid, cid, uniq, step_no=1, status=1):
    pass
    target = json.dumps({"text": f"候选锚点{uniq}", "chain": ["text"]}, ensure_ascii=False)
    srow = sql("INSERT INTO tp_appauto_step_suggestion (project_id, case_id, source_task_id, step_no,"
               " failure_class, anchor, source, proposed_action, proposed_target, reason, x, y, status)"
               f" VALUES ({pid}, {cid}, NULL, {step_no}, 2, '旧锚点{uniq}', 'vision', 'tap',"
               f" '{target}', '界面改版：控件文案由旧变新',"
               f" 540, 1200, {status}) RETURNING id")
    return int(srow.splitlines()[0])


def _set_healing_switch(api, value):
    pass
    rows = api.get("/api/v1/configs", params={"groupName": "APP自动化"}).json()["data"]
    hit = [c for c in rows if c["configKey"] == HEALING_KEY]
    assert hit, "自愈开关键缺失（V160 种子未落库？）"
    cfg = hit[0]
    r = api.put(f"/api/v1/configs/{cfg['id']}", json={
        "configKey": HEALING_KEY, "configValue": value,
        "groupName": cfg.get("groupName") or "APP自动化",
        "valueType": cfg.get("valueType") or "int",
        "encryptFlag": cfg.get("encryptFlag", 0),
        "description": cfg.get("description") or ""})
    assert r.status_code == 200, f"配置翻转失败: {r.status_code} {r.text[:200]}"


def test_appauto_step_healing_page(page, collectors):
    pass
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    uniq = uuid.uuid4().hex[:6]
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    pid = None
    try:
        pid, cid = _mk_project_case(api, uniq)
        _seed_script(cid, uniq)
        s1 = _mk_suggestion(pid, cid, uniq)
        s2 = _mk_suggestion(pid, cid, f"{uniq}b")

        open_journey_leaf(page, "menu-step-healing", "appauto")
        expect(page.locator('[data-test="step-healing-page"]')).to_be_visible(timeout=10000)

        # ── 2. deep-link the project + pick the case → two rows (all fields present:
        # step no / source / status tag) ──
        # combo filter text must be the full name: a bare uniq suffix also matches the
        # case popper that is mid-collapse (still in the DOM/animation)
        _pick_project(page, pid)
        _pick_combo(page, "step-healing-case-select", f"首屏登录入口_{uniq}")
        table = page.locator('[data-test="step-healing-table"]')
        expect(table.locator("tbody tr")).to_have_count(2, timeout=10000)
        expect(table.locator('[data-test="step-healing-source-label"]').first).to_contain_text("视觉定位")
        expect(table.locator('[data-test="step-healing-status-tag"]').first).to_contain_text("待批")

        reject_btn = page.locator('tbody tr').filter(has_text="待批").locator(
            '[data-test="step-healing-reject-btn"]').first
        expect(reject_btn).to_be_visible(timeout=8000)
        reject_btn.click()
        confirm = page.locator('.el-message-box')
        expect(confirm).to_be_visible(timeout=5000)
        confirm.locator('button', has_text="确定").click()
        assert sql(f"SELECT status FROM tp_appauto_step_suggestion WHERE id={s2}") == "3"
        _pick_project(page, pid)
        _pick_combo(page, "step-healing-case-select", f"首屏登录入口_{uniq}")
        rejected_row = page.locator('tbody tr').filter(has_text="已驳回")
        expect(rejected_row).to_have_count(1, timeout=10000)
        assert rejected_row.locator('[data-test="step-healing-adopt-btn"]').count() == 0

        page.locator('[data-test="step-healing-adopt-btn"]').first.click()
        confirm2 = page.locator('.el-message-box')
        expect(confirm2).to_be_visible(timeout=5000)
        confirm2.locator('button', has_text="确定").click()
        alert = page.locator('[data-test="step-healing-gate-off-alert"]')
        expect(alert).to_be_visible(timeout=10000)
        expect(alert).to_contain_text("系统设置")
        expect(alert).to_contain_text(HEALING_KEY)
        assert sql(f"SELECT status FROM tp_appauto_step_suggestion WHERE id={s1}") == "1", \
            "关态采纳必须零写回（状态不翻转）"
        assert sql(f"SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id={pid}") == "0"
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not ("/step-suggestions/" in e["url"] and "/adopt" in e["url"]
                    and e["status"] == 403)]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 403" not in c]

        r_stale = api.post(f"/api/v1/appauto/step-suggestions/{s1}/adopt",
                           params={"expectedScriptVersion": 999})
        assert r_stale.status_code == 403, f"关态应 403: {r_stale.status_code} {r_stale.text[:200]}"
        r_rej = api.post(f"/api/v1/appauto/step-suggestions/{s2}/reject")
        assert r_rej.status_code == 409, f"重复处理应 409: {r_rej.text[:200]}"
        assert "不可重复驳回" in r_rej.json().get("msg", ""), f"40904 人话缺失: {r_rej.text[:200]}"
        assert str(r_rej.json().get("code")) == "40904"

        assert_no_errors(collectors, "appauto step healing page")
    finally:
        if pid:
            r = api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert r.status_code == 200, f"项目清理失败: {r.status_code} {r.text[:200]}"
            assert sql(f"SELECT count(*) FROM tp_appauto_step_suggestion WHERE project_id={pid}") == "0"
            assert sql(f"SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id={pid}") == "0"


def test_appauto_step_healing_adopt_real_landing():
    pass
    token = api_login("admin", ADMIN_PASSWORD)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    uniq = uuid.uuid4().hex[:6]
    pid = None
    flipped = False
    try:
        _set_healing_switch(api, "1")
        flipped = True
        pid, cid = _mk_project_case(api, uniq)
        _seed_script(cid, uniq)
        sid = _mk_suggestion(pid, cid, uniq)
        ver_before = int(sql(f"SELECT version FROM tp_script WHERE case_id={cid}"))

        r_stale = api.post(f"/api/v1/appauto/step-suggestions/{sid}/adopt",
                           params={"expectedScriptVersion": ver_before + 77})
        assert r_stale.status_code == 409, f"陈旧应 409: {r_stale.text[:200]}"
        body = r_stale.json()
        assert str(body.get("code")) == "40901", f"业务码应 40901: {body}"
        assert f"当前版本 {ver_before}" in body.get("msg", ""), f"人话缺版本号: {body}"

        r = api.post(f"/api/v1/appauto/step-suggestions/{sid}/adopt",
                     params={"expectedScriptVersion": ver_before})
        assert r.status_code == 200, f"采纳失败: {r.status_code} {r.text[:300]}"
        view = r.json()["data"]
        assert view["status"] == 2
        assert view["adoptedScriptVersion"] == ver_before + 1

        assert int(sql(f"SELECT version FROM tp_script WHERE case_id={cid}")) == ver_before + 1
        aj = sql(f"SELECT action_json FROM tp_script WHERE case_id={cid}")
        assert f"候选锚点{uniq}" in aj, "候选定位未合入脚本"
        assert sql(f"SELECT status FROM tp_appauto_step_suggestion WHERE id={sid}") == "2"
        assert sql(f"SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id={pid}") == "1", \
            "带坐标采纳应回灌 A2 定位样本"
    finally:
        if flipped:
            _set_healing_switch(api, "1")  # restore factory default (K8 26-key family = ON)
        if pid:
            api.delete(f"/api/v1/projects/{pid}/purge", params={"force": "true"})
            assert sql(f"SELECT count(*) FROM tp_appauto_step_suggestion WHERE project_id={pid}") == "0"
            assert sql(f"SELECT count(*) FROM tp_appauto_ai_sample WHERE project_id={pid}") == "0"
