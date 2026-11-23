
import base64
import hashlib
import hmac
import json
import os
import time
import uuid

import httpx
import redis
from conftest import (
    API_BASE,
    BASE_URL,
    TESTER_PASSWORD,
    assert_no_errors,
    mk_tester,
    sql_query,
)
from playwright.sync_api import expect


RP = redis.Redis(host="localhost", port=int(os.environ.get("REDIS_PORT") or "6379"), decode_responses=True)


def _b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _expired_access(user_id: int, username: str, roles=None) -> str:
    pass
    secret = os.environ["SSO_JWT_SECRET"].encode()
    now = int(time.time())
    header = _b64u(b'{"alg":"HS256","typ":"JWT"}')
    payload = _b64u(json.dumps({
        "jti": str(uuid.uuid4()), "sub": user_id, "name": username,
        "nick": username, "dept": None, "roles": roles or ["TESTER"],
        "avatar": None, "iat": now - 120, "exp": now - 60,
    }, ensure_ascii=False).encode())
    signing = f"{header}.{payload}"
    sig = _b64u(hmac.new(secret, signing.encode(), hashlib.sha256).digest())
    return f"{signing}.{sig}"


def _login(username: str, password: str):
    r = httpx.post(f"{API_BASE}/auth/login",
                   json={"username": username, "password": password}, timeout=10)
    assert r.status_code == 200, f"登录失败: {r.status_code} {r.text[:200]}"
    d = r.json()["data"]
    return d["accessToken"], d["refreshToken"]


def _inject(page, access: str, refresh: str | None = None):
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    items = {"tp_access_token": access, "tp_token_type": "Bearer"}
    if refresh:
        items["tp_refresh_token"] = refresh
    page.evaluate(
        "(items) => { for (const [k, v] of Object.entries(items)) localStorage.setItem(k, v) }",
        items)


def _api_token(page) -> tuple:
    return (page.evaluate("() => localStorage.getItem('tp_access_token')"),
            page.evaluate("() => localStorage.getItem('tp_refresh_token')"))


def _purge_sessions(user_id: int):
    pass
    idx = f"auth:user-sessions:{user_id}"
    for k in RP.smembers(idx):
        RP.delete(k)
    RP.delete(idx)
    for k in RP.scan_iter("auth:refresh:*"):
        raw = RP.get(k) or ""
        try:
            if json.loads(raw).get("userId") == user_id:
                RP.delete(k)
        except ValueError:
            RP.delete(k)


def _drop_401(collectors):
    pass
    collectors["network_4xx"] = [e for e in collectors["network_4xx"] if e["status"] != 401]
    collectors["console_errors"] = [
        e for e in collectors["console_errors"] if "status of 401" not in e
    ]


def _cleanup(api, username, access, refresh):
    if access:
        httpx.post(f"{API_BASE}/auth/logout", json={"refreshToken": refresh},
                   headers={"Authorization": f"Bearer {access}"}, timeout=10)
    r = api.get(f"/users?keyword={username}&size=20")
    for x in r.json().get("data", {}).get("list", []):
        if x.get("username") == username:
            _purge_sessions(x["id"])
            sql_query(f"DELETE FROM tp_notification WHERE user_id = {x['id']}")
            api.delete(f"/users/{x['id']}")


def test_r2_logout_revokes_both_tokens(page, collectors, api_client, uniq):
    pass
    _, _ = mk_tester(api_client, uniq, prefix="r2")
    username = f"r2tester_{uniq}"
    at2 = rt2 = None
    try:
        at, rt = _login(username, TESTER_PASSWORD)

        assert httpx.get(f"{API_BASE}/users/me", headers={"Authorization": f"Bearer {at}"}).status_code == 200

        _inject(page, at, rt)
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)

        page.locator(".user-info").click()
        page.locator('.el-dropdown-menu__item:has-text("退出登录")').click()
        page.locator('.el-message-box__btns button:has-text("退出")').click()
        expect(page.locator('[data-test="login-submit"]')).to_be_visible(timeout=10000)

        r_old = httpx.get(f"{API_BASE}/users/me", headers={"Authorization": f"Bearer {at}"})
        assert r_old.status_code == 401, f"旧 access 登出后应 401, got {r_old.status_code}"
        assert "失效" in (r_old.json().get("msg") or ""), f"应人话说明会话失效: {r_old.text[:120]}"

        r_rf = httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt})
        assert r_rf.status_code == 401, f"旧 refresh 登出后应 401, got {r_rf.status_code}"

        assert page.evaluate("() => localStorage.getItem('tp_refresh_token')") is None
        assert page.evaluate("() => localStorage.getItem('tp_access_token')") is None

        at2, rt2 = _login(username, TESTER_PASSWORD)
        assert at2 != at and rt2 != rt
        assert httpx.get(f"{API_BASE}/users/me", headers={"Authorization": f"Bearer {at2}"}).status_code == 200

        _drop_401(collectors)
        assert_no_errors(collectors, "TC-R2-01/R6-02 登出双吊销")
    finally:
        _cleanup(api_client, username, at2, rt2)


