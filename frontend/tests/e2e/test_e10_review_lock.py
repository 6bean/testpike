
import os
import subprocess

import pytest
from playwright.sync_api import expect

from conftest import (BASE_URL, add_member, assert_no_errors, attach_monitors,
                      inject_user_token, mk_tester)


PWD = "Test@12345"
LOCK_WAIT_MS = 75_000


REDIS_PORT = os.environ.get("REDIS_PORT") or "6379"


def redis_cli(*args) -> str:
    pass
    r = subprocess.run(["redis-cli", "-h", "localhost", "-p", REDIS_PORT, *args],
                       capture_output=True, text=True, timeout=10)
    assert r.returncode == 0, f"redis-cli 失败: {args} -> {r.stderr[:200]}"
    return r.stdout.strip()


def lock_key(case_id: int) -> str:
    return f"lock:review:case:{case_id}"


def whitelist_expected_409(collectors, page):
    pass
    collectors["network_4xx"] = [
        e for e in collectors["network_4xx"]
        if not (e["status"] == 409 and e["url"].endswith("/review-lock"))
    ]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 409" not in e
    ]


def test_e10_review_lock_dual_browser(logged_in, collectors, api_client, browser, uniq):
    pass
    page_a = logged_in

    r = api_client.post("/projects", json={"name": f"E10锁项目_{uniq}", "code": f"E10U{uniq}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    pid = r.json()["data"]
    r = api_client.post("/cases", json={
        "projectId": pid, "title": f"E10评审锁用例_{uniq}",
        "precondition": "前置：双浏览器会话", "expectedResult": "预期：互斥生效",
        "priority": 0, "confidence": 1, "endType": "web", "status": 1,
        "steps": [{"stepNo": 1, "description": "打开详情页", "isAssert": 0}],
    })
    assert r.status_code == 200 and r.json().get("code") == "0", f"建用例失败: {r.text[:300]}"
    case = r.json()["data"]
    cid = case["id"]
    b_id, _b_nick = mk_tester(api_client, uniq, prefix="e10")
    admin_id = api_client.get("/users/me").json()["data"]["user"]["userId"]
    add_member(api_client, pid, b_id)
    r = api_client.post(f"/cases/{cid}/assign", json={"assigneeId": b_id})
    assert r.status_code == 200 and r.json().get("code") == "0", f"分配失败: {r.text[:300]}"

    redis_cli("DEL", lock_key(cid))
    assert redis_cli("EXISTS", lock_key(cid)) == "0"

    ctx_b = browser.new_context(viewport={"width": 1600, "height": 900})
    page_b = ctx_b.new_page()
    coll_b = attach_monitors(page_b)
    detail_url = f"{BASE_URL}/#/cases/{cid}"

    try:
        page_a.goto(detail_url, wait_until="domcontentloaded")
        expect(page_a.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
        with page_a.expect_response(
            lambda r: r.request.method == "POST" and r.url.endswith("/review-lock"),
            timeout=15000,
        ) as acq_info:
            page_a.locator('[data-test="detail-edit"]').click()
        assert acq_info.value.status == 200, "A 取锁应 200"
        expect(page_a.locator('[data-test="review-lock-holding"]')).to_be_visible(timeout=5000)
        assert redis_cli("HEXISTS", lock_key(cid), str(admin_id)) == "1", "Redis 锁键应含 admin 属主"

        inject_user_token(page_b, f"e10tester_{uniq}", PWD)
        page_b.goto(detail_url, wait_until="domcontentloaded")
        expect(page_b.locator('[data-test="case-detail-root"]')).to_be_visible(timeout=10000)
        with page_b.expect_response(
            lambda r: r.request.method == "POST" and r.url.endswith("/review-lock"),
            timeout=15000,
        ) as b409_info:
            page_b.locator('[data-test="detail-edit"]').click()
        assert b409_info.value.status == 409, f"B 取锁应 409，实际 {b409_info.value.status}"
        banner = page_b.locator('[data-test="review-lock-banner"]')
        expect(banner).to_be_visible(timeout=5000)
        expect(banner).to_contain_text("系统管理员")
        expect(page_b.locator('[data-test="detail-edit"]')).to_be_disabled()
        expect(page_b.locator('[data-test="detail-review-pass"]')).to_be_disabled()
        expect(page_b.locator('[data-test="detail-review-reject"]')).to_be_disabled()

        ttl_before = int(redis_cli("PTTL", lock_key(cid)))
        with page_a.expect_response(
            lambda r: r.request.method == "PUT" and "/review-lock/heartbeat" in r.url,
            timeout=LOCK_WAIT_MS,
        ) as hb_info:
            pass
        hb = hb_info.value
        assert hb.status == 200, f"A 心跳应 200，实际 {hb.status}"
        ttl_after = int(redis_cli("PTTL", lock_key(cid)))
        assert ttl_after > 1_790_000, f"心跳后租期应回升接近 30min 满值，实际 {ttl_after}ms"
        assert ttl_after > ttl_before, f"租期未回升（{ttl_before} -> {ttl_after}）= 心跳未续租"

        with page_b.expect_response(
            lambda r: r.request.method == "POST" and r.url.endswith("/review-lock"),
            timeout=15000,
        ) as retry_info:
            page_b.locator('[data-test="review-lock-retry"]').click()
        assert retry_info.value.status == 409, "心跳续期后 B 重试应仍 409"
        expect(banner).to_be_visible()

        with page_a.expect_response(
            lambda r: r.request.method == "DELETE" and r.url.endswith("/review-lock"),
            timeout=15000,
        ) as rel_info:
            page_a.locator('[data-test="detail-back"]').click()
        assert rel_info.value.status == 204, f"A 离开应释放 204，实际 {rel_info.value.status}"
        assert redis_cli("EXISTS", lock_key(cid)) == "0", "释放后 Redis 锁键应消失"

        with page_b.expect_response(
            lambda r: r.request.method == "POST" and r.url.endswith("/review-lock"),
            timeout=15000,
        ) as take_info:
            page_b.locator('[data-test="review-lock-retry"]').click()
        assert take_info.value.status == 200, "B 接管取锁应 200"
        expect(page_b.locator('[data-test="review-lock-banner"]')).to_be_hidden(timeout=5000)
        expect(page_b.locator('[data-test="detail-edit-title"]')).to_be_visible(timeout=5000)
        expect(page_b.locator('[data-test="review-lock-holding"]')).to_be_visible(timeout=5000)
        assert redis_cli("HEXISTS", lock_key(cid), str(b_id)) == "1", "接管后属主应为 B userId"

        with page_b.expect_response(
            lambda r: r.request.method == "DELETE" and r.url.endswith("/review-lock"),
            timeout=15000,
        ) as brel_info:
            page_b.get_by_role("button", name="取消", exact=True).click()
        assert brel_info.value.status == 204, "B 取消编辑应释放 204"
        assert redis_cli("EXISTS", lock_key(cid)) == "0"

        whitelist_expected_409(collectors, page_a)
        whitelist_expected_409(coll_b, page_b)
        assert_no_errors(collectors, "TC-E10-02 双浏览器 A 页")
        assert_no_errors(coll_b, "TC-E10-02 双浏览器 B 页")
    finally:
        ctx_b.close()
        api_client.delete(f"/projects/{pid}/purge?force=true")
        api_client.delete(f"/users/{b_id}")
