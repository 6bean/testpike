"""AI generation queue recovery spectrum .

Real browser + real backend + real generation ledger (no mocks):

 S1 submit a batch through the API (one item linked to a requirement point),
 leave the page, come back with only ?projectId — the queue panel is
 re-attached from the server ledger (items + progress visible again,
 handles ride the URL as ?batchId=);
 S2 the requirement management page carries the live "case generating"
 badge for the linked point; the badge click lands on the queue;
 S3 stopping the tasks settles the batch and the badge silently disappears
 (no toast, no manual refresh — R62 settled-seal + F16);
 S4 both columns fill the content band (single-source token) and flow
 internally: outer page never scrolls, the rows region scrolls inside.

Cost discipline: the batch is submitted with two short requirements and
stopped through the cancel door right after the observation windows, so at
most a couple of brief LLM runs are burned; settled rows reject the stop
honestly and the assertions tolerate the done-before-stop branch.
"""
import re
import time

from conftest import (ADMIN_PASSWORD, ADMIN_USERNAME, BASE_URL, assert_no_errors,
                      inject_user_token, purge_project)
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect


def _no_issues(page_unused, collectors, name):
    assert_no_browser_issues(collectors.get("browser_issues"), name)

BATCH_ITEM = '[data-test="ai-gen-batch-items"] .batch-item'


def _make_project(api, uniq):
    r = api.post("/projects", json={"name": f"队列找回{uniq}", "code": f"RC{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    data = r.json()["data"]
    return data["id"] if isinstance(data, dict) else data


def _mk_requirement(api, pid, title):
    r = api.post(f"/projects/{pid}/requirements", json={"source": 3, "title": title})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _open_generate(page, pid, extra_query=""):
    inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/ai/generate?projectId={pid}{extra_query}",
              wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="ai-gen-form"]')).to_be_visible(timeout=15000)


def _batch_tasks(api, batch_id):
    r = api.get(f"/ai/batches/{batch_id}")
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
    return r.json()["data"]


def _cancel_quietly(api, task_id):
    """Stop door on a live row; an already-settled row honestly refuses —
    both branches are legal here (LLM may finish before the stop lands)."""
    api.post(f"/ai/tasks/{task_id}/cancel")


def test_leave_and_return_restores_batch_queue_and_progress(page, collectors, api_client, uniq):
    """S1+S4: queue and progress survive a page leave; band fills, inner flows."""
    pid = None
    try:
        pid = _make_project(api_client, uniq)
        req = _mk_requirement(api_client, pid, f"找回队列需求点_{uniq}")
        rid = req["id"]
        t1 = "登录失败提示剩余重试次数"
        t2 = "订单超时自动取消"
        r = api_client.post("/ai/batch-generate", json={
            "projectId": pid,
            "requirements": [t1, t2],
            "items": [{"text": t1, "requirementId": rid}, {"text": t2}],
        })
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        batch_id = r.json()["data"]["batchId"]

        # --- first entry: the recovery door must find the live batch with no
        # submit-time handle at all (exactly the leave-and-return journey).
        _open_generate(page, pid)
        panel = page.locator('[data-test="ai-gen-batch-panel"]')
        expect(panel).to_be_visible(timeout=15000)
        expect(page.locator(BATCH_ITEM)).to_have_count(2, timeout=15000)
        expect(page.locator('[data-test="ai-gen-batch-progress-count"]')).to_contain_text("2", timeout=15000)
        page.wait_for_url(re.compile(r"batchId=" + str(batch_id)), timeout=15000)

        # --- S4 geometry: columns fill the content band, page itself never scrolls.
        geo = page.evaluate("""() => {
          const band = window.innerHeight - 80;
          const row = document.querySelector('.ai-gen-row').getBoundingClientRect();
          const left = document.querySelector('[data-test="ai-gen-form"]').getBoundingClientRect();
          const right = document.querySelector('[data-test="ai-gen-batch-panel"]').getBoundingClientRect();
          const scroll = document.querySelector('.batch-rows-scroll');
          return {
            band, rowH: row.height, leftH: left.height, rightH: right.height,
            overflow: getComputedStyle(scroll).overflowY,
            outerScroll: document.documentElement.scrollHeight - window.innerHeight,
          };
        }""")
        assert abs(geo["rowH"] - geo["band"]) <= 32, f"row band fill off: {geo}"
        assert geo["leftH"] >= geo["band"] - 32, f"left card not full height: {geo}"
        assert geo["rightH"] >= geo["band"] - 32, f"right card not full height: {geo}"
        assert geo["overflow"] == "auto", f"rows region must flow inside: {geo}"
        assert geo["outerScroll"] <= 2, f"outer page must not grow: {geo}"

        # --- worst-case scale of the inner flow: 14 more typed rows stay inside
        # the scroll region (the card never grows past the band).
        for _ in range(14):
            page.locator('[data-test="ai-gen-batch-add"]').click()
        grown = page.evaluate("""() => {
          const scroll = document.querySelector('.batch-rows-scroll');
          const right = document.querySelector('[data-test="ai-gen-batch-panel"]').getBoundingClientRect();
          return {inner: scroll.scrollHeight > scroll.clientHeight,
                  bandOk: right.height >= window.innerHeight - 80 - 32};
        }""")
        assert grown["inner"], f"15-row input must scroll inside: {grown}"
        assert grown["bandOk"], f"panel kept its band under growth: {grown}"

        # --- leave the page for good (different menu), then come back again.
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        page.wait_for_timeout(500)
        _open_generate(page, pid)
        expect(page.locator(BATCH_ITEM)).to_have_count(2, timeout=15000)

        # --- S3 tail: stop everything, progress settles (no spinner left).
        info = _batch_tasks(api_client, batch_id)
        for item in info["items"]:
            _cancel_quietly(api_client, item["taskId"])
        deadline = time.time() + 60
        while time.time() < deadline:
            info = _batch_tasks(api_client, batch_id)
            if info["pending"] + info["running"] == 0:
                break
            time.sleep(2)
        assert info["pending"] + info["running"] == 0, f"queue never settled: {info}"
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="ai-gen-batch-progress-count"]')).to_contain_text("2/2", timeout=20000)
        assert_no_errors(collectors)
        _no_issues(page, collectors, "queue recovery S1")
    finally:
        if pid:
            purge_project(api_client, pid)


