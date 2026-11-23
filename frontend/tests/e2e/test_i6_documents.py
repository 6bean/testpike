
import hashlib
import os
import subprocess
import tempfile

from playwright.sync_api import expect

from conftest import ADMIN_PASSWORD, BASE_URL, assert_no_errors, inject_user_token


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def doc_page(api, page, pid):
    pass
    inject_user_token(page, "admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)


def make_dataset(api, uniq):
    pass
    r = api.post("/projects", json={"name": f"I6文档项目{uniq}", "code": f"I6D{uniq[:6]}"})
    assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def md_file(tmpdir, name, uniq, extra=""):
    pass
    content = (
        f"# 订单中心需求文档 {uniq}{extra}\n\n"
        "## 1. 背景\n\n本系统用于订单全生命周期管理，覆盖创建、支付、履约与售后。{u}{e}\n\n"
        "## 2. 支付流程\n\n### 2.1 支付渠道\n\n支持微信、支付宝与银行卡三种渠道，超时时间 15 分钟。{u}{e}\n\n"
        "### 2.2 退款规则\n\n全额退款需在支付后 7 天内发起，部分退款不限次数。{u}{e}\n\n"
        "## 3. 非功能要求\n\n接口 P99 延迟不超过 200ms，可用性 99.9%。{u}{e}\n"
    ).replace("{u}", uniq).replace("{e}", extra)
    path = os.path.join(tmpdir, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return path, content


def _open_upload_dialog(page):
    page.locator('[data-test="doc-upload-btn"]').first.click()
    dialog = page.locator('[data-test="file-upload-dialog"]')
    expect(dialog).to_be_visible(timeout=5000)
    return dialog


def _pick_file(page, path):
    dialog = _open_upload_dialog(page)
    dialog.locator('[data-test="file-upload-drop"] input[type="file"]').set_input_files(path)
    return dialog


def upload_via_ui(page, path):
    d = _pick_file(page, path)
    submit = d.locator('[data-test="file-upload-submit"]')
    expect(submit).to_be_enabled(timeout=3000)
    submit.click()


def test_i6_documents_upload_manage(page, collectors, api_client, uniq):
    pass
    pid = make_dataset(api_client, uniq)
    member_id = None
    tmpdir = tempfile.mkdtemp(prefix="i6doc-")
    try:
        doc_page(api_client, page, pid)
        expect(page.locator('[data-test="doc-upload-btn"]')).to_be_visible(timeout=10000)

        path1, content1 = md_file(tmpdir, f"I6需求文档{uniq}.md", uniq)
        upload_via_ui(page, path1)
        expect(page.locator('.el-message--success').filter(has_text="解析完成").first).to_be_visible(timeout=15000)
        row = page.locator('[data-test="doc-table"] tbody tr').filter(has_text=f"I6需求文档{uniq}").first
        expect(row).to_be_visible(timeout=10000)
        expect(row.locator('[data-test="doc-status-tag"]')).to_have_text("解析成功")
        chunks = sql(f"SELECT chunk_count FROM tp_document WHERE name = 'I6需求文档{uniq}'")
        assert int(chunks) > 0, "分块数应>0"
        expect(row).to_contain_text(chunks)
        assert ".md" not in row.inner_text(), "展示名应去扩展名"
        import re as _re
        assert _re.search(r"\d+(\.\d+)?\s?(B|KB|MB)", row.inner_text()), f"大小应人话: {row.inner_text()[:200]}"
        expect(row).to_contain_text("markdown")
        db_hash = sql(f"SELECT file_hash FROM tp_document WHERE name = 'I6需求文档{uniq}'")
        assert db_hash == hashlib.sha256(content1.encode("utf-8")).hexdigest(), "sha256 与内容不一致"

        with page.expect_response(
                lambda r: "/api/v1/ai/documents" in r.url and r.status == 409,
                timeout=15000):
            upload_via_ui(page, path1)
        err_toast = page.locator('.el-message--error').filter(has_text="已存在").first
        expect(err_toast).to_be_visible(timeout=15000)
        assert sql(f"SELECT count(*) FROM tp_document WHERE name LIKE 'I6需求文档{uniq}%'") == "1", \
            "重传不应产生第二行"
        err_inline = page.locator('[data-test="file-upload-dialog"] [data-test="file-upload-error"]')
        expect(err_inline).to_be_visible(timeout=5000)
        assert "已存在" in err_inline.inner_text(), f"行内 409 人话缺失: {err_inline.inner_text()}"
        page.keyboard.press("Escape")
        expect(page.locator('[data-test="file-upload-dialog"]')).to_be_hidden(timeout=5000)
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"] if "409" not in str(e.get("status", ""))]
        collectors["console_errors"] = [
            c for c in collectors["console_errors"] if "status of 409" not in c]

        exe_path = os.path.join(tmpdir, "恶意程序.exe")
        with open(exe_path, "wb") as f:
            f.write(b"MZ\x00\x01fake")
        dialog = _pick_file(page, exe_path)
        err = dialog.locator('[data-test="file-upload-error"]')
        expect(err).to_be_visible(timeout=5000)
        expect(err).to_contain_text("不支持")
        expect(dialog.locator('[data-test="file-upload-submit"]')).to_be_disabled()
        page.keyboard.press("Escape")
        expect(dialog).to_be_hidden(timeout=5000)
        assert sql(f"SELECT count(*) FROM tp_document WHERE name LIKE '恶意程序%'") == "0", \
            "exe 前端拦截后不应有任何留痕"

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-table"] tbody tr')
               .filter(has_text=f"I6需求文档{uniq}").first).to_be_visible(timeout=15000)

        r = api_client.post("/users", json={
            "username": f"i6mem_{uniq}", "password": "Test@12345",
            "displayName": f"I6成员{uniq}", "email": f"i6mem_{uniq}@test.local",
            "phone": "", "role": 1})
        assert r.status_code == 200 and r.json().get("code") == "0", f"建用户失败: {r.text[:300]}"
        member_id = r.json()["data"]
        r = api_client.post(f"/projects/{pid}/members", json={"userId": member_id, "role": 1})
        assert r.json().get("code") == "0", f"加成员失败: {r.text[:300]}"
        inject_user_token(page, f"i6mem_{uniq}", "Test@12345")
        page.goto(f"{BASE_URL}/#/documents?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-list-page"]')).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="doc-readonly-hint"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="doc-upload-btn"]')).to_have_count(0)
        expect(page.locator('[data-test="doc-table"] tbody tr')
               .filter(has_text=f"I6需求文档{uniq}").first).to_be_visible(timeout=10000)

        doc_page(api_client, page, pid)
        row = page.locator('[data-test="doc-table"] tbody tr').filter(has_text=f"I6需求文档{uniq}").first
        expect(row).to_be_visible(timeout=10000)
        doc_id = sql(f"SELECT id FROM tp_document WHERE name = 'I6需求文档{uniq}'")
        row.locator('[data-test="ra-delete"]').click()
        confirm_box = page.locator('.el-message-box')
        expect(confirm_box).to_be_visible(timeout=10000)
        expect(confirm_box).to_contain_text("分块")
        confirm_box.locator('button:has-text("确认删除")').click()
        expect(page.locator('.el-message--success').filter(has_text="文档已删除").first).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="doc-table"] tbody tr')
               .filter(has_text=f"I6需求文档{uniq}")).to_have_count(0, timeout=10000)

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="doc-table"] tbody tr')
               .filter(has_text=f"I6需求文档{uniq}")).to_have_count(0, timeout=10000)
        assert sql(f"SELECT count(*) FROM tp_document WHERE id = {doc_id}") == "0"
        assert sql(f"SELECT count(*) FROM tp_document_chunk WHERE document_id = {doc_id}") == "0", \
            "chunk 未随删（CASCADE 失效）"

        assert_no_errors(collectors, "F1 文档管理 上传/去重/预检/只读/删除 全链")
    finally:
        try:
            ids = sql(f"SELECT id FROM tp_document WHERE project_id = {pid}")
            for i in ids.splitlines():
                subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                                "-d", "testpike", "-tAc",
                                f"DELETE FROM tp_document_chunk WHERE document_id = {i}"],
                               capture_output=True)
            sql(f"DELETE FROM tp_document WHERE project_id = {pid}")
        except subprocess.CalledProcessError:
            pass
        if member_id:
            try:
                api_client.put(f"/users/{member_id}/roles", json={"roleIds": []})
                api_client.delete(f"/users/{member_id}")
            except Exception:
                pass
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
        assert sql(f"SELECT count(*) FROM tp_document WHERE project_id = {pid}") == "0", \
            "purge 后文档残留"
        assert sql(f"SELECT count(*) FROM tp_document_chunk c WHERE NOT EXISTS "
                   f"(SELECT 1 FROM tp_document d WHERE d.id = c.document_id)") == "0", \
            "存在孤儿 chunk（CASCADE 失效）"
