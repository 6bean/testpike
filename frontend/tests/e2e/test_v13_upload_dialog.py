# -*- coding: utf-8 -*-

import os

import pytest
from playwright.sync_api import expect

BASE = "http://localhost:5173"

ENTRIES = [
    ("/documents", '[data-test="doc-upload-btn"]'),
    ("/skill-market", '[data-test="market-import-open"]'),
    ("/tool-market", '[data-test="tool-market-import-open"]'),
    ("/plugins", '[data-test="plugin-upload-open"]'),
]


def _mk_project(api_client, uniq, tag="V13"):
    pass
    r = api_client.post("/projects", json={
        "name": f"{tag}上传项目{uniq}", "code": f"{tag}{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _cleanup_project(api_client, pid):
    try:
        api_client.delete(f"/projects/{pid}")
    except Exception:
        pass


def _open_dialog(page, route, trigger, pid=None):
    url = f"{BASE}/#{route}"
    if pid is not None and route == "/documents":
        url = f"{url}?projectId={pid}"
    page.goto(url, wait_until="domcontentloaded")
    btn = page.locator(trigger).first
    expect(btn).to_be_visible(timeout=10000)
    btn.click()
    dialog = page.locator('[data-test="file-upload-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    return dialog


def _pick(page, dialog, path):
    dialog.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(str(path))


def test_v13_01_four_entries_same_component(logged_in, api_client, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    hints = []
    for route, trigger in ENTRIES:
        dialog = _open_dialog(page, route, trigger, pid=pid)
        expect(dialog.locator('[data-test="file-upload-drop"]')).to_be_visible()
        expect(dialog.locator('[data-test="file-upload-submit"]')).to_be_disabled()
        hint = dialog.locator('[data-test="file-upload-drop"] .el-upload__text').inner_text()
        hints.append((route, hint.strip()))
        page.keyboard.press("Escape")
        expect(dialog).to_be_hidden(timeout=5000)
    _cleanup_project(api_client, pid)
    texts = {r: h for r, h in hints}
    distinct = set(texts.values())
    assert len(distinct) == 1, f"四入口拖拽提示文案不一致（应为公共件 locale 单源）：{texts}"


def test_v13_02_format_reject_message_consistent(logged_in, api_client, tmp_path, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    bad = tmp_path / "payload.txt"
    bad.write_text("not an accepted upload", encoding="utf-8")
    msgs = {}
    for route, trigger in [ENTRIES[0], ENTRIES[1]]:
        dialog = _open_dialog(page, route, trigger, pid=pid)
        _pick(page, dialog, bad)
        err = dialog.locator('[data-test="file-upload-error"]')
        expect(err).to_be_visible(timeout=3000)
        msgs[route] = err.inner_text().strip()
        page.keyboard.press("Escape")
    _cleanup_project(api_client, pid)
    assert msgs[ENTRIES[0][0]] == msgs[ENTRIES[1][0]], \
        f"格式拒绝文案不一致（应同模板=公共件单源）：{msgs}"
    assert "txt" in msgs[ENTRIES[0][0]].lower() or "格式" in msgs[ENTRIES[0][0]], \
        f"格式拒绝文案未含人话要素（扩展名/格式）：{msgs}"


def test_v13_03_oversize_human_message(logged_in, api_client, tmp_path, uniq):
    pass
    page = logged_in
    pid = _mk_project(api_client, uniq)
    big = tmp_path / "huge.md"
    with open(big, "wb") as f:
        os.truncate(f.fileno(), 61 * 1024 * 1024)
    dialog = _open_dialog(page, ENTRIES[0][0], ENTRIES[0][1], pid=pid)
    _pick(page, dialog, big)
    err = dialog.locator('[data-test="file-upload-error"]')
    expect(err).to_be_visible(timeout=3000)
    text = err.inner_text()
    assert "50" in text, f"超限人话提示未含上限数值：{text}"
    page.keyboard.press("Escape")
    _cleanup_project(api_client, pid)


def test_v13_04_upload_progress_and_success(logged_in, api_client, tmp_path, uniq):
    pass
    page = logged_in
    # layout sweep removed the page-level project picker: enter the page
    # the same way as the sibling cases here — a ?projectId= deep link (the
    # global header context is the single source and enables the upload button).
    pid = _mk_project(api_client, uniq, tag="V13b")
    page.goto(f"{BASE}/#/documents?projectId={pid}", wait_until="domcontentloaded")
    upload_btn = page.locator('[data-test="doc-upload-btn"]')
    expect(upload_btn).to_be_visible(timeout=10000)
    expect(upload_btn).to_be_enabled(timeout=10000)
    md = tmp_path / "v13_upload_probe.md"
    import uuid as _uuid
    token = _uuid.uuid4().hex[:12]
    md.write_text(f"# V13 统一上传件探针\n\nTC-V13-01 契约验证文档 {token}。\n", encoding="utf-8")
    dialog = _open_dialog(page, ENTRIES[0][0], ENTRIES[0][1], pid=pid)
    _pick(page, dialog, md)
    submit = dialog.locator('[data-test="file-upload-submit"]')
    expect(submit).to_be_enabled(timeout=3000)
    submit.click()
    progress = dialog.locator('[data-test="file-upload-progress"]')
    closed_or_progress = page.wait_for_function(
        """() => {
            const p = document.querySelector('[data-test="file-upload-dialog"] [data-test=\\"file-upload-progress\\"]');
            const d = document.querySelector('[data-test="file-upload-dialog"]');
            const gone = !d || getComputedStyle(d).display === 'none';
            return !!p || gone;
        }""", timeout=15000)
    assert closed_or_progress, "上传后既无进度区挂载、对话框也未关闭（进度契约缺失）"
    expect(dialog).to_be_hidden(timeout=30000)
    row = page.locator('.el-table__row', has_text="v13_upload_probe").first
    expect(row).to_be_visible(timeout=15000)
