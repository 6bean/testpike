"""Composer paste E2E spectrum .

Drives real ClipboardEvent paste dispatches against the live dialog composer
and asserts the full wire, not just the DOM:
- pasted screenshot bytes ride the SHARED upload door (the same
 POST /ai/chat/images channel the file picker uses -- proof there is no
 second upload path), land as a ready chip, and the fresh question's
 /stream body carries exactly the server-issued objectKey/fileName pair
 (the server-side attachment record: core minted the canonical key under
 the issuing project and the run door passed its H1 segment check with no
 refusal);
- an Excel-shaped TSV clipboard becomes a GFM pipe table at the caret and
 the outbound prompt carries the ``|---`` separator row;
- an HTML clipboard holding two <table> elements converts BOTH, in
 document order;
- with the hot-update switch ai.chat.paste-table-convert.enabled pinned
 OFF (restored at case end), the same TSV stays verbatim -- raw tabs, no
 separator row (off-state honesty leg);
- a multi-line converted paste never sends by itself, and one Enter press
 still sends exactly once (R33: paste is orthogonal to the Enter-sends
 preference).

Every case ends with the 4xx/5xx + console + Issues-panel zero-tolerance
check; the browser runs through the shared new-headless rig posture.
"""
import json
import re

import pytest
from conftest import (
    API_BASE,
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    get_sys_config_value,
    inject_token,
    purge_project as _purge_project,
    seed_project as _seed_project,
    sql_query,
)
from playwright.sync_api import expect

# Shares the serialized ai-chat worker group: this family pins and restores
# the paste-table switch on the shared config plane, so it must never run
# beside another chat spectrum that reloads the page mid-case.
pytestmark = [pytest.mark.timeout(420), pytest.mark.xdist_group("ai-chat-exec-switch")]

PASTE_TABLE_KEY = "ai.chat.paste-table-convert.enabled"
# 64x64 solid-blue PNG. Pixels matter on this leg: the booked image rides the
# real vision read channel (tp_ai_call_log row assertion), and the provider
# rejects either side <= 10px, so the old 1x1 placeholder degraded every read.
PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAeklEQVR4nO3PUQkAIBTAwBfHOPbH"
    "MIbw4xAGC3Cbtc/XDRc0oAUNaEEDWtCAFjSgBQ1oQQNa0IAWNKAFDWhBA1rQgBY0oAUNaEEDWtCA"
    "FjSgBQ1oQQNa0IAWNKAFDWhBA1rQgBY0oAUNaEEDWtCAFjSgBQ1oQQNa8NgF+yZBPGwJpg8AAAAA"
    "SUVORK5CYII="
)

TSV_CLIPBOARD = "姓名\t分数\n阿明\t95\n阿红\t80"
HTML_TWO_TABLES = (
    "<p>前置说明</p>"
    "<table><tr><th>环境</th><th>地址</th></tr><tr><td>dev</td><td>10.0.0.1</td></tr></table>"
    "<p>中间说明</p>"
    "<table><tr><th>端口</th></tr><tr><td>8081</td></tr></table>"
)

# The paste handler reads clipboardData off a synthetic ClipboardEvent: a
# DataTransfer built in-page (items.add for files, setData for the text
# flavours) dispatched as 'paste' on the composer textarea. cancelable so
# preventDefault semantics stay observable -- the dispatch result is handed
# back so off-state cases can assert the event was NOT consumed.
_PASTE_JS = """
([b64, mime, plain, html]) => {
  const el = document.querySelector('[data-test="chat-dock-input"]');
  el.focus();
  const dt = new DataTransfer();
  if (b64) {
    const bin = atob(b64);
    const u8 = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    dt.items.add(new File([u8], '现场截图.png', { type: mime }));
  }
  if (plain !== null) dt.setData('text/plain', plain);
  if (html !== null) dt.setData('text/html', html);
  const evt = new ClipboardEvent('paste', {
    clipboardData: dt, bubbles: true, cancelable: true,
  });
  el.dispatchEvent(evt);
  return evt.defaultPrevented;
}
"""


def _paste(page, b64=None, mime="image/png", plain=None, html=None):
    """Dispatch one synthetic paste; returns defaultPrevented."""
    return page.evaluate(_PASTE_JS, [b64, mime if b64 else None, plain, html])


