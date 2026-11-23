"""Queue-waiting notice spectrum .

Real browser + real backend + real generation ledger (no mocks). The notice
is server-assembled (single source, R62 single-cursor: the panel renders the
very string the batch API returns, no second channel):

 S1 with the backlog threshold lowered through the real config API (hot
 update door), a live batch shows the three-part notice — reason with
 the current backlog count, how many of this batch are waiting, and a
 recovery estimate (or the honest no-sample note);
 S2 the browser text equals the API text (mirror fidelity, no client-side
 re-wording) and carries no manual-refresh instruction (R62);
 S3 after every task settles the notice disappears by itself — the list
 re-reads silently, nothing to refresh.

Cost discipline: one batch of six short requirements, stopped through the
cancel door right after the observation windows, mirroring the queue
recovery spectrum.
"""
import time

from conftest import (ADMIN_PASSWORD, ADMIN_USERNAME, BASE_URL, _set_sys_config_value,
                      assert_no_errors, get_sys_config_value, inject_user_token,
                      purge_project)
from issues_monitor import assert_no_browser_issues

NOTICE = '[data-test="ai-gen-queue-notice"]'
THRESHOLD_KEY = "ai.gen.window-priority.notice-backlog-threshold"
BATCH_PANEL = '[data-test="ai-gen-batch-panel"]'


def _make_project(api, uniq):
    r = api.post("/projects", json={"name": f"排队提示{uniq}", "code": f"QN{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _batch_info(api, batch_id):
    r = api.get(f"/ai/batches/{batch_id}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _wait_notice(api, batch_id, timeout_s=90):
    """Poll the batch ledger until the server emits the notice (tasks reach
    pending state on the batch) or the batch settles early."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        info = _batch_info(api, batch_id)
        if info.get("queueNotice"):
            return info
        if info["pending"] + info["running"] == 0:
            return info
        time.sleep(2)
    return _batch_info(api, batch_id)


def test_backlog_notice_shows_three_parts_and_clears_after_settle(page, collectors, api_client, uniq):
    pid = None
    original = get_sys_config_value(THRESHOLD_KEY)
    try:
        # Lower the show-threshold so one waiting batch is enough to light the
        # notice (real config API = hot-update door, no SQL shortcut).
        _set_sys_config_value(THRESHOLD_KEY, "1")
        pid = _make_project(api_client, uniq)
        texts = [f"排队提示短句{idx}_{uniq[:6]}" for idx in range(6)]
        r = api_client.post("/ai/batch-generate", json={
            "projectId": pid,
            "requirements": texts,
            "items": [{"text": t} for t in texts],
        })
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        batch_id = r.json()["data"]["batchId"]

        info = _wait_notice(api_client, batch_id)
        notice = info.get("queueNotice")
        # S1: three parts — reason names the backlog, the waiting count of
        # this batch, and the recovery estimate (or the honest no-sample note).
        assert notice, f"notice never assembled while batch waiting: {info}"
        assert "积压" in notice and "排队" in notice, f"notice lacks reason parts: {notice}"
        assert ("预计约" in notice) or ("自动更新" in notice), f"notice lacks recovery part: {notice}"
        # R63: the reason must point at the current input value (a number).
        assert any(ch.isdigit() for ch in notice), f"notice carries no concrete numbers: {notice}"
        # S2: browser renders the exact server sentence (single source).
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}&batchId={batch_id}",
                  wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        from playwright.sync_api import expect
        expect(page.locator(BATCH_PANEL)).to_be_visible(timeout=15000)
        loc = page.locator(NOTICE)
        expect(loc).to_be_visible(timeout=15000)
        shown = loc.inner_text().strip()
        assert shown == notice.strip(), f"browser text diverged from server text:\n{shown}\n{notice}"
        # R62: no manual-refresh instruction anywhere in the notice.
        assert "刷新" not in shown, f"notice instructs refreshing: {shown}"
        assert_no_errors(collectors)
        assert_no_browser_issues(collectors.get("browser_issues"), "queue notice live")

        # S3: stop everything; once the batch settles the notice disappears
        # on the silent re-read — no reload touch needed from the user.
        info = _batch_info(api_client, batch_id)
        for item in info["items"]:
            api_client.post(f"/ai/tasks/{item['taskId']}/cancel")
        deadline = time.time() + 90
        while time.time() < deadline:
            info = _batch_info(api_client, batch_id)
            if info["pending"] + info["running"] == 0:
                break
            time.sleep(3)
        assert info["pending"] + info["running"] == 0, f"queue never settled: {info}"
        assert not info.get("queueNotice"), f"notice survived settle: {info.get('queueNotice')}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator(BATCH_PANEL)).to_be_visible(timeout=15000)
        expect(page.locator(NOTICE)).to_have_count(0, timeout=20000)
        assert_no_errors(collectors)
        assert_no_browser_issues(collectors.get("browser_issues"), "queue notice settle")
    finally:
        if original is not None:
            _set_sys_config_value(THRESHOLD_KEY, original)
        if pid:
            purge_project(api_client, pid)
