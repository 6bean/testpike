# -*- coding: utf-8 -*-

import uuid

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors

PNG_BYTES = b"\x89PNG\r\n\x1a\n" + bytes(range(128))


def test_v14_case_host_comment_persist(logged_in, collectors, api_client):
    pass
    page = logged_in
    suffix = uuid.uuid4().hex[:8]
    pid = api_client.post("/projects", json={
        "name": f"T240E2E项目{suffix}", "code": f"T240E2EP{suffix}"}).json()["data"]
    case_id = api_client.post("/cases", json={
        "projectId": pid, "title": f"评论宿主{suffix}", "precondition": "无",
        "expectedResult": "ok", "priority": 1, "confidence": 1,
        "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "s", "isAssert": 0}]}).json()["data"]["id"]
    try:
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        panel = page.locator('[data-test="comment-panel"]')
        expect(panel).to_be_visible(timeout=15000)

        submit = panel.locator('[data-test="comment-submit"]')
        expect(submit).to_be_disabled()

        text = f"E2E评论-{suffix}"
        panel.locator('[data-test="comment-input"]').fill(text)
        expect(submit).to_be_enabled()
        submit.click()
        expect(panel.locator('[data-test="comment-item"]').filter(has_text=text)).to_be_visible(timeout=10000)

        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="comment-item"]').filter(has_text=text)).to_be_visible(timeout=15000)
        got = api_client.get(f"/comments?hostType=case&hostId={case_id}").json()["data"]
        assert any(text == g["content"] for g in got), "评论未落库（前端回显假绿）"

        panel.locator('input[type="file"]').set_input_files(files=[
            {"name": "shot.png", "mimeType": "image/png", "buffer": PNG_BYTES}])
        img_text = f"带图评论-{suffix}"
        panel.locator('[data-test="comment-input"]').fill(img_text)
        submit.click()
        expect(page.locator('[data-test="comment-item"]').filter(has_text=img_text)).to_be_visible(timeout=10000)
        got2 = api_client.get(f"/comments?hostType=case&hostId={case_id}").json()["data"]
        img_cid = next(g["id"] for g in got2 if g["content"] == img_text)
        atts = api_client.get(f"/comments/{img_cid}/attachments").json()["data"]
        assert len(atts) == 1 and atts[0]["fileName"] == "shot.png", f"附件未随评论上传：{atts}"
        item = page.locator('[data-test="comment-item"]').filter(has_text=img_text)
        item.locator('[data-test="comment-attach-toggle"]').click()
        expect(item.locator('[data-test="comment-attachment-row"]')).to_be_visible(timeout=5000)
        grant = api_client.get(f"/comments/attachments/{atts[0]['id']}/download").json()["data"]
        import httpx
        dl = httpx.get(grant["url"], timeout=30)
        assert dl.status_code == 200 and dl.content.startswith(b"\x89PNG"), f"grant 下载失败：{dl.status_code}"

        assert_no_errors(collectors, "T240 评论面板 E2E")
    finally:
        api_client.delete(f"/projects/{pid}")
        api_client.delete(f"/projects/{pid}/purge?force=true")