def _open_chat(page, pid, wait_switch_read=False):
    """Enter the chat page. With wait_switch_read the navigation response
    window also covers the mount-time GET /configs read of the hot-update
    paste switch: starting a paste before that lands leaves the factory
    default governing the composer (fixture race, product unchanged)."""
    inject_token(page)
    if wait_switch_read:
        with page.expect_response(
                lambda r: "/configs" in r.url and "groupName" in r.url,
                timeout=15000):
            page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}",
                      wait_until="domcontentloaded")
        # Drain the page task queue so the config promise callback (which
        # writes the switch ref) has run before the first paste dispatch.
        page.evaluate("() => new Promise(r => setTimeout(r, 300))")
    else:
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}",
                  wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
    expect(page.locator('[data-test="chat-dock-input"]')).to_be_visible(timeout=5000)


def _send_and_capture_stream(page):
    """Enter-send one composed question and return the parsed /stream body."""
    inp = page.locator('[data-test="chat-dock-input"]')
    with page.expect_request(
            lambda r: r.method == "POST" and r.url.endswith("/stream")) as req_info:
        inp.press("Enter")
    return json.loads(req_info.value.post_data)


def _pin_paste_switch(value):
    pre = get_sys_config_value(PASTE_TABLE_KEY) or "true"
    _set_sys_config_value(PASTE_TABLE_KEY, value)
    return pre


def test_pasted_image_rides_shared_upload_door_and_stream_payload(
        page, collectors, api_client, uniq):
    """Screenshot bytes pasted in: ready chip + server-issued pair on /stream."""
    pid = _seed_project(api_client, uniq, "PASTEIMG")
    try:
        _open_chat(page, pid)
        with page.expect_response(
                lambda r: r.status == 200 and "/ai/chat/images" in r.url) as up_resp:
            _paste(page, b64=PNG_B64)
        view = up_resp.value.json()["data"]
        # Server-side record: the upload door is the SHARED one and minted the
        # canonical key under this project -- that (not the client body) is
        # the attachment booking evidence, same posture as the picker spectrum.
        assert view["objectKey"].split("/chat-image/")[0].rsplit("/", 1)[-1] == str(pid), \
            f"key must be minted under the issuing slice: {view['objectKey']}"
        expect(page.locator('[data-test="chat-image-item-ready"]')).to_have_count(1, timeout=15000)

        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill("只回答两个字：收到")
        body = _send_and_capture_stream(page)
        att = body.get("attachments")
        assert isinstance(att, list) and len(att) == 1 \
            and att[0]["objectKey"] == view["objectKey"] \
            and att[0]["fileName"] == view["fileName"], \
            f"pasted image must ride the stream payload as the server pair, got {att!r}"

        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\S"), timeout=10000)

        # Booking evidence for the replay ledger : the read
        # channel really opened the pasted picture -- a tp_ai_call_log row of
        # the image-read function stands for this scratch project. Same-door
        # posture as the picker spectrum, hard assertion, never conditional.
        read_rows = int(sql_query(
            "SELECT count(*) FROM tp_ai_call_log WHERE function = '对话用户图读图' "
            f"AND project_id = {pid}"))
        assert read_rows >= 1, \
            f"pasted image must book an image-read call log row, rows={read_rows}"

        assert_no_errors(collectors, "pasted image rides the shared door")
    finally:
        _purge_project(api_client, pid)
        sql_query(f"DELETE FROM tp_ai_call_log WHERE project_id = {pid}")


def test_pasted_excel_tsv_becomes_gfm_and_the_prompt_carries_the_separator_row(
        page, collectors, api_client, uniq):
    """TSV clipboard: converted at the caret AND the outbound prompt holds |---."""
    pid = _seed_project(api_client, uniq, "PASTETSV")
    try:
        _open_chat(page, pid)
        _paste(page, plain=TSV_CLIPBOARD)
        inp = page.locator('[data-test="chat-dock-input"]')
        # Golden shape (chatPasteEnhance.renderGfm): "| a | b |" header,
        # "| --- | --- |" separator, one data row per clipboard row.
        expect(inp).to_have_value(
            "| 姓名 | 分数 |\n| --- | --- |\n| 阿明 | 95 |\n| 阿红 | 80 |", timeout=5000)
        body = _send_and_capture_stream(page)
        assert "| --- | --- |" in body.get("prompt", ""), \
            f"outbound prompt must carry the GFM separator row: {body.get('prompt')!r}"
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        assert_no_errors(collectors, "excel TSV paste converts to GFM on the wire")
    finally:
        _purge_project(api_client, pid)