def test_requirement_page_badge_marks_generating_and_clears(page, collectors, api_client, uniq):
    """S2+S3: live badge on the requirement row; badge click lands on the queue;
    after the stop the badge silently disappears (no toast, no manual refresh)."""
    pid = None
    try:
        pid = _make_project(api_client, uniq)
        req = _mk_requirement(api_client, pid, f"生成中标注需求点_{uniq}")
        text = "密码强度提示需实时刷新"
        r = api_client.post("/ai/batch-generate", json={
            "projectId": pid,
            "requirements": [text],
            "items": [{"text": text, "requirementId": req["id"]}],
        })
        assert r.status_code == 200 and r.json().get("code") == "0", r.text[:300]
        batch_id = r.json()["data"]["batchId"]

        # Requirement page: the live annotation badge shows without any submit handle.
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/requirements?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="req-page"]')).to_be_visible(timeout=15000)
        badge = page.locator(f'[data-test="req-gen-live-{req["id"]}"]')
        expect(badge).to_be_visible(timeout=20000)

        # Badge click lands on the queue panel of the same segment.
        badge.click()
        page.wait_for_url(re.compile(r"#/ai/generate\?.*batchId=" + str(batch_id)), timeout=15000)

        # Stop the child; the requirement page polls the annotation silently and
        # the badge disappears without any reload or toast.
        info = _batch_tasks(api_client, batch_id)
        for item in info["items"]:
            _cancel_quietly(api_client, item["taskId"])
        inject_user_token(page, ADMIN_USERNAME, ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/requirements?projectId={pid}", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="req-page"]')).to_be_visible(timeout=15000)
        expect(page.locator(f'[data-test="req-gen-live-{req["id"]}"]')).to_have_count(0, timeout=30000)
        assert_no_errors(collectors)
        _no_issues(page, collectors, "queue recovery S2")
    finally:
        if pid:
            purge_project(api_client, pid)
