
import time
import uuid

import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, sql_query


pytestmark = pytest.mark.xdist_group("kb_gate")

KB_SWITCH = '[data-test="project-kb-gate-switch"]'
_FULL_KEYS = ("name", "defaultLang", "defaultPriority", "budgetMonthly",
              "jiraProjectId", "testOwnerId", "devOwnerId",
              "scriptStoragePath", "recordingStoragePath", "storageMode")


def _mk_project(api, uniq):
    name = f"KB闸_{uniq}"
    r = api.post("/projects", json={"name": name, "code": f"KBG{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data if isinstance(data, int) else data["id"]
    return pid, name


def _mk_case_pending(api, pid, title):
    pass
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "precondition": "前置", "expectedResult": "通过",
        "priority": 1, "confidence": 2, "endType": "web", "phase": 2, "status": 1,
        "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"创建用例失败: {r.text[:300]}"
    return r.json()["data"]


def _approve(api, case):
    r = api.post(f"/cases/{case['id']}/review",
                 json={"action": "pass", "version": case["version"]})
    assert r.status_code == 200 and r.json().get("code") == "0", f"审批失败: {r.text[:300]}"


def _put_kb(api, pid, kb_enabled):
    pass
    prow = api.get(f"/projects/{pid}").json()["data"]
    body = {k: prow.get(k) for k in _FULL_KEYS}
    body["kbEnabled"] = kb_enabled
    return api.put(f"/projects/{pid}", json=body)


def _echo_kb(api, pid):
    return api.get(f"/projects/{pid}").json()["data"].get("kbEnabled")


def _chunk_rows(case_id):
    return int(sql_query("SELECT count(*) FROM knowledge.tp_knowledge_chunk "
                         f"WHERE metadata->>'case_id'='{case_id}'"))


def _skip_rows(case_id):
    return int(sql_query("SELECT count(*) FROM tp_audit_log "
                         "WHERE action='KB_GATE_SKIP' "
                         f"AND object_id='kb-ingest:case:{case_id}'"))


def _wait_chunk(case_id, deadline_s=150):
    pass
    deadline = time.time() + deadline_s
    got = 0
    while time.time() < deadline:
        got = _chunk_rows(case_id)
        if got >= 1:
            return got
        time.sleep(2)
    return got


def _run_gate_chain(api_client):
    pass
    uniq = uuid.uuid4().hex[:8]
    pid, pname = _mk_project(api_client, uniq)
    out = {"pid": pid, "pname": pname}
    try:
        c1 = _mk_case_pending(api_client, pid, f"KB开态_{uniq}")
        _approve(api_client, c1)
        out["ingest_rows"] = _wait_chunk(c1["id"])
        r = _put_kb(api_client, pid, 0)
        out["put_off"] = r.status_code == 200 and r.json().get("code") == "0"
        out["echo_off"] = _echo_kb(api_client, pid)
        c2 = _mk_case_pending(api_client, pid, f"KB关态_{uniq}")
        _approve(api_client, c2)
        deadline = time.time() + 60
        while time.time() < deadline and _skip_rows(c2["id"]) == 0:
            time.sleep(2)
        out["skip_rows"] = _skip_rows(c2["id"])
        out["gate_off_rows"] = _chunk_rows(c2["id"])
        r = _put_kb(api_client, pid, 2)
        out["invalid_code"] = r.json().get("code")
        out["invalid_msg"] = r.json().get("msg", "")
        r = _put_kb(api_client, pid, 1)
        out["put_on"] = r.status_code == 200 and r.json().get("code") == "0"
        out["echo_on"] = _echo_kb(api_client, pid)
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
    return out


def test_kb_gate_ingest_chain(api_client):
    pass
    kb = _run_gate_chain(api_client)
    assert kb["ingest_rows"] >= 1, \
        f"开态审批后向量行应≥1（真 embed），实际 rows={kb['ingest_rows']}"
    assert kb["put_off"], "PUT kbEnabled=0 应成功"
    assert kb["echo_off"] == 0, "关态 GET 回显应为 0"
    assert kb["gate_off_rows"] == 0, \
        f"关态审批后不应入库（闸豁免），实际 rows={kb['gate_off_rows']}"
    assert kb["skip_rows"] >= 1, \
        "关态闸应写 KB_GATE_SKIP 审计留痕（IT-KBS-02 非静默）"
    assert kb["invalid_code"] != "0", \
        f"非法值 2 应被拒，实际 code={kb['invalid_code']}"
    assert "知识库开关" in (kb["invalid_msg"] or ""), \
        f"人话错误消息缺失: {kb['invalid_msg']}"
    assert kb["put_on"] and kb["echo_on"] == 1, "回开往返应成功且回显 1"


def test_kb_gate_ui_switch_roundtrip(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    uid, uname = _mk_project(api_client, uniq)
    base = page.url.split("#")[0]
    try:
        page.goto(f"{base}#/projects", wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-table"]')).to_be_visible(timeout=15000)
        page.fill('input[data-test="project-search"]', uname)
        page.press('input[data-test="project-search"]', "Enter")
        row = page.locator('[data-test="project-table"] tbody tr').filter(has_text=uname).first
        expect(row).to_be_visible(timeout=10000)
        row.locator('[data-test="ra-edit"]').click()
        sw = page.locator(KB_SWITCH)
        expect(sw).to_be_visible(timeout=5000)
        assert "is-checked" in (sw.get_attribute("class") or ""), \
            f"编辑弹窗开关初始应为开态: class={sw.get_attribute('class')}"
        sw.click()
        assert "is-checked" not in (sw.get_attribute("class") or ""), "点击后应翻为关"
        with page.expect_response(
                lambda r: r.request.method == "PUT" and f"/api/v1/projects/{uid}" in r.url):
            page.click('button[data-test="project-submit"]')
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        assert _echo_kb(api_client, uid) == 0
        page.goto(f"{base}#/projects", wait_until="domcontentloaded")
        expect(page.locator('[data-test="project-table"]')).to_be_visible(timeout=15000)
        page.fill('input[data-test="project-search"]', uname)
        page.press('input[data-test="project-search"]', "Enter")
        row = page.locator('[data-test="project-table"] tbody tr').filter(has_text=uname).first
        expect(row).to_be_visible(timeout=10000)
        row.locator('[data-test="ra-edit"]').click()
        expect(sw).to_be_visible(timeout=5000)
        assert "is-checked" not in (sw.get_attribute("class") or ""), \
            "reload 后重开弹窗开关应为关态"
        sw.click()
        page.click('button[data-test="project-submit"]')
        expect(page.locator(".el-message--success").first).to_be_visible(timeout=5000)
        assert _echo_kb(api_client, uid) == 1
        assert_no_errors(collectors, "T217 kb-gate UI 往返")
    finally:
        api_client.delete(f"/projects/{uid}/purge?force=true")
