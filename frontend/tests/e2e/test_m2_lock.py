
import uuid

import httpx
from playwright.sync_api import expect

from conftest import API_BASE, BASE_URL, assert_no_errors, add_member, mk_tester, nav_to, row_cmd



def _mk_dataset(api, uniq):
    pass
    code = f"M2E{uniq[:6].upper()}"
    r = api.post("/projects", json={"name": f"M2锁定项目{uniq}", "code": code})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]

    env_ids = []
    for name, etype in ((f"测试环境{uniq}", 2), (f"预发环境{uniq}", 3)):
        r = api.post(f"/projects/{pid}/envs", json={
            "name": name, "envType": etype, "baseUrl": f"https://{name}.tp-local.test",
        })
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"
        env_ids.append(r.json()["data"])

    case_ids = []
    for title, phase, prio in (("登录冒烟M2", 1, 0), ("支付下单M2", 2, 1), ("优惠券回归M2", 3, 1)):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"{title}{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": prio, "confidence": 2,
            "endType": "web", "status": 3, "phase": phase,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
        })
        assert r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])

    tester_id, _ = mk_tester(api, uniq, prefix="m2")
    add_member(api, pid, tester_id)
    return pid, env_ids, case_ids, tester_id


def _mk_plan(api, pid, env_ids, uniq, lock_mode=2):
    pass
    r = api.post("/plans", json={
        "projectId": pid, "name": f"M2严格计划{uniq}", "targetVersion": "v2.1.0",
        "envIds": env_ids,
        "caseScope": {"phases": [1, 2, 3], "priorities": [0, 1]},
        "entryCriteria": {"requireSmokePass": True},
        "exitCriteria": {"minPassRate": 0.8, "noOpenDefects": True},
        "lockMode": lock_mode,
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建计划失败: {r.text[:300]}"
    return r.json()["data"]["id"]


def _assign_publish(api, plan_id, case_ids, tester_id):
    r = api.post(f"/plans/{plan_id}/assign", json={"caseIds": case_ids, "assigneeId": tester_id})
    assert r.json().get("code") == "0", f"分配失败: {r.text[:300]}"
    r = api.post(f"/plans/{plan_id}/publish")
    assert r.json().get("code") == "0", f"发布失败: {r.text[:300]}"


def _refresh_projects(page):
    pass
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)


def _goto_plan_detail(page, pid, plan_name):
    """Plan list via the header-context deep link ?projectId (in-page selector retired by ) → detail."""
    page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="plan-name-link"]').first).to_be_visible(timeout=10000)
    page.locator('[data-test="plan-name-link"]').first.click()
    expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)


def _cleanup(api, pid):
    api.delete(f"/projects/{pid}")



def test_m2_lock_edit_blocked(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, env_ids, case_ids, tester_id = _mk_dataset(api_client, uniq)
    plan_name = f"M2严格计划{uniq}"
    try:
        plan_id = _mk_plan(api_client, pid, env_ids, uniq, lock_mode=2)
        _assign_publish(api_client, plan_id, case_ids, tester_id)

        r = api_client.put(f"/plans/{plan_id}", json={
            "name": plan_name, "targetVersion": "v2.1.0", "envIds": env_ids[:1],
            "caseScope": {"phases": [1, 2, 3], "priorities": [0, 1]},
            "entryCriteria": {"requireSmokePass": True},
            "exitCriteria": {"minPassRate": 0.8, "noOpenDefects": True},
            "planStart": None, "planEnd": None, "lockMode": 2,
        })
        assert r.status_code == 409, f"HTTP 应 409: {r.status_code} {r.text[:300]}"
        body = r.json()
        assert body.get("code") == "40904", f"应 40904 资源冲突: {body.get('code')} {body.get('msg')}"
        assert "已发布" in body.get("msg", "") and "锁定" in body.get("msg", ""), \
            f"人话应含「已发布/锁定」: {body.get('msg')}"

        r = api_client.post(f"/plans/{plan_id}/assign", json={"caseIds": case_ids[:1], "assigneeId": tester_id})
        assert r.status_code == 409, f"HTTP 应 409: {r.status_code} {r.text[:300]}"
        body = r.json()
        assert body.get("code") == "40904", f"分配应 40904: {body.get('code')} {body.get('msg')}"
        assert "已发布" in body.get("msg", ""), f"人话应含「已发布」: {body.get('msg')}"

        _refresh_projects(page)

        _goto_plan_detail(page, pid, plan_name)
        expect(page.locator('[data-test="plan-detail-lockmode"]')).to_contain_text("严格", timeout=10000)
        expect(page.locator('[data-test="plan-detail-status"]')).to_contain_text("已发布")
        expect(page.locator('[data-test="plan-deviation-card"]')).to_be_visible()
        # Reload persistence: lock mode still strict (?projectId rides the deep link)
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-name-link"]').first).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-name-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-detail-lockmode"]')).to_contain_text("严格", timeout=10000)

        assert_no_errors(collectors, "M2 发布后修改 409 + 锁定标签")
    finally:
        _cleanup(api_client, pid)



