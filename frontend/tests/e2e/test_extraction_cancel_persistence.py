""" E2E: explicit cancel of a running extraction persists through the real chain.

Covers (all real, zero mock — admin -> Feign -> core -> MQ -> ai-service -> real LLM):
1. Start extraction on a deliberately multi-section document so the run stays in
 status=3 long enough to observe (real LLM ~10s for the small F2 fixture; twelve
 sections keep the parsing state well past the cancel click).
2. The in-progress dialog renders [data-test=extract-cancel-btn] (canWrite gate);
 one click POSTs /ai/documents/{id}/extraction-cancel, the success toast names the
 action, and the dialog flips to the failure block showing the core's readable
 cancel text (status=1 + non-empty extractError, pit c distinction).
3. Persistence proof: hard DB pin on status/extract_error, then close + reload +
 reopen — the failure block with the same text comes back from the server, so the
 cancel is not an in-memory artifact.
4. Iron rule 5d: every rendering assertion is hard (no conditional wrap); the
 generation guard that rejects the old run's late write-back is pinned in L2
 (cancelledGenerationWritebackIsRejectedByGuard) and is not re-litigated here.
5. Zero-residue finally: document deleted, project purged, doc|req counts = 0|0.
"""
import re

import httpx
from playwright.sync_api import expect

from conftest import (ADMIN_PASSWORD, API_BASE, assert_no_errors, api_login,
                      inject_user_token, sql_query, teardown_extraction_case)

from test_f2_extraction import (doc_page, make_project, open_extract_dialog,
                                upload_doc)

CANCEL_TEXT = "已手动取消本轮提取任务，可随时重新发起提取"


def sql(q):
    # Lane-aware via conftest.sql_query single source (TP_E2E_DB_NAME env).
    return sql_query(q)


def long_doc_content(uniq):
    """Twelve business sections: keeps the real LLM run in status=3 for a while."""
    topics = [
        "提现限额规则", "风控校验规则", "银行卡四要素核验", "单日累计额度重置",
        "人工审批通道", "出金延迟规则", "黑名单拦截", "证件有效期校验",
        "支付密码错误锁定", "异步通知重试", "对账文件生成", "灰度开关控制",
    ]
    body = f"# 提现业务需求文档 {uniq}\n\n"
    for i, topic in enumerate(topics, 1):
        body += (f"## {topic}\n\n"
                 f"系统必须实现第{i}条规则「{topic}」：当触发条件满足时执行对应校验，"
                 f"校验失败时拒绝操作并向用户返回可读原因。{uniq}-{i}\n\n")
    return body


def test_extraction_cancel_persists(page, collectors, api_client, uniq):
    """Cancel a running extraction from the dialog; failure state survives reload."""
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "K")
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "k",
                                        content=long_doc_content(uniq))
        doc_page(page, pid)

        # ── 1. open dialog, submit, wait for the in-progress block ──
        dialog = open_extract_dialog(page, name)
        with page.expect_response(
                lambda r: f"/ai/documents/{doc_id}/extract" in r.url and r.status == 200,
                timeout=20000):
            dialog.locator('[data-test="extract-submit-btn"]').click()
        expect(page.locator('.el-message--success').filter(
            has_text="提取任务已提交").first).to_be_visible(timeout=15000)
        parsing = dialog.locator('[data-test="extract-parsing-hint"]')
        expect(parsing).to_be_visible(timeout=15000)

        # ── 2. one click on the cancel button (canWrite-gated, present above) ──
        cancel_btn = dialog.locator('[data-test="extract-cancel-btn"]')
        expect(cancel_btn).to_be_visible(timeout=5000)
        with page.expect_response(
                lambda r: f"/ai/documents/{doc_id}/extraction-cancel" in r.url
                and r.status == 200,
                timeout=20000):
            cancel_btn.click()
        expect(page.locator('.el-message--success').filter(
            has_text=re.compile("已取消提取")).first).to_be_visible(timeout=15000)

        # ── 3. dialog flips to the failure block with the core's cancel text ──
        error_alert = dialog.locator('[data-test="extract-error-alert"]')
        expect(error_alert).to_be_visible(timeout=15000)
        expect(error_alert).to_contain_text(CANCEL_TEXT, timeout=10000)

        # ── 4. DB pin: status=1 + readable extractError (server truth) ──
        row = sql("SELECT status || '|' || coalesce(extract_error,'') "
                  f"FROM tp_document WHERE id={doc_id}")
        assert row == f"1|{CANCEL_TEXT}", f"取消回写 DB 实钉失败: {row}"

        # ── 5. reload + reopen: the failure block comes back from the server ──
        dialog.locator('[data-test="extract-close-btn"]').click()
        page.wait_for_selector('[data-test="extract-dialog"]', state="hidden", timeout=10000)
        page.reload(wait_until="domcontentloaded")
        open_extract_dialog(page, name)
        reopened = page.locator('[data-test="extract-error-alert"]')
        expect(reopened).to_be_visible(timeout=15000)
        expect(reopened).to_contain_text(CANCEL_TEXT, timeout=10000)

        assert_no_errors(collectors, "T567 提取取消持久化 全链")
    finally:
        teardown_extraction_case(api_client, doc_id, pid)
        if pid:
            resid = sql("SELECT (SELECT count(*) FROM tp_document WHERE project_id=" + str(pid) + ") || '|' || "
                        "(SELECT count(*) FROM tp_requirement WHERE project_id=" + str(pid) + ")")
            assert resid == "0|0", f"零残留失败（文档|需求点）: {resid}"
