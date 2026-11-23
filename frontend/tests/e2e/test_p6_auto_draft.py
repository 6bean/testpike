
import json
import time

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, nav_to


DRAFT_KEY_PREFIX = "tp-auto-draft:case:edit:"


def _expect_toast(page, text):
    pass
    expect(page.locator(".el-message").filter(has_text=text).last).to_be_visible(timeout=8000)


def _now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"P6项目_{uniq}", "code": f"P6T{uniq[:6].upper()}"})
    assert r.json().get("code") == "0", f"创建项目失败: {r.text[:200]}"
    return r.json()["data"], f"P6项目_{uniq}"


def _mk_case(api, pid, title, priority=1):
    r = api.post("/cases", json={
        "projectId": pid, "title": title, "priority": priority, "confidence": 2, "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0}],
    })
    assert r.json().get("code") == "0", f"创建用例失败: {r.text[:200]}"
    return r.json()["data"]


def _seed_draft(page, case_id, data):
    pass
    page.evaluate(
        "(payload) => localStorage.setItem(payload.key, JSON.stringify({data: payload.data, savedAt: payload.savedAt}))",
        {"key": f"{DRAFT_KEY_PREFIX}{case_id}", "data": data, "savedAt": _now_iso()},
    )


def _read_draft(page, case_id):
    return page.evaluate(
        "k => localStorage.getItem(k)", f"{DRAFT_KEY_PREFIX}{case_id}")


def test_p6_crash_recover_save_clear(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"自动草稿_{uniq}")
    draft_key = f"{DRAFT_KEY_PREFIX}{case['id']}"

    try:
        page.goto(f"{BASE_URL}/#/cases/{case['id']}", wait_until="domcontentloaded")
        edit_btn = page.locator('[data-test="detail-edit"]')
        expect(edit_btn).to_be_visible(timeout=10000)
        edit_btn.click()
        title_input = page.locator('[data-test="detail-edit-title"]')
        new_title = f"崩溃前编辑标题_{uniq}"
        title_input.fill(new_title)

        deadline = time.time() + 14
        raw = None
        while time.time() < deadline:
            raw = _read_draft(page, case["id"])
            if raw:
                break
            page.wait_for_timeout(500)
        assert raw, "编辑变更后 8s 防抖未自动落 localStorage 草稿"
        rec = json.loads(raw)
        assert rec["data"]["title"] == new_title, f"草稿内容不符: {rec['data']}"

        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-edit"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="detail-edit"]').click()
        bar = page.locator('[data-test="draft-restore-bar"]')
        expect(bar).to_be_visible(timeout=5000)
        expect(bar).to_contain_text("检测到未保存草稿")

        page.locator('[data-test="draft-restore-btn"]').click()
        expect(title_input).to_have_value(new_title, timeout=5000)
        _expect_toast(page, "草稿已恢复到表单")
        assert _read_draft(page, case["id"]) is None

        page.locator('[data-test="detail-save-draft"]').click()
        _expect_toast(page, "草稿已保存")
        assert _read_draft(page, case["id"]) is None, "提交成功后草稿未清除"
        r = api_client.get(f"/cases/{case['id']}")
        assert r.json()["data"]["title"] == new_title, "标题未真实落库"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-edit"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="detail-edit"]').click()
        expect(page.locator('[data-test="draft-restore-bar"]')).to_have_count(0)

        assert_no_errors(collectors, "T107 崩溃恢复主链")
    finally:
        page.evaluate("k => localStorage.removeItem(k)", draft_key)
        rr = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert rr.status_code in (200, 204) or rr.json().get("code") == "0", f"清理失败: {rr.text[:200]}"


def test_p6_discard_and_conflict_409(logged_in, api_client, collectors, uniq):
    pass
    page = logged_in
    pid, _pname = _mk_project(api_client, uniq)
    case = _mk_case(api_client, pid, f"草稿丢弃_{uniq}")
    draft_key = f"{DRAFT_KEY_PREFIX}{case['id']}"

    try:
        stale_title = f"待丢弃草稿_{uniq}"
        _seed_draft(page, case["id"], {"title": stale_title, "steps": case.get("steps", [])})
        page.goto(f"{BASE_URL}/#/cases/{case['id']}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="detail-edit"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="detail-edit"]').click()
        bar = page.locator('[data-test="draft-restore-bar"]')
        expect(bar).to_be_visible(timeout=5000)
        page.locator('[data-test="draft-discard-btn"]').click()
        expect(bar).to_have_count(0)
        expect(page.locator('[data-test="detail-edit-title"]')).to_have_value(case["title"])
        assert _read_draft(page, case["id"]) is None, "丢弃后草稿键未删除"

        page.locator('.edit-actions button:has-text("取消")').click()
        r = api_client.put(f"/cases/{case['id']}", json={
            "title": f"他人抢先修改_{uniq}", "version": case["version"],
        })
        assert r.json().get("code") == "0", f"他端更新失败: {r.text[:200]}"
        my_draft = f"我的草稿内容_{uniq}"
        _seed_draft(page, case["id"], {"title": my_draft, "steps": []})
        page.locator('[data-test="detail-edit"]').click()
        expect(page.locator('[data-test="draft-restore-bar"]')).to_be_visible(timeout=5000)
        page.locator('[data-test="draft-restore-btn"]').click()
        expect(page.locator('[data-test="detail-edit-title"]')).to_have_value(my_draft, timeout=5000)
        page.locator('[data-test="detail-save-draft"]').click()
        _expect_toast(page, "版本冲突")
        expect(page.locator('[data-test="detail-edit-title"]')).to_have_value(my_draft, timeout=5000)

        conflict_url = f"{BASE_URL}/api/v1/cases/{case['id']}"
        collectors["network_4xx"] = [
            e for e in collectors["network_4xx"]
            if not (e["url"] == conflict_url and e["status"] == 409)
        ]
        collectors["console_errors"] = [
            e for e in collectors["console_errors"] if "status of 409" not in e
        ]

        assert_no_errors(collectors, "T107 丢弃+409 冲突")
    finally:
        page.evaluate("k => localStorage.removeItem(k)", draft_key)
        rr = api_client.delete(f"/projects/{pid}/purge?force=true")
        assert rr.status_code in (200, 204) or rr.json().get("code") == "0", f"清理失败: {rr.text[:200]}"