def test_m2_strict_deviation(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, env_ids, case_ids, tester_id = _mk_dataset(api_client, uniq)
    plan_name = f"M2严格计划{uniq}"
    try:
        plan_id = _mk_plan(api_client, pid, env_ids, uniq, lock_mode=2)
        _assign_publish(api_client, plan_id, case_ids, tester_id)

        r = api_client.post("/cases", json={
            "projectId": pid, "title": f"计划外用例M2{uniq[:6]}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3, "phase": 4,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
        })
        out_id = r.json()["data"]["id"]

        r = api_client.get(f"/plans/{plan_id}/case-allowed?caseId={out_id}")
        body = r.json()
        assert body.get("code") == "40301", f"应 40301: {body.get('code')} {body.get('msg')}"
        assert "严格模式" in body.get("msg", "") and "偏差" in body.get("msg", ""), \
            f"人话应含「严格模式/偏差」: {body.get('msg')}"
        r = api_client.get(f"/plans/{plan_id}/case-allowed?caseId={case_ids[0]}")
        assert r.json().get("code") == "0", f"计划内应放行: {r.json().get('msg')}"

        _refresh_projects(page)

        _goto_plan_detail(page, pid, plan_name)
        expect(page.locator('[data-test="plan-deviation-add"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-deviation-add"]').click()
        expect(page.locator('[data-test="deviation-type"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="deviation-case"]').click()
        out_opt = page.locator(
            f'.el-select-dropdown__item:has-text("计划外用例M2{uniq[:6]}"):visible').first
        expect(out_opt).to_be_visible(timeout=5000)
        out_opt.click()
        reason = f"E2E 计划外执行偏差 {uniq}"
        page.locator('[data-test="deviation-reason"]').fill(reason)
        page.locator('[data-test="deviation-submit"]').click()
        dev_row = page.locator('[data-test="plan-deviation-table"] tbody tr').first
        expect(dev_row).to_be_visible(timeout=10000)
        expect(dev_row).to_contain_text("计划外用例执行")
        expect(dev_row).to_contain_text(reason)

        # ④ Reload persistence: deviation record still there (deep link keeps ?projectId)
        page.goto(f"{BASE_URL}/#/plans?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="plan-name-link"]').first).to_be_visible(timeout=10000)
        page.locator('[data-test="plan-name-link"]').first.click()
        expect(page.locator('[data-test="plan-detail-page"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="plan-deviation-table"] tbody tr').first).to_contain_text(reason, timeout=10000)

        r = api_client.get(f"/plans/{plan_id}/case-allowed?caseId={out_id}")
        assert r.json().get("code") == "0", f"登记后应放行: {r.json().get('msg')}"

        assert_no_errors(collectors, "M2 严格模式偏差登记全链路")
    finally:
        _cleanup(api_client, pid)



def test_m2_reference_hint(logged_in, collectors, api_client, uniq):
    pass
    page = logged_in
    pid, env_ids, case_ids, tester_id = _mk_dataset(api_client, uniq)
    plan_name = f"M2严格计划{uniq}"
    try:
        plan_id = _mk_plan(api_client, pid, env_ids, uniq, lock_mode=2)
        _assign_publish(api_client, plan_id, case_ids, tester_id)

        r = api_client.get(f"/cases/{case_ids[0]}/references")
        assert r.status_code == 200 and r.json().get("code") == "0", f"引用查询失败: {r.text[:300]}"
        refs = r.json()["data"]
        assert any(plan_name in x for x in refs), f"引用列表应含计划名: {refs}"

        _refresh_projects(page)

        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(f'.el-select-dropdown:visible .el-select-dropdown__item:has-text("M2锁定项目{uniq}"):visible').first.click()
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=10000)
        row_cmd(page, page.locator('[data-test="case-table"] tbody tr').first, "edit")
        expect(page.locator('[data-test="case-create-dialog"]')).to_be_visible(timeout=5000)
        hint = page.locator('[data-test="case-ref-hint"]')
        expect(hint).to_be_visible(timeout=5000)
        hint_text = hint.text_content()
        assert plan_name in hint_text, f"提示应含计划名「{plan_name}」: {hint_text}"
        page.locator('.el-dialog__footer button:has-text("取消")').first.click()

        new_title = f"登录冒烟M2{uniq[:6]}-改标题"
        r = api_client.put(f"/cases/{case_ids[0]}", json={
            "title": new_title, "precondition": "无", "expectedResult": "符合预期",
            "priority": 0, "confidence": 2, "endType": "web", "status": 3, "phase": 1,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
            "version": 1,
        })
        assert r.status_code == 200 and r.json().get("code") == "0", f"改标题失败: {r.text[:300]}"
        r = api_client.get(f"/plans/{plan_id}/deviations")
        assert r.json().get("code") == "0", f"偏差列表失败: {r.text[:300]}"
        devs = r.json()["data"]
        type2 = [d for d in devs if d.get("deviationType") == 2]
        assert type2, f"应有 type=2 范围外变更偏差: {devs}"
        assert "用例核心字段变更" in type2[0]["reason"], f"原因应含核心字段变更: {type2[0]['reason']}"

        assert_no_errors(collectors, "M2 用例编辑引用提示 + 严格变更偏差")
    finally:
        _cleanup(api_client, pid)
