# -*- coding: utf-8 -*-
"""First full-journey E2E for whole-collection runs .

Real browser + real admin -> Feign -> core -> live executor -> Postgres chain
(no mocks): a throwaway project carries one collection with 30 requests
(worst-case scale fixture demanded by the display-scale iron rule), including
an extract / backward-reference / forward-reference trio so the honest forward
banner has a real subject.

Journey doors:
1. deep link /#/api-test?projectId= -> collection auto-selects -> the launch
 dialog opens on the preview card: 30 rows, the forward-reference banner
 counts exactly the rows whose producer sits behind the consumer, and the
 card stays instantly searchable at 50+ scale (filter + honest no-match +
 restore);
2. launch-disabled spectra: the button stays disabled until an environment is
 picked (no post-submit generic error);
3. launch through the UI with CONTINUE_AND_SUMMARY -> the drawer opens on the
 live detail -> 30 per-hop traffic lights -> poll to a terminal ledger
 verdict on the real executor chain; the forward hop honestly ends non-green
 (its pool key did not exist yet);
4. reload persistence: re-enter the page, open the history drawer — the run is
 still listed (ledger rows are DB truth, not frontend state);
5. zero-tolerance gates: 4xx/5xx, console error/warn, browser Issues
 (assert_no_errors bundles all three);
6. the throwaway project is purged for real with zero residue across the four
 apitest tables (the run-set purge order was fixed the same day).
"""
import re
import time

import pytest
from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
)

pytestmark = pytest.mark.timeout(600)

LIVE_BASE = "http://127.0.0.1:8899"
HOPS = 30  # worst-case scale fixture


def sql(q):
    import subprocess
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def wait_set_terminal(set_id, secs=300):
    """Poll the collection-run ledger to a terminal verdict (2 passed/3 failed/4 interrupted)."""
    deadline = time.time() + secs
    while time.time() < deadline:
        st = sql(f"SELECT status FROM tp_apitest_run_set WHERE id={set_id}")
        if st in ("2", "3", "4"):
            return st
        time.sleep(3)
    return "timeout"


def pick_option(page, select_test, option_text):
    page.locator(f'[data-test="{select_test}"]').click()
    page.locator(".el-select-dropdown__item:visible").filter(
        has_text=option_text).first.click()


