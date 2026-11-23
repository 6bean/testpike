
import os
import re
import subprocess
import tempfile
import time

from conftest import ADMIN_PASSWORD, BASE_URL, assert_no_errors, inject_user_token, sql_query  # noqa: F401
from playwright.sync_api import expect

import httpx
import pytest

pytestmark = pytest.mark.timeout(240)

API_BASE = os.environ.get("TP_E2E_API_BASE", "http://localhost:8081/api/v1")


def _sql(q: str) -> str:
    return sql_query(q)


def _md_v1(title: str) -> bytes:
    return (f"# {title}\n\n"
            "## 下单流程\n\n下单需要校验库存并锁定优惠。E2E-F3。\n\n"
            "## 支付流程\n\n支付支持微信与支付宝双通道。E2E-F3。\n\n"
            "## 退款流程\n\n退款原路返回，T+1 到账。E2E-F3。\n").encode()


def _md_v2(title: str) -> bytes:
    return (f"# {title}\n\n"
            "## 下单流程\n\n下单需要校验库存并锁定优惠。（改版：增加风控前置）E2E-F3。\n\n"
            "## 支付流程\n\n支付支持微信与支付宝双通道。E2E-F3。\n\n"
            "## 发票流程\n\n支付完成可开电子发票。E2E-F3。\n").encode()


def _upload(api_client, pid, title, content: bytes):
    r = httpx.post(f"{API_BASE}/ai/documents", headers=api_client.headers,
                   files={"file": (f"{title}.md", content, "text/markdown")},
                   data={"projectId": str(pid)}, timeout=60)
    body = r.json()
    assert body.get("code") == "0", f"上传失败: {str(body)[:200]}"
    assert body["data"]["status"] == 1, f"解析未成功: {str(body['data'])[:200]}"
    return body["data"]["id"]