def test_r6_silent_refresh_single_flight(page, collectors, api_client, uniq):
    pass
    uid, _ = mk_tester(api_client, uniq, prefix="r6a")
    username = f"r6atester_{uniq}"
    _, rt = _login(username, TESTER_PASSWORD)
    at_new = rt_new = None
    try:
        _inject(page, _expired_access(uid, username), rt)
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")

        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        assert "#/dashboard" in page.url, f"无感续期不应跳登录, url={page.url}"

        refresh_reqs = [rq for rq in collectors["requests"] if "/auth/refresh" in rq["url"]]
        assert len(refresh_reqs) == 1, f"单飞刷新应仅 1 条 refresh 请求, got {len(refresh_reqs)}"

        expired_at = _expired_access(uid, username)
        at_new, rt_new = _api_token(page)
        assert at_new and at_new != expired_at, "access 应已被无感续期替换"
        assert rt_new and rt_new != rt, "refresh 应已轮换"
        assert httpx.get(f"{API_BASE}/users/me", headers={"Authorization": f"Bearer {at_new}"}).status_code == 200
        assert httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt}).status_code == 401

        _drop_401(collectors)
        assert_no_errors(collectors, "TC-R6-01 无感续期单飞")
    finally:
        _cleanup(api_client, username, at_new, rt_new)


def test_r6_replay_revokes_all_sessions(page, collectors, api_client, uniq):
    pass
    uid, _ = mk_tester(api_client, uniq, prefix="r6b")
    username = f"r6btester_{uniq}"
    other_uname = f"r6otester_{uniq}"
    mk_tester(api_client, uniq, prefix="r6o")
    tok_new = rt_new = None
    try:
        _, rt_a = _login(username, TESTER_PASSWORD)
        _, rt_b = _login(username, TESTER_PASSWORD)
        _, rt_other = _login(other_uname, TESTER_PASSWORD)

        r = httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt_a}, timeout=10)
        assert r.status_code == 200, f"A 首次刷新应成功: {r.text[:150]}"
        rt_a2 = r.json()["data"]["refreshToken"]

        time.sleep(0.25)
        r_re = httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt_a}, timeout=10)
        assert r_re.status_code == 401
        assert "全部" in (r_re.json().get("msg") or ""), f"重放应人话说明吊销全部: {r_re.text[:150]}"

        assert httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt_a2}).status_code == 401
        assert httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt_b}).status_code == 401
        assert httpx.post(f"{API_BASE}/auth/refresh", json={"refreshToken": rt_other}).status_code == 200

        tok_new, rt_new = _login(username, TESTER_PASSWORD)
        nt = httpx.get(f"{API_BASE}/notifications?eventType=LOGIN_SESSION_REVOKE&size=10",
                       headers={"Authorization": f"Bearer {tok_new}"}, timeout=10).json()
        items = (nt.get("data") or {}).get("list") or []
        assert len(items) >= 1 and "会话" in (items[0].get("title") or items[0].get("content") or ""), \
            f"应收到会话吊销通知: {str(nt)[:200]}"

        _inject(page, _expired_access(uid, username), rt_a2)
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page).to_have_url(f"{BASE_URL}/#/login", timeout=15000)
        expect(page.locator('[data-test="login-submit"]')).to_be_visible(timeout=15000)

        _drop_401(collectors)
        assert_no_errors(collectors, "TC-R6-03 重放吊销全部")
    finally:
        _cleanup(api_client, username, tok_new, rt_new)
        _cleanup(api_client, other_uname, None, None)