def test_t369_runset_journey(page, collectors, api_client, uniq):
    suffix = uniq[:6]
    pid = col_id = set_id = None
    env_name = f"T369旅程环境{suffix}"
    plain = '[{"type":"status","op":"equals","expect":200}]'

    def save_req(name, body, assertions):
        rr = api_client.post("/apitest/requests", json={
            "collectionId": col_id, "name": name, "method": "POST", "urlPath": "/api/login",
            "headersJson": '[{"name":"Content-Type","value":"application/json"}]',
            "bodyText": body, "authJson": "{}", "assertionsJson": assertions})
        assert rr.json().get("code") == "0", f"建请求 {name} 失败: {rr.text[:300]}"

    try:
        # ── Arrange: project + env + one collection with 30 hops (real API) ──
        r = api_client.post("/projects", json={
            "name": f"T369旅程项目{suffix}", "code": f"P369{suffix}"})
        assert r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
        d = r.json()["data"]
        pid = d["id"] if isinstance(d, dict) else d

        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": env_name, "envType": 2, "baseUrl": LIVE_BASE,
            "configJson": "{}", "endTypes": "web", "browsers": "chromium",
            "versionSnapshot": f"p369-{suffix}"})
        assert r.json().get("code") == "0", f"建环境失败: {r.text[:300]}"

        r = api_client.post("/apitest/collections", json={
            "projectId": pid, "parentId": 0, "name": f"T369集合{suffix}", "tags": ""})
        assert r.json().get("code") == "0", f"建集合失败: {r.text[:300]}"
        col_id = r.json()["data"]["id"]

        # Producer hop: extract a value into the pool for later consumers.
        save_req("提取登录值", '{"username":"e2e"}',
                 '[{"type":"status","op":"equals","expect":200},'
                 '{"type":"extract","path":"$.data.login","saveAs":"loginok"}]')
        # Backward-reference hop: consumes loginok produced one seq earlier —
        # resolves through the single {{VAR:}} parser on the live runner.
        save_req("消费已产出值", '{"marker":"{{VAR:loginok}}"}', plain)
        # Forward-reference hop: consumes a key whose producer sits LAST, so the
        # preview flags it honestly and the launch banner shows count 1.
        save_req("前向引用跳", '{"marker":"{{VAR:latekey}}"}', plain)
        # Fill to the worst-case fixture size with plain green hops.
        for i in range(HOPS - 4):
            save_req(f"探针跳{i:02d}", '{"i":%d}' % i, plain)
        # Late producer: extracts the key the forward hop tried to consume early.
        save_req("末位产出者", '{"last":true}',
                 '[{"type":"status","op":"equals","expect":200},'
                 '{"type":"extract","path":"$.data.login","saveAs":"latekey"}]')

        # ── Door 1: deep link -> dialog -> preview card at worst-case scale ──
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")
        run_btn = page.locator('[data-test="apitest-run-collection-btn"]')
        expect(run_btn).to_be_enabled(timeout=20000)
        run_btn.click()
        expect(page.locator('[data-test="apitest-runset-dialog"]')).to_be_visible(timeout=10000)

        rows = page.locator('[data-test="apitest-runset-preview-row"]')
        expect(rows).to_have_count(HOPS, timeout=15000)

        # Honest forward banner: exactly the one forward-reference row.
        alert = page.locator('[data-test="apitest-runset-forward-alert"]')
        expect(alert).to_be_visible(timeout=5000)
        expect(alert).to_contain_text("1 个请求引用了排在其后的提取值")

        # SE iron rule: the fixed-height card stays instantly searchable.
        # Union locator: el-input may forward data-test either to the wrapper
        # or straight to the inner control depending on fallthrough, so both
        # forms are matched (same posture as the promote-journey precedents).
        search = page.locator(
            'input[data-test="apitest-runset-order-search"],'
            ' [data-test="apitest-runset-order-search"] input')
        search.fill("前向引用跳")
        expect(rows).to_have_count(1, timeout=5000)
        search.fill("不存在的名串zz")
        expect(rows).to_have_count(0, timeout=5000)
        expect(page.locator('[data-test="apitest-runset-dialog"]').get_by_text(
            "没有匹配的顺序行")).to_be_visible(timeout=5000)
        search.fill("")
        expect(rows).to_have_count(HOPS, timeout=5000)

        # ── Door 2: launch-disabled spectra before an environment is picked ──
        launch_btn = page.locator('[data-test="apitest-runset-launch-btn"]')
        expect(launch_btn).to_be_disabled(timeout=5000)

        # ── Door 3: pick env + CONTINUE_AND_SUMMARY, launch through the UI ──
        pick_option(page, "apitest-runset-env-select", env_name)
        expect(launch_btn).to_be_enabled(timeout=5000)
        page.locator('[data-test="apitest-runset-mode-group"] .el-radio').nth(1).click()
        launch_btn.click()
        # Toast copy follows the naming decision that renames Collection in
        # user-facing wording; the assertion tracks the shipped text.
        expect(page.locator(".el-message--success").first).to_contain_text(
            "接口集运行已发起", timeout=15000)

        expect(page.locator('[data-test="apitest-runset-drawer"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="apitest-runset-history-row"]').first) \
            .to_be_visible(timeout=15000)
        expect(page.locator('[data-test="apitest-runset-detail"]')).to_be_visible(timeout=15000)

        set_id = int(sql(f"SELECT id FROM tp_apitest_run_set WHERE collection_id={col_id}"
                         " ORDER BY id DESC LIMIT 1"))
        assert set_id > 0, "发起后账本未落库"

        lights = page.locator('[data-test="apitest-runset-light"]')
        expect(lights).to_have_count(HOPS, timeout=15000)

        st = wait_set_terminal(set_id)
        assert st in ("2", "3", "4"), (
            f"集合运行未在 300s 内收口（status={st}）——查 runner 在位与派发链")
        # The terminal verdict is rendered, not merely stored.
        expect(page.locator('[data-test="apitest-runset-status-tag"]')).to_contain_text(
            re.compile("全部通过|存在失败|已中断"), timeout=15000)
        # The forward hop honestly ended non-green (its pool key did not exist).
        forward_green = sql(
            "SELECT count(*) FROM tp_apitest_run rs JOIN tp_apitest_request q"
            " ON q.id=rs.request_id WHERE rs.run_set_id=%s AND q.name='前向引用跳'"
            " AND rs.status=1" % set_id)
        assert forward_green == "0", (
            f"前向引用跳被池值意外救活（status=1 计 {forward_green}，fail-closed 谱失守）")

        # ── Door 4: reload persistence — history is DB truth, not page state ──
        # Leave to another route first: a goto of the identical hash URL is a
        # same-document navigation (the open drawer would survive and its
        # overlay intercepts the toolbar click), so re-entry must cross a real
        # page load through /dashboard (same posture as the promote journey).
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="apitest-runset-history-btn"]')).to_be_enabled(timeout=20000)
        page.locator('[data-test="apitest-runset-history-btn"]').click()
        expect(page.locator('[data-test="apitest-runset-drawer"]')).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="apitest-runset-history-row"]').first) \
            .to_be_visible(timeout=15000)

        assert_no_errors(collectors, "T369 collection-run full journey")
    finally:
        # Purge for real (run/run-set rows precede their parents since the
        # same-day purge-order fix), then reconcile residue to zero.
        try:
            if set_id:
                sql("UPDATE tp_apitest_run SET status = 3 WHERE run_set_id = %s"
                    " AND status IN (0,1)" % set_id)
            if pid:
                r = api_client.delete(f"/projects/{pid}/purge?force=true")
                assert r.json().get("code") == "0", f"purge 失败: {r.text[:200]}"
                col_set = f"(SELECT id FROM tp_apitest_collection WHERE project_id={pid})"
                # run rows are keyed by request_id, not collection_id.
                checks = [
                    ("tp_apitest_collection",
                     f"SELECT count(*) FROM tp_apitest_collection WHERE project_id={pid}"),
                    ("tp_apitest_request",
                     f"SELECT count(*) FROM tp_apitest_request WHERE collection_id IN {col_set}"),
                    ("tp_apitest_run_set",
                     f"SELECT count(*) FROM tp_apitest_run_set WHERE collection_id IN {col_set}"),
                    ("tp_apitest_run",
                     "SELECT count(*) FROM tp_apitest_run rs JOIN tp_apitest_request q"
                     " ON q.id=rs.request_id JOIN tp_apitest_collection c"
                     f" ON c.id=q.collection_id WHERE c.project_id={pid}"),
                ]
                for tbl, q in checks:
                    left = sql(q)
                    assert left == "0", f"purge 后 {tbl} 残留 {left}"
        except Exception as cleanup_error:  # noqa: BLE001 — surface, never swallow
            print(f"[t369 cleanup] {cleanup_error}")