def _open_upload_dialog(page):
    page.locator('[data-test="doc-upload-btn"]').first.click()
    dialog = page.locator('[data-test="file-upload-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    return dialog


def upload_via_ui(page, path):
    d = _open_upload_dialog(page)
    d.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(path)
    submit = d.locator('[data-test="file-upload-submit"]')
    expect(submit).to_be_enabled(timeout=3000)
    submit.click()


def test_f3_doc_change_review_queue(page, collectors, api_client, uniq):
    pass
    title = f"E2E订单系统需求{uniq}"
    r = api_client.post("/projects", json={"name": f"F3变更分析{uniq}", "code": f"F3C{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    tmpdir = tempfile.mkdtemp(prefix="f3doc-")
    try:
        inject_user_token(page, "admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)

        expect(page.locator('[data-test="doc-change-panel"]')).to_be_visible(timeout=5000)
        expect(page.locator('[data-test="doc-change-empty"]')).to_be_visible(timeout=5000)

        v1_id = _upload(api_client, pid, title, _md_v1(title))
        chapter_order = f"{title} > 下单流程"
        _sql(f"INSERT INTO tp_requirement (project_id, source, source_id, title, status, chapter)"
             f" VALUES ({pid}, 2, 'doc:{v1_id}', '下单校验库存E2E{uniq}', 1, '{chapter_order}')")
        req_id = _sql(f"SELECT id FROM tp_requirement WHERE title = '下单校验库存E2E{uniq}'")
        r = api_client.post("/cases", json={
            "projectId": pid, "title": f"E2E-下单校验{uniq}", "precondition": "无",
            "expectedResult": "OK", "priority": 1, "endType": "web"})
        data = r.json().get("data")
        case_id = data if isinstance(data, int) else (data or {}).get("id")
        assert case_id, f"建用例失败: {r.text[:200]}"
        _sql(f"INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)"
             f" VALUES ({case_id}, {req_id}, 1)")
        page.reload(wait_until="domcontentloaded")
        row_v1 = page.locator('[data-test="doc-table"] tbody tr').filter(has_text=title).first
        expect(row_v1).to_be_visible(timeout=15000)

        v2_path = os.path.join(tmpdir, f"{title}.md")
        with open(v2_path, "wb") as f:
            f.write(_md_v2(title))
        upload_via_ui(page, v2_path)
        expect(page.locator('.el-message--success').filter(has_text="解析完成").first)\
            .to_be_visible(timeout=20000)

        row_v2 = page.locator('[data-test="doc-table"] tbody tr').filter(has_text=title).first
        expect(row_v2.locator('[data-test="ra-reanalyze"]')).to_be_visible(timeout=10000)
        row_v2.locator('[data-test="ra-reanalyze"]').click()
        expect(page.locator('.el-message--success').filter(has_text="变更分析完成").first)\
            .to_be_visible(timeout=20000)
        ana_row = page.locator('[data-test="doc-change-table"] tbody tr').first
        expect(ana_row).to_be_visible(timeout=10000)
        ana_id = _sql("SELECT max(id) FROM tp_doc_change_analysis WHERE project_id = " + str(pid))
        assert ana_id, "分析行未落库"
        expect(ana_row.locator('[data-test="doc-change-status"]')).to_have_text("待复核")
        expect(ana_row.locator('[data-test="doc-change-count"]')).to_have_text("3")
        expect(page.locator('[data-test="doc-change-pending-alert"]'))\
            .to_contain_text("1 条待复核")
        assert int(_sql("SELECT count(*) FROM tp_notification WHERE event_type ="
                       " 'DOC_CHANGE_ANALYZED' AND dedup_key LIKE "
                       f"'DOC_CHANGE_ANALYZED:{ana_id}:%'")) >= 1, "复核提醒站内信缺失"

        ana_row.locator('.el-table__expand-icon').click()
        detail = page.locator('[data-test="doc-change-detail"]')
        expect(detail).to_be_visible(timeout=5000)
        expect(detail).to_contain_text("下单流程")
        expect(detail).to_contain_text("发票流程")
        expect(detail).to_contain_text("退款流程")
        modify_item = detail.locator('[data-test="doc-change-items"] tbody tr')\
            .filter(has_text="下单流程").first
        expect(modify_item.locator('[data-test="doc-change-reqcount"]')).to_have_text("1")
        expect(modify_item.locator('[data-test="doc-change-casecount"]')).to_have_text("1")
        expect(modify_item.locator('[data-test="doc-change-type"]')).to_have_text("修改")
        delete_item = detail.locator('[data-test="doc-change-items"] tbody tr')\
            .filter(has_text="退款流程").first
        expect(delete_item.locator('[data-test="doc-change-type"]')).to_have_text("删除")

        page.locator('[data-test="doc-change-filter-status"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text="已处置").first.click()
        expect(page.locator('[data-test="doc-change-table"] tbody tr')).to_have_count(0, timeout=8000)
        page.locator('[data-test="doc-change-filter-status"]').click()
        page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter(has_text="待复核").first.click()
        expect(page.locator('[data-test="doc-change-table"] tbody tr').first)\
            .to_be_visible(timeout=8000)

        ana_row = page.locator('[data-test="doc-change-table"] tbody tr').first
        ana_row.locator('[data-test="doc-change-handle-btn"]').click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=5000)
        expect(box).to_contain_text("已处置")
        note = f"已复核：下单用例需补风控步骤E2E{uniq}"
        box.locator('.el-message-box__input input').fill(note)
        box.locator('button:has-text("确认处置")').click()
        expect(page.locator('.el-message--success').filter(has_text="已处置并留痕").first)\
            .to_be_visible(timeout=15000)
        _sql("SELECT 1")
        row_db = _sql(f"SELECT status || '|' || coalesce(handle_note,'')"
                      f" FROM tp_doc_change_analysis WHERE id = {ana_id}")
        assert row_db == f"1|{note}", f"DB 处置留痕不符: {row_db}"

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-change-table"] tbody tr').first)\
            .to_be_visible(timeout=15000)
        first = page.locator('[data-test="doc-change-table"] tbody tr').first
        expect(first.locator('[data-test="doc-change-status"]')).to_have_text("已处置")
        expect(first.locator('[data-test="doc-change-handledat"]')).not_to_have_text("-", timeout=5000)
        expect(first.locator('[data-test="doc-change-note"]')).to_contain_text(note)

        assert_no_errors(collectors, "F3 变更分析复核队列 触发/清单/筛选/处置/持久化 全链")
    finally:
        try:
            _sql(f"DELETE FROM tp_doc_change_analysis WHERE project_id = {pid}")
            _sql(f"DELETE FROM tp_notification WHERE project_id = {pid}")
            ids = _sql(f"SELECT id FROM tp_document WHERE project_id = {pid}")
            for i in ids.splitlines():
                subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                                "-d", "testpike", "-tAc",
                                f"DELETE FROM tp_document_chunk WHERE document_id = {i}"],
                               capture_output=True)
            _sql(f"DELETE FROM tp_document WHERE project_id = {pid}")
        except Exception:
            pass
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        assert _sql(f"SELECT count(*) FROM tp_doc_change_analysis WHERE project_id = {pid}") == "0"
        assert _sql(f"SELECT count(*) FROM tp_document WHERE project_id = {pid}") == "0"


# ── Disposition-queue upgrade (revision-draft receipt + chat deep link) ──
# Fixture plumbing (project/docs/requirement/case/reanalyze) goes through the
# real admin API exactly like the L3 writeback script; only the disposition
# queue UI and the ?session= door are under test here.

def _seed_pending_analysis(api_client, uniq, tag):
    """Two same-named doc versions + one affected requirement/case, then run
    reanalyze over the real chain. Returns (pid, req_id, case_title)."""
    title = f"E2E订单系统需求{uniq}"
    r = api_client.post("/projects",
                        json={"name": f"F3草案回执{tag}{uniq}", "code": f"F3{tag}{uniq[:5]}"})
    assert r.json().get("code") == "0", f"project create failed: {r.text[:300]}"
    pid = r.json()["data"]
    v1_id = _upload(api_client, pid, title, _md_v1(title))
    chapter_order = f"{title} > 下单流程"
    _sql(f"INSERT INTO tp_requirement (project_id, source, source_id, title, status, chapter)"
         f" VALUES ({pid}, 2, 'doc:{v1_id}', '下单校验库存E2E{uniq}', 1, '{chapter_order}')")
    req_id = int(_sql(f"SELECT id FROM tp_requirement WHERE title = '下单校验库存E2E{uniq}'"))
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"E2E-下单校验{uniq}", "precondition": "无",
        "expectedResult": "OK", "priority": 1, "endType": "web"})
    data = r.json().get("data")
    case_id = data if isinstance(data, int) else (data or {}).get("id")
    assert case_id, f"case create failed: {r.text[:200]}"
    _sql(f"INSERT INTO tp_case_requirement (case_id, requirement_id, created_by)"
         f" VALUES ({case_id}, {req_id}, 1)")
    v2_id = _upload(api_client, pid, title, _md_v2(title))
    r = api_client.post("/doc-change-analyses/reanalyze", json={"newDocumentId": v2_id})
    assert r.json().get("code") == "0", f"reanalyze failed: {r.text[:300]}"
    return pid, req_id, f"E2E-下单校验{uniq}"


