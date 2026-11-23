

import pytest
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, inject_token, nav_to, sql_query

pytestmark = pytest.mark.xdist_group("n6-batch-mode")


CFG_KEY = "testpike.batch.unevaluated-mode"


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"N6E2E_{uniq}", "code": f"N6E{uniq.upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_env(api, pid, uniq):
    r = api.post(f"/projects/{pid}/envs", json={
        "name": f"N6环境_{uniq}", "envType": 2, "baseUrl": f"https://n6-{uniq}.tp-local.test"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_case(api, pid, title, status):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "无", "expectedResult": "符合预期",
        "priority": 1, "confidence": 2, "endType": "web", "status": status, "phase": 2,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _mk_tester(api, uniq):
    r = api.post("/users", json={
        "username": f"n6t_{uniq}", "password": "Test@12345", "displayName": f"N6执行人_{uniq}",
        "email": f"n6t_{uniq}@test.local", "phone": "", "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建 tester 失败: {r.text[:300]}"
    return r.json()["data"]


def _mk_plan_published(api, pid, env_id, approved_id, uneval_id, uid, tag):
    pass
    r = api.post("/plans", json={
        "projectId": pid, "name": f"N6计划_{tag}", "targetVersion": "v1.0.0", "envIds": [env_id],
        "caseScope": {"phases": [2], "priorities": [0, 1], "keyword": None, "tagIds": []},
        "entryCriteria": {"requireSmokePass": True},
        "exitCriteria": {"minPassRate": 0.95, "noOpenDefects": True},
        "planStart": "2026-09-01", "planEnd": "2026-09-10"})
    body = r.json()
    assert r.status_code == 200 and body.get("code") == "0" and body["data"]["caseCount"] == 2, \
        f"建计划失败: {r.text[:300]}"
    plan_id = body["data"]["id"]
    r = api.post(f"/plans/{plan_id}/assign", json={"caseIds": [approved_id, uneval_id], "assigneeId": uid})
    assert r.status_code == 200 and r.json().get("code") == "0", f"分配失败: {r.text[:300]}"
    r = api.post(f"/plans/{plan_id}/publish")
    assert r.status_code == 200 and r.json().get("code") == "0", f"发布失败: {r.text[:300]}"
    return plan_id


def _set_mode(api, mode):
    pass
    r = api.get("/configs")
    cfg_id = None
    for x in r.json().get("data", []):
        if x.get("configKey") == CFG_KEY:
            cfg_id = x["id"]
            break
    payload = {"configKey": CFG_KEY, "configValue": mode, "groupName": "批次",
               "valueType": "string", "encryptFlag": 0, "description": "E2E N6 模式切换"}
    if cfg_id:
        resp = api.put(f"/configs/{cfg_id}", json=payload)
    else:
        resp = api.post("/configs", json=payload)
    assert resp.status_code == 200 and resp.json().get("code") == "0", f"配置切换失败: {resp.text[:300]}"


def _go_plan_detail(page, pid):
    """Plan list via the header-context deep link ?projectId (in-page selector retired by ) → detail."""
    page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="plan-table"] .el-table__row').first).to_be_visible(timeout=10000)
    page.locator('[data-test="plan-name-link"]').first.click()
    expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)


def test_n6_batch_start_skip(page, collectors, api_client, uniq):
    pass
    api = api_client
    tag = uniq
    pid = _mk_project(api, tag)
    env_id = _mk_env(api, pid, tag)
    approved_id = _mk_case(api, pid, f"N6已通过_{tag}", 3)
    uneval_id = _mk_case(api, pid, f"N6未评审_{tag}", 1)
    uid = _mk_tester(api, tag)
    r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
    assert r.status_code == 200 and r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
    for cid in (approved_id, uneval_id):
        sql_query(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
                  f"VALUES ({cid}, 'v2', '{{\"caseId\": {cid}, \"setup\": [], \"steps\": []}}', 1, 1) "
                  f"ON CONFLICT (case_id) DO NOTHING")
    plan_id = _mk_plan_published(api, pid, env_id, approved_id, uneval_id, uid, tag)
    try:
        inject_token(page)

        _go_plan_detail(page, pid)
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("已发布", timeout=10000)
        page.locator('[data-test="plan-detail-genbatch"]').click()
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        box = page.locator(".el-message-box").filter(has_text="已跳过").first
        expect(box).to_be_visible(timeout=10000)
        expect(box.locator(".el-message-box__title")).to_contain_text("已跳过 1 条未通过评审用例", timeout=5000)
        box_text = box.locator(".el-message-box__message").inner_text()
        assert f"N6未评审_{tag}" in box_text, f"跳过清单缺未评审用例标题: {box_text}"
        assert "待评审" in box_text, f"跳过清单缺状态人话: {box_text}"
        assert f"N6已通过_{tag}" not in box_text, f"跳过清单不应含已通过用例: {box_text}"
        box.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.locator(".el-message-box")).to_have_count(0, timeout=5000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-case-table"] .el-table__row').first).to_be_visible(timeout=10000)
        approved_row = page.locator('[data-test="plan-case-table"] .el-table__row').filter(has_text=f"N6已通过_{tag}")
        assert "-" not in approved_row.locator("td").nth(5).inner_text(), \
            f"已通过用例应已有批次引用: {approved_row.locator('td').nth(5).inner_text()}"
        uneval_row = page.locator('[data-test="plan-case-table"] .el-table__row').filter(has_text=f"N6未评审_{tag}")
        assert "-" in uneval_row.locator("td").nth(5).inner_text(), \
            f"未评审用例不应有批次引用: {uneval_row.locator('td').nth(5).inner_text()}"

        assert_no_errors(collectors, "N6 跳过清单展示")
    finally:
        api.delete(f"/projects/{pid}/purge?force=true")
        api.delete(f"/users/{uid}")


def test_n6_batch_start_block(page, collectors, api_client, uniq):
    pass
    api = api_client
    tag = uniq
    _set_mode(api, "block")
    pid = None
    uid = None
    try:
        pid = _mk_project(api, tag)
        env_id = _mk_env(api, pid, tag)
        approved_id = _mk_case(api, pid, f"N6B已通过_{tag}", 3)
        uneval_id = _mk_case(api, pid, f"N6B未评审_{tag}", 1)
        uid = _mk_tester(api, tag)
        r = api.post(f"/projects/{pid}/members", json={"userId": uid, "role": 1})
        assert r.status_code == 200 and r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
        for cid in (approved_id, uneval_id):
            sql_query(f"INSERT INTO tp_script (case_id, schema_version, action_json, status, generated_by) "
                      f"VALUES ({cid}, 'v2', '{{\"caseId\": {cid}, \"setup\": [], \"steps\": []}}', 1, 1) "
                      f"ON CONFLICT (case_id) DO NOTHING")
        _mk_plan_published(api, pid, env_id, approved_id, uneval_id, uid, f"B{tag}")

        inject_token(page)
        _go_plan_detail(page, pid)
        page.locator('[data-test="plan-detail-genbatch"]').click()
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('确定')").click()
        expect(page.locator(".el-message--error").last).to_be_visible(timeout=10000)
        err = page.locator(".el-message--error").last.inner_text()
        assert "未通过评审" in err and f"N6B未评审_{tag}" in err, f"block 人话缺失: {err}"
        skip_box = page.locator(".el-message-box:visible").filter(has_text="已跳过")
        expect(skip_box).to_have_count(0, timeout=5000)

        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"] if not (e["status"] == 400 and "/generate-batches" in e["url"])
        ]
        collectors["console_errors"] = [
            e for e in collectors["console_errors"] if "status of 400" not in e
        ]
        assert_no_errors(collectors, "N6 block 模式")
    finally:
        if pid:
            api.delete(f"/projects/{pid}/purge?force=true")
        if uid:
            api.delete(f"/users/{uid}")
        _set_mode(api, "skip")
