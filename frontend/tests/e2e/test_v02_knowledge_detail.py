
import uuid

import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, sql_query


pytestmark = pytest.mark.xdist_group("kb-admin")

KB_PAGE = "#/knowledge"

FULL_TEXT = "登录接口回归要点：" + "先校验用户名密码非空，再校验验证码时效五分钟，" \
    "最后校验连续五次失败锁定账户十五分钟自动解锁。" * 2


def _seed(pid: int, case_id: int, uniq: str) -> int:
    pass
    doc_id = int(sql_query(
        "INSERT INTO tp_knowledge_doc (project_id,pack_id,source_type,source_ref,title,content,status)"
        f" VALUES ({pid},0,'TEST_CASE',{case_id},'KBV2用例_{uniq}','{FULL_TEXT}','INACTIVE')"
        " RETURNING id").strip().splitlines()[0])
    for idx in (0, 1):
        sql_query(
            "INSERT INTO knowledge.tp_knowledge_chunk (id,content,metadata,embedding)"
            f" VALUES (gen_random_uuid(),'分块正文{idx}-KBV2-{uniq}',"
            f"'{{\"doc_id\":\"{doc_id}\",\"case_id\":\"{case_id}\",\"source_type\":\"TEST_CASE\","
            f"\"project_id\":\"{pid}\",\"title\":\"KBV2用例_{uniq}\",\"chunk_index\":\"{idx}\","
            f"\"lang\":\"java\",\"status\":\"ACTIVE\",\"pack_id\":\"0\",\"excellent\":false}}'::jsonb,"
            "('[0' || repeat(',0',1535) || ']')::vector)")
    for _ in range(3):
        sql_query(
            "INSERT INTO tp_knowledge_hit (doc_id,task_id,scene,chunk_ids)"
            f" VALUES ({doc_id},NULL,'STYLE_FEW_SHOT','[]'::jsonb)")
    return doc_id


def _purge(doc_id: int, uniq: str) -> None:
    sql_query(f"DELETE FROM tp_knowledge_hit WHERE doc_id={doc_id}")
    sql_query(f"DELETE FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id'='{doc_id}'")
    sql_query(f"DELETE FROM tp_knowledge_doc WHERE title LIKE 'KBV2%_{uniq}'")


def _goto_kb(page):
    page.goto(f"{page.url.split('#')[0]}{KB_PAGE}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="knowledge-page"]')).to_be_visible(timeout=15000)


def _table_rows(page):
    return page.locator('[data-test="kb-table"] .el-table__body tbody tr.el-table__row')


def test_kb_v2_detail_drawer_count_and_jump(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    prj = api_client.post("/projects", json={"name": f"KBV2_{uniq}", "code": f"KV{uniq[:6].upper()}"})
    assert prj.status_code == 200 and prj.json().get("code") == "0", f"创建项目失败: {prj.text[:300]}"
    pid = prj.json()["data"]["id"] if isinstance(prj.json()["data"], dict) else prj.json()["data"]
    case = api_client.post("/cases", json={
        "projectId": pid, "title": f"KBV2源用例_{uniq}", "precondition": "已登录",
        "expectedResult": "成功", "priority": 2, "confidence": 2,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开 /login", "isAssert": 0}],
    })
    assert case.status_code == 200 and case.json().get("code") == "0", f"创建用例失败: {case.text[:300]}"
    case_id = case.json()["data"]["id"]
    doc_id = _seed(pid, case_id, uniq)
    try:
        _goto_kb(page)
        row = _table_rows(page).filter(has_text=f"KBV2用例_{uniq}")
        expect(row).to_have_count(1, timeout=15000)
        row.locator(f'[data-test="kb-detail-open-{doc_id}"]').click()
        drawer = page.locator('[data-test="kb-detail-drawer"]')
        expect(drawer).to_be_visible()
        expect(drawer.locator('[data-test="kb-detail-content"]')).to_contain_text("十五分钟自动解锁。")
        expect(drawer.locator('[data-test="kb-detail-meta"]')).to_contain_text("3 次")
        hits_table = drawer.locator('[data-test="kb-hits-table"] .el-table__body tbody tr.el-table__row')
        expect(hits_table).to_have_count(3, timeout=10000)
        expect(hits_table.first).to_contain_text("关联任务已清理或未记录")
        expect(drawer.locator('[data-test="kb-chunk-head-0"]')).to_be_visible()
        expect(drawer.locator('[data-test="kb-chunk-head-1"]')).to_be_visible()
        drawer.locator('[data-test="kb-chunk-head-0"]').click()
        expect(drawer.locator('[data-test="kb-chunk-body-0"]')).to_contain_text(f"分块正文0-KBV2-{uniq}")
        expect(drawer.locator('[data-test="kb-chunk-meta-0"]')).to_contain_text("java")
        jump = drawer.locator('[data-test="kb-detail-source-jump"]')
        expect(jump).to_be_visible()
        expect(jump).to_contain_text("查看源用例")
        jump.click()
        page.wait_for_url(f"**/cases/{case_id}", timeout=15000)
        assert f"#/cases/{case_id}" in page.url, f"跳转未达用例详情: {page.url}"
        assert_no_errors(collectors, "TC-V2-01 详情抽屉计数/全文/跳转")
    finally:
        _purge(doc_id, uniq)


def test_kb_v2_noflash_term_note_reload_persist(logged_in, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    doc_id = int(sql_query(
        "INSERT INTO tp_knowledge_doc (project_id,pack_id,source_type,source_ref,title,content,status)"
        f" VALUES (0,0,'TERM',NULL,'KBV2术语_{uniq}','术语种子内容','INACTIVE') RETURNING id"
    ).strip().splitlines()[0])
    sql_query(f"INSERT INTO tp_knowledge_hit (doc_id,task_id,scene,chunk_ids) VALUES ({doc_id},NULL,'DDL_DICT','[]'::jsonb)")
    try:
        _goto_kb(page)
        row = _table_rows(page).filter(has_text=f"KBV2术语_{uniq}")
        expect(row).to_have_count(1, timeout=15000)
        row.locator(f'[data-test="kb-detail-open-{doc_id}"]').click()
        drawer = page.locator('[data-test="kb-detail-drawer"]')
        expect(drawer).to_be_visible()
        expect(drawer.locator('[data-test="kb-detail-source-jump"]')).to_have_count(0)
        expect(drawer.locator('[data-test="kb-detail-source-noflash"]')).to_contain_text("该来源无站内跳转目标")
        expect(drawer).to_contain_text("该文档暂无向量分块")
        drawer.locator('.el-drawer__close-btn').first.click()
        expect(drawer).to_be_hidden(timeout=10000)
        _goto_kb(page)
        page.locator(f'[data-test="kb-detail-open-{doc_id}"]').click()
        expect(drawer).to_be_visible()
        expect(drawer.locator('[data-test="kb-detail-meta"]')).to_contain_text("1 次")
        assert_no_errors(collectors, "TC-V2-01 注记/持久化链路")
    finally:
        _purge(doc_id, uniq)