def _open_pending_row(page, pid):
    """Deep-link the documents page (i6 two-step posture) and surface the
    pending review row of the freshly seeded analysis."""
    inject_user_token(page, "admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
    row = page.locator('[data-test="doc-change-table"] tbody tr').first
    expect(row).to_be_visible(timeout=15000)
    expect(row.locator('[data-test="doc-change-status"]')).to_have_text("待复核")
    return row


def _pregen_and_wait_drafts(page, row, pid):
    """Click the pre-generation door, assert the receipt bar, then wait for
    the asynchronously dispatched draft generation to reach the database."""
    pregen = row.locator('[data-test="doc-change-pregen-btn"]')
    expect(pregen).to_be_visible(timeout=5000)
    pregen.click()
    expect(page.locator('.el-message--success').filter(has_text="已生成").first) \
        .to_be_visible(timeout=40000)
    receipt = page.locator('[data-test="doc-change-draft-receipt"]')
    expect(receipt).to_be_visible(timeout=10000)
    door = receipt.locator('[data-test="doc-change-draft-go"]')
    expect(door).to_contain_text("去对话确认", timeout=10000)
    deadline = time.time() + 150
    landed = 0
    while time.time() < deadline:
        landed = int(_sql("SELECT count(*) FROM tp_ai_chat_draft WHERE session_id IN "
                          f"(SELECT id FROM tp_ai_chat_session WHERE project_id = {pid})"))
        if landed >= 1:
            break
        time.sleep(3)
    assert landed >= 1, f"draft generation never landed for project {pid}"
    return door


def _purge_with_chat(api_client, pid):
    """Teardown: the dispatched draft session keeps landing frames during
    cleanup (L3 precedent), so the chat subtree is dropped in converging
    rounds before the generic project purge."""
    time.sleep(8)
    sess = f"(SELECT id FROM tp_ai_chat_session WHERE project_id = {pid})"
    msgs = f"(SELECT id FROM tp_ai_chat_message WHERE session_id IN {sess})"
    for _ in range(4):
        try:
            _sql(f"DELETE FROM tp_ai_chat_frame WHERE message_id IN {msgs}")
            _sql(f"DELETE FROM tp_ai_chat_message WHERE session_id IN {sess}")
            _sql(f"DELETE FROM tp_ai_chat_draft WHERE session_id IN {sess}")
            _sql(f"DELETE FROM tp_ai_chat_session WHERE project_id = {pid}")
            _sql("DELETE FROM tp_requirement_version WHERE requirement_id IN "
                 f"(SELECT id FROM tp_requirement WHERE project_id = {pid})")
            _sql(f"DELETE FROM tp_doc_change_analysis WHERE project_id = {pid}")
            _sql(f"DELETE FROM tp_notification WHERE project_id = {pid}")
            break
        except Exception:
            time.sleep(4)
    for i in _sql(f"SELECT id FROM tp_document WHERE project_id = {pid}").splitlines():
        subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc",
                        f"DELETE FROM tp_document_chunk WHERE document_id = {i}"],
                       capture_output=True)
    _sql(f"DELETE FROM tp_document WHERE project_id = {pid}")
    api_client.delete(f"/projects/{pid}")
    api_client.delete(f"/projects/{pid}/purge?force=true")
    assert _sql(f"SELECT count(*) FROM tp_doc_change_analysis WHERE project_id = {pid}") == "0"


