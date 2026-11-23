
import uuid

import pytest
from playwright.sync_api import expect

from conftest import assert_no_errors, hard_goto, sql_query


pytestmark = pytest.mark.xdist_group("kb-admin")

KB_PAGE = "#/knowledge"


def _seed(project_id: int, uniq: str) -> list[int]:
    pass
    ids = []
    case_ref = int(uniq[:6], 16)
    for source, proj, title, ref in (
            ("TEST_CASE", project_id, f"KBSEED用例_{uniq}", str(case_ref)),
            ("TERM", 0, f"KBSEED术语_{uniq}", "NULL")):
        out = sql_query(
            "INSERT INTO tp_knowledge_doc (project_id,pack_id,source_type,source_ref,title,content,status)"
            f" VALUES ({proj},0,'{source}',{ref},'{title}','种子内容-{title}','INACTIVE') RETURNING id"
        )
        ids.append(int(out.strip().splitlines()[0]))
    return ids


def _purge(uniq: str) -> None:
    sql_query(f"DELETE FROM tp_knowledge_doc WHERE title LIKE 'KBSEED%_{uniq}'")


def _goto_kb(page):
    # Hard navigation on purpose: the header project dropdown reads the
    # visible-domain snapshot injected by loadMe at app boot, and this test
    # creates its project via the API after login. A same-document hash goto
    # keeps the stale snapshot, so the fresh project never reaches the
    # dropdown (measured: 30s click timeout on the option). Routing through a
    # blank document re-runs the boot chain, which is the state real users
    # reach by refreshing after project creation.
    hard_goto(page, f"{page.url.split('#')[0]}{KB_PAGE}")
    expect(page.locator('[data-test="knowledge-page"]')).to_be_visible(timeout=15000)


def _table_rows(page):
    return page.locator('[data-test="kb-table"] .el-table__body tbody tr.el-table__row')


def test_kb_list_filters_reload(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    prj = api_client.post("/projects", json={"name": f"KB台账_{uniq}", "code": f"KB{uniq[:6].upper()}"})
    assert prj.status_code == 200 and prj.json().get("code") == "0", f"创建项目失败: {prj.text[:300]}"
    pid = prj.json()["data"]
    _seed(pid, uniq)
    try:
        _goto_kb(page)
        expect(_table_rows(page).filter(has_text=f"KBSEED术语_{uniq}")).to_have_count(1, timeout=15000)
        expect(_table_rows(page).filter(has_text=f"KBSEED用例_{uniq}")).to_have_count(1)
        expect(_table_rows(page).filter(has_text=f"KBSEED术语_{uniq}")).to_contain_text("已失效")
        expect(page.locator('[data-test="kb-pager"]')).to_be_visible()

        page.reload(wait_until="domcontentloaded")
        expect(_table_rows(page).filter(has_text=f"KBSEED术语_{uniq}")).to_have_count(1, timeout=15000)
        expect(_table_rows(page).filter(has_text=f"KBSEED用例_{uniq}")).to_have_count(1)

        page.locator('[data-test="project-ctx-select"]').click()
        page.locator(f".el-select-dropdown:visible .el-select-dropdown__item:has-text('KB台账_{uniq}')").first.click()
        expect(_table_rows(page)).to_have_count(1, timeout=10000)
        expect(_table_rows(page).first).to_contain_text(f"KBSEED用例_{uniq}")

        page.locator('[data-test="kb-source-select"]').click()
        page.locator(".tp-kb-source-popper .el-select-dropdown__item:has-text('测试用例')").first.click()
        expect(_table_rows(page)).to_have_count(1)
        page.locator('[data-test="kb-source-select"]').click()
        page.locator(".tp-kb-source-popper .el-select-dropdown__item:has-text('术语')").first.click()
        expect(page.locator('[data-test="empty-state"]').first).to_be_visible(timeout=10000)

        assert_no_errors(collectors, "kb list/filters/reload")
    finally:
        _purge(uniq)
        api_client.delete(f"/projects/{pid}")


def test_kb_rebuild_entry(logged_in, api_client, collectors):
    pass
    page = logged_in
    uniq = uuid.uuid4().hex[:8]
    _seed(0, uniq)
    try:
        _goto_kb(page)
        expect(_table_rows(page).filter(has_text=f"KBSEED用例_{uniq}")).to_have_count(1, timeout=15000)

        page.locator('[data-test="kb-rebuild-open"]').click()
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('取消')").click()
        expect(page.locator(".el-message-box")).to_have_count(0, timeout=5000)
        expect(page.locator('[data-test="kb-rebuild-result-dialog"]')).to_have_count(0)

        page.locator('[data-test="kb-rebuild-open"]').click()
        expect(page.locator(".el-message-box")).to_be_visible(timeout=5000)
        page.locator(".el-message-box__btns button:has-text('重建')").click()
        dialog = page.locator('[data-test="kb-rebuild-result-dialog"]')
        expect(dialog).to_be_visible(timeout=120000)
        body = dialog.locator(".el-dialog__body")
        expect(body).to_contain_text("重嵌入补齐的台账数")
        expect(body).to_contain_text("剔除的孤儿向量文档数")
        expect(body).to_contain_text("扫描台账 ACTIVE 行数")
        expect(body).to_contain_text("重建后向量总行数")
        page.locator('[data-test="kb-rebuild-close"]').click()
        expect(dialog).to_have_count(0, timeout=5000)

        assert_no_errors(collectors, "kb rebuild entry")
    finally:
        _purge(uniq)
