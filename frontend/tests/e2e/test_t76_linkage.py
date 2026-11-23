
import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, nav_to



def _mk_config(api, key, value, group="通知", vtype="string", encrypt=0, desc="T76 E2E 配置"):
    r = api.post("/configs", json={
        "configKey": key, "configValue": value, "groupName": group,
        "valueType": vtype, "encryptFlag": encrypt, "description": desc,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建配置失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_project(api, uniq, suffix=""):
    code = f"T76P{uniq[:6].upper()}{suffix}"
    r = api.post("/projects", json={"name": f"T76联动项目{uniq}{suffix}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_env(api, pid, uniq, suffix=""):
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"T76环境{suffix}{uniq[:4]}", "envType": 2,
        "baseUrl": f"https://t76-{suffix}-{uniq}.tp-local.test",
    })
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, uniq, title="T76用例", phase=2, priority=1):
    r = api.post("/cases", json={
        "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
        "expectedResult": "符合预期", "priority": priority, "confidence": 2,
        "endType": "web", "status": 3, "phase": phase,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
    })
    assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _mk_tester_member(api, pid, uniq):
    r = api.post("/users", json={
        "username": f"t76t{uniq[:6]}", "password": "Test@12345",
        "displayName": f"T76执行人{uniq[:4]}", "role": 1,
    })
    assert r.json().get("code") == "0", f"建用户失败: {r.text[:300]}"
    uid = r.json()["data"]
    r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
    assert r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
    return uid


def _audit_search(page, object_type_label, object_id=None, action=None):
    pass
    page.locator('[data-test="audit-action"]').fill("")
    page.locator('[data-test="audit-object-id"]').fill("")
    page.locator('[data-test="audit-object-type"]').click()
    page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("{object_type_label}")').first.click()
    if action:
        page.locator('[data-test="audit-action"]').fill(action)
    if object_id:
        page.locator('[data-test="audit-object-id"]').fill(str(object_id))
    page.locator('[data-test="audit-search"]').click()
    expect(page.locator('[data-test="audit-table"] tbody tr').first).to_be_visible(timeout=10000)
    return page.locator('[data-test="audit-table"] tbody tr').count()



def test_t76_audit_multi_object(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    key = f"e2e.t76.audit.{uniq}"
    cfg = None
    pid = None
    try:
        cfg = _mk_config(api_client, key, "old")
        api_client.put(f"/configs/{cfg['id']}", json={
            "configKey": key, "configValue": "new", "groupName": "通知",
            "valueType": "string", "encryptFlag": 0, "description": "T76 审计联动",
        })
        pid = _mk_project(api_client, uniq)
        env_id = _mk_env(api_client, pid, uniq)
        case_id = _mk_case(api_client, pid, uniq)
        uid = _mk_tester_member(api_client, pid, uniq)
        r = api_client.post("/plans", json={
            "projectId": pid, "name": f"T76审计计划{uniq[:6]}", "targetVersion": "v1.0.0",
            "envIds": [env_id], "caseScope": {"phases": [2], "priorities": [1],
                                              "keyword": None, "tagIds": []},
            "entryCriteria": {"requireSmokePass": False},
            "exitCriteria": {"minPassRate": 0.9, "noOpenDefects": False}, "lockMode": 1})
        assert r.json().get("code") == "0", f"建计划失败: {r.text[:300]}"
        plan_id = r.json()["data"]["id"]
        api_client.post(f"/plans/{plan_id}/assign", json={"caseIds": [case_id], "assigneeId": uid})
        api_client.post(f"/plans/{plan_id}/publish")
        r = api_client.post("/defect-library", json={
            "projectId": pid, "caseId": case_id, "planId": plan_id,
            "title": f"T76联动缺陷{uniq[:6]}", "description": "T76 联动", "severity": 2, "assigneeId": uid})
        assert r.json().get("code") == "0", f"建缺陷失败: {r.text[:300]}"
        defect_id = r.json()["data"]["id"]
        r = api_client.post(f"/defect-library/{defect_id}/transition", json={"toStatus": 2, "version": 1})
        assert r.json().get("code") == "0", f"缺陷流转失败: {r.text[:300]}"

        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")
        n_sys = _audit_search(page, "系统配置", action="update")
        assert n_sys >= 1, f"sys_config/update 记录应 ≥1: {n_sys}"
        n_defect = _audit_search(page, "缺陷")
        assert n_defect >= 2, f"defect 记录应 ≥2（create+transition）: {n_defect}"
        row = page.locator('[data-test="audit-table"] tbody tr').first
        row.locator('[data-test="audit-detail"]').click()
        expect(page.locator('[data-test="audit-diff-table"]')).to_be_visible(timeout=5000)
        page.locator('.el-dialog__footer button:has-text("关闭")').first.click()
        n_plan = _audit_search(page, "执行计划", action="plan_publish")
        assert n_plan >= 1, f"exec_plan/plan_publish 记录应 ≥1: {n_plan}"

        assert_no_errors(collectors, "T76 审计×K8/L2/L1 联动")
    finally:
        if cfg:
            r = api_client.get("/configs")
            for x in r.json().get("data", []):
                if x.get("configKey") == key:
                    api_client.delete(f"/configs/{x['id']}")
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")



def test_t76_tag_plan_scope(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid = None
    try:
        pid = _mk_project(api_client, uniq)
        env_id = _mk_env(api_client, pid, uniq)
        c_tag = _mk_case(api_client, pid, uniq, "T76支付用例", phase=2)
        _mk_case(api_client, pid, uniq, "T76回归用例", phase=3)
        r = api_client.post("/tags", json={"projectId": pid, "name": f"T76支付域{uniq[:4]}", "type": 4})
        assert r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
        tag_id = r.json()["data"]["id"]
        r = api_client.put(f"/cases/{c_tag}/tags", json={"projectId": pid, "tagIds": [tag_id]})
        assert r.json().get("code") == "0", f"打标签失败: {r.text[:300]}"

 # Plan wizard: tag scope via the plan-wizard-tags selector (header-context deep link ?projectId)
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-table"]').first).to_be_visible(timeout=15000)
        page.locator('[data-test="plan-create-btn"]').click()
        expect(page.locator('[data-test="plan-wizard-name"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-wizard-name"]').fill(f"T76标签计划{uniq[:6]}")
        page.locator('[data-test="plan-wizard-version"]').fill("v1.1.0")
        page.locator('[data-test="plan-wizard-next"]').click()
        expect(page.locator('[data-test="plan-wizard-envs"]').first).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-wizard-envs"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible').first.click()
        page.keyboard.press("Escape")
        page.locator('[data-test="plan-wizard-phases"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("功能")').first.click()
        page.keyboard.press("Escape")
        page.locator('[data-test="plan-wizard-tags"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("T76支付域{uniq[:4]}")').first.click()
        page.keyboard.press("Escape")
        page.locator('[data-test="plan-wizard-next"]').click()
        expect(page.locator('[data-test="plan-wizard-submit"]')).to_be_visible(timeout=10000)
        with page.expect_response(lambda r: "/plans" in r.url and r.request.method == "POST", timeout=10000):
            page.locator('[data-test="plan-wizard-submit"]').click()
        expect(page.locator(".el-message").filter(has_text="拉入 1 条用例").first).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-detail-page"]').first).to_be_visible(timeout=15000)
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-detail-page"]').first).to_be_visible(timeout=15000)

        # Tag stats page (tag × stats linkage): coverage tab → payment-domain caseCount=1
        page.goto(f"{BASE_URL}/#/tags?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="tag-table"]').first).to_be_visible(timeout=15000)
        row = page.locator(f'[data-test="tag-table"] tbody tr:has-text("T76支付域{uniq[:4]}")').first
        expect(row).to_be_visible(timeout=10000)
        page.locator('[data-test="tag-tabs"] .el-tabs__item', has_text="覆盖率统计").click()
        expect(page.locator('[data-test="tag-stats-table"] tbody tr').first).to_be_visible(timeout=10000)
        srow = page.locator(f'[data-test="tag-stats-table"] tbody tr:has-text("T76支付域{uniq[:4]}")').first
        expect(srow).to_be_visible(timeout=10000)
        assert srow.locator('td').nth(1).text_content().strip() == "1", \
            f"标签统计 caseCount 应为 1: {srow.text_content()}"

        assert_no_errors(collectors, "T76 标签×范围×统计联动")
    finally:
        if pid:
            api_client.delete(f"/projects/{pid}/purge?force=true")



def test_t76_audit_llm_masked(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    llm_id = None
    try:
        r = api_client.post("/llm-configs", json={
            "name": f"T76LLM{uniq[:6]}", "provider": "openai", "modelName": "gpt-4o-mini",
            "apiKey": "sk-seed-key", "baseUrl": "http://localhost:18080/v1",
            "status": 1, "isDefault": 0, "description": "T76 审计联动"})
        assert r.json().get("code") == "0", f"建 LLM 配置失败: {r.text[:300]}"
        llm_id = r.json()["data"]["id"]
        r = api_client.put(f"/llm-configs/{llm_id}", json={
            "name": f"T76LLM{uniq[:6]}", "provider": "openai", "modelName": "gpt-4o-mini-t76",
            "apiKey": "«redacted:sk-…»", "baseUrl": "http://localhost:18080/v1", "status": 1,
            "isDefault": 0, "description": "T76 审计联动"})
        assert r.status_code == 200 and r.json().get("code") == "0", f"更新 LLM 失败: {r.text[:300]}"

        nav_to(page, "审计日志", "/audit-logs", '[data-test="audit-page"]', submenu="系统管理")
        page.locator('[data-test="audit-object-type"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item:visible:has-text("大模型配置")').first.click()
        page.locator('[data-test="audit-search"]').click()
        expect(page.locator('[data-test="audit-table"] tbody tr').first).to_be_visible(timeout=10000)
        row = page.locator('[data-test="audit-table"] tbody tr').first
        row.locator('[data-test="audit-detail"]').click()
        expect(page.locator('[data-test="audit-diff-table"]')).to_be_visible(timeout=5000)
        diff_text = page.locator('[data-test="audit-diff-table"]').text_content()
        assert "sk-…»" not in diff_text and "sk-seed" not in diff_text, \
            f"diff 中不应出现明文 apiKey: {diff_text[:200]}"
        assert "******" in diff_text or "apiKeyMasked" in diff_text, \
            f"diff 应含打码标记: {diff_text[:200]}"
        page.locator('.el-dialog__footer button:has-text("关闭")').first.click()

        assert_no_errors(collectors, "T76 审计×K9 敏感打码")
    finally:
        if llm_id:
            api_client.delete(f"/llm-configs/{llm_id}")