@pytest.mark.timeout(540)
def test_f3_pregen_receipt_and_affected_case_names(page, collectors, api_client, uniq):
    """Expanded row names the affected case; the pre-generation door answers
    with a receipt bar (dispatched counts + human confirmation entry)."""
    pid, req_id, case_title = _seed_pending_analysis(api_client, uniq, "A")
    try:
        row = _open_pending_row(page, pid)
        row.locator('.el-table__expand-icon').click()
        detail = page.locator('[data-test="doc-change-detail"]')
        expect(detail).to_be_visible(timeout=5000)

        # Affected cases resolve lazily to real titles — never a bare id chip
        # while the case exists (fallback wording only for vanished cases).
        affected = detail.locator('[data-test="doc-change-affected"]')
        expect(affected).to_be_visible(timeout=10000)
        chips = affected.locator('[data-test="doc-change-case-chip"]')
        expect(chips.first).to_contain_text(case_title, timeout=15000)
        assert chips.filter(has_text="用例已不可用").count() == 0, \
            "unavailable-case fallback shown while the case still exists"

        _pregen_and_wait_drafts(page, row, pid)

        # The receipt is presentation only: the write gate stays shut until a
        # human accepts the draft inside the chat session.
        st = _sql(f"SELECT status || '/' || version FROM tp_requirement WHERE id = {req_id}")
        assert st == "1/1", f"draft pre-generation auto-applied! status/version={st}"
        assert int(_sql(f"SELECT count(*) FROM tp_requirement_version "
                        f"WHERE requirement_id = {req_id}")) == 0, \
            "version snapshot written without a human confirmation"

        assert_no_errors(collectors, "F3 pregen receipt + named affected cases")
    finally:
        _purge_with_chat(api_client, pid)


@pytest.mark.timeout(540)
def test_f3_draft_session_deeplink_lands_pending_confirmation(page, collectors, api_client, uniq):
    """The receipt's chat door deep-links /ai/chat?session=<id> and the
    conversation renders the pending draft card with its one-click confirm."""
    pid, req_id, _ = _seed_pending_analysis(api_client, uniq, "B")
    try:
        row = _open_pending_row(page, pid)
        row.locator('.el-table__expand-icon').click()
        expect(page.locator('[data-test="doc-change-detail"]')).to_be_visible(timeout=5000)
        door = _pregen_and_wait_drafts(page, row, pid)

        session_id = int(_sql("SELECT max(id) FROM tp_ai_chat_session WHERE project_id = "
                              + str(pid)))
        assert session_id > 0, "pregen chat session missing"
        door.click()
        expect(page).to_have_url(
            re.compile(rf"/#/ai/chat\?session={session_id}&projectId={pid}"), timeout=15000)

        card = page.locator('[data-test="chat-draft-card"]').first
        expect(card).to_be_visible(timeout=60000)
        confirm = card.locator('[data-test="chat-draft-confirm"]')
        expect(confirm).to_be_visible(timeout=10000)

        # Landing on the session must not mutate the requirement by itself.
        st = _sql(f"SELECT status || '/' || version FROM tp_requirement WHERE id = {req_id}")
        assert st == "1/1", f"deep link auto-applied! status/version={st}"

        assert_no_errors(collectors, "F3 draft session deep link to chat")
    finally:
        _purge_with_chat(api_client, pid)