def test_pasted_html_tables_convert_in_document_order(page, collectors, api_client, uniq):
    """HTML clipboard with two tables: both convert, first table stays first."""
    pid = _seed_project(api_client, uniq, "PASTEHTML")
    try:
        _open_chat(page, pid)
        _paste(page, plain="环境\t地址\n\t", html=HTML_TWO_TABLES)
        inp = page.locator('[data-test="chat-dock-input"]')
        text = inp.input_value()
        # Two full-line GFM separator rows = both tables converted.
        seps = [ln for ln in text.split("\n") if re.fullmatch(r"\|( --- \|)+", ln)]
        assert len(seps) == 2, f"both tables must convert: {text!r}"
        assert text.index("| 环境 | 地址 |") < text.index("| 端口 |"), \
            f"document order must be preserved: {text!r}"
        assert "| 10.0.0.1 |" in text and "| 8081 |" in text
        assert "<table" not in text and "前置说明" in text
        assert_no_errors(collectors, "html tables convert in document order")
    finally:
        _purge_project(api_client, pid)


def test_switch_off_pastes_verbatim(page, collectors, api_client, uniq):
    """Off state = raw tabs, zero conversion (the honesty leg of the switch).

    Two legs, because a synthetic ClipboardEvent never runs the browser's
    default paste: the CONTRACT leg asserts the handler did not consume the
    event and rewrote nothing (defaultPrevented false, composer untouched);
    the TRUSTED leg drives a real clipboard write + Ctrl+V so the actual
    default-paste path lands the raw TSV with tabs intact.
    """
    pid = _seed_project(api_client, uniq, "PASTEOFF")
    pre = _pin_paste_switch("false")
    try:
        _open_chat(page, pid, wait_switch_read=True)
        inp = page.locator('[data-test="chat-dock-input"]')
        prevented = _paste(page, plain=TSV_CLIPBOARD)
        assert prevented is False, \
            "off state must leave the paste to the browser default"
        expect(inp).to_have_value("", timeout=2000)
        page.context.grant_permissions(
            ["clipboard-read", "clipboard-write"], origin=BASE_URL)
        inp.click()
        page.evaluate("async (t) => navigator.clipboard.writeText(t)",
                      TSV_CLIPBOARD)
        inp.press("Control+v")
        expect(inp).to_have_value(TSV_CLIPBOARD, timeout=5000)
        assert "| ---" not in inp.input_value()
        assert_no_errors(collectors, "switch off keeps the paste verbatim")
    finally:
        _set_sys_config_value(PASTE_TABLE_KEY, pre)
        _purge_project(api_client, pid)


def test_multiline_paste_never_sends_and_one_enter_sends_once(
        page, collectors, api_client, uniq):
    """R33 orthogonality: the paste listener never fires a send by itself."""
    pid = _seed_project(api_client, uniq, "PASTEKEY")
    try:
        _open_chat(page, pid)
        sent_before = page.locator('[data-test="chat-msg"][data-role="user"]').count()
        _paste(page, plain=TSV_CLIPBOARD)
        inp = page.locator('[data-test="chat-dock-input"]')
        expect(inp).to_have_value(re.compile(r"^\| 姓名 \| 分数 \|\n\| --- \| --- \|"), timeout=5000)
        # Multi-line content sitting in the composer changes nothing on the
        # transcript (no auto-send, no swallowed text).
        expect(page.locator('[data-test="chat-msg"][data-role="user"]')).to_have_count(
            sent_before, timeout=2000)
        body = _send_and_capture_stream(page)
        assert "| --- | --- |" in body.get("prompt", ""), "one Enter sent exactly the composed table"
        expect(page.locator('[data-test="chat-msg"][data-role="user"]')).to_have_count(
            sent_before + 1, timeout=10000)
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=180000)
        assert_no_errors(collectors, "multiline paste + Enter semantics stay orthogonal")
    finally:
        _purge_project(api_client, pid)
