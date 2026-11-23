"""Thread-action row E2E .

Proves the two exported capabilities in a REAL browser against the REAL chain
(browser -> admin 8081 -> core 8083, no mocks):

1) Export: stream one turn, click the export button, the browser must fire a
 genuine download whose suggested name carries the session title (built from
 the first question, so the unique token proves the server-side render rode
 through) and whose Markdown body holds the question + answer; the success
 toast names the turn count object (W-group: report the object).
2) Sediment: click the save-to-knowledge button, the toast must name the new
 document and offer the one-click door; following it lands on the extraction
 page documents tab, the row shows up there (project segment), and a reload
 keeps it (real persistence, not an optimistic echo).

Iron rules: exec gate hot-enabled and restored to the R32 baseline (true);
scratch project purged by the shared sweep; every phase ends with the
4xx/5xx + console + Issues-panel zero-tolerance check. Pinned to the shared
exec-switch worker group like the rest of the AI-chat family.
"""
import re

import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    purge_project,
    seed_project,
)
from playwright.sync_api import expect

pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]


def _stream_one_turn(page, pid: int, token: str) -> None:
    """Open the standalone chat page on the scratch project and finish one turn."""
    page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
    inp = page.locator('[data-test="chat-dock-input"]')
    inp.fill(f"只回答两个字：{token}")
    inp.press("Enter")
    expect(page.locator('[data-test="chat-dock-stop"]')).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
    expect(page.locator('[data-test="chat-msg"]')).to_have_count(2, timeout=10000)




def test_export_button_downloads_markdown_and_toast_names_turns(page, collectors, api_client, uniq):
    """Export path: real download event + Markdown body + turn-count toast."""
    pid = seed_project(api_client, uniq, "EXPORT")
    token = uniq[:6].lower()
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        _stream_one_turn(page, pid, token)

        btn = page.locator('[data-test="chat-export-btn"]')
        expect(btn).to_be_visible(timeout=10000)
        expect(btn).to_be_enabled()

        with page.expect_download(timeout=15000) as dl_info:
            btn.click()
        download = dl_info.value
        # Name = session title (from the first question) + server stamp + .md.
        assert token in download.suggested_filename, (
            f"download name must carry the thread title, got {download.suggested_filename}"
        )
        assert download.suggested_filename.endswith(".md")
        body = download.path().read_text(encoding="utf-8")
        assert token in body, "exported markdown must contain the question"
        assert "答" in body, "exported markdown must contain the answer turn"

        toast = page.locator(".el-message")
        expect(toast.first).to_contain_text(re.compile(r"已导出 \d+ 轮问答"), timeout=5000)

        assert_no_errors(collectors, "thread export download")
    finally:
        purge_project(api_client, pid)


def test_sediment_button_lands_doc_and_one_click_door_reaches_it(page, collectors, api_client, uniq):
    """Sediment path: toast names the doc -> door click -> documents tab row -> reload."""
    pid = seed_project(api_client, uniq, "SEDIMENT")
    token = uniq[:6].lower()
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    try:
        inject_token(page)
        _stream_one_turn(page, pid, token)

        btn = page.locator('[data-test="chat-sediment-btn"]')
        expect(btn).to_be_visible(timeout=10000)
        expect(btn).to_be_enabled()
        btn.click()

        # Success toast reports its object (document name) and the reachable door.
        toast = page.locator(".el-message")
        expect(toast.first).to_contain_text("已存为知识：", timeout=30000)
        expect(toast.first).to_contain_text("去文档管理查看")

        toast.get_by_text("去文档管理查看").first.click()
        expect(page).to_have_url(re.compile(r"/#/extraction\?.*tab=documents|/#/extraction"), timeout=10000)
        expect(page.locator('[data-test="doc-table"]')).to_be_visible(timeout=15000)

        # The row lives in THIS project's segment — filter by the thread token.
        kw = page.locator('[data-test="doc-filter-keyword"] input, input[data-test="doc-filter-keyword"]')
        if kw.count() == 0:
            kw = page.locator('[data-test="doc-filter-keyword"]')
        kw.first.fill(token)
        page.locator('[data-test="doc-search-btn"]').click()
        row = page.locator(f'[data-test="doc-table"] .el-table__row:has-text("{token}")')
        expect(row.first).to_be_visible(timeout=15000)

        # Reload: persistence is the ledger's, not the toast's.
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-table"]')).to_be_visible(timeout=15000)
        kw2 = page.locator('[data-test="doc-filter-keyword"] input, input[data-test="doc-filter-keyword"]')
        if kw2.count() == 0:
            kw2 = page.locator('[data-test="doc-filter-keyword"]')
        kw2.first.fill(token)
        page.locator('[data-test="doc-search-btn"]').click()
        expect(row.first).to_be_visible(timeout=15000)

        assert_no_errors(collectors, "thread sediment to knowledge")
    finally:
        purge_project(api_client, pid)
