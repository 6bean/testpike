
import pytest
from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    pick_apitest_collection,
    pick_project_in_header,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

HOPS = 30
PRODUCER = "流程产出跳"
CONSUMER = "流程消费跳"
FORWARD = "流程前向跳"
TAIL = "流程末位产出"


def _seed(api_client, uniq):
    r = api_client.post("/projects", json={"name": f"T414b流程项目{uniq}", "code": f"T414B{uniq[:6].upper()}", "tags": ""})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    r = api_client.post("/apitest/collections",
                        json={"projectId": pid, "parentId": 0, "name": f"T414b流程接口集{uniq}", "tags": ""})
    assert r.json().get("code") == "0", f"建接口集失败: {r.text[:300]}"
    cid = r.json()["data"]["id"]

    def save(name, body, assertions, precondition=None, loop=None):
        payload = {
            "collectionId": cid, "name": f"{name}{uniq}", "method": "POST",
            "protocol": "http", "urlPath": f"/flow/{name[2]}", "headersJson": "[]",
            "bodyText": body, "authJson": "{}", "assertionsJson": assertions,
        }
        if precondition:
            payload["preconditionJson"] = precondition
        if loop:
            payload["loopJson"] = loop
        r = api_client.post("/apitest/requests", json=payload)
        assert r.json().get("code") == "0", f"建请求失败 {name}: {r.text[:300]}"

    plain = '[{"type":"status","op":"equals","expect":200}]'
    # Hop 1: producer with BOTH orchestration badges + an extracted pool key.
    save(PRODUCER, '{"username":"e2e"}',
         '[{"type":"status","op":"equals","expect":200},{"type":"extract","path":"$.data.login","saveAs":"flowkey"}]',
         precondition='{"field":"status","op":"eq","expect":200}',
         loop='{"mode":"count","times":3,"delayMs":1000}')
    # Hop 2: consumes the key produced one seq earlier (backward chord, info).
    save(CONSUMER, '{"marker":"{{VAR:flowkey}}"}', plain)
    # Hop 3: consumes a key whose producer sits LAST (forward chord, warning).
    save(FORWARD, '{"marker":"{{VAR:lateflow}}"}', plain)
    # Fill to the worst-case fixture size.
    for i in range(HOPS - 4):
        save(f"探针跳{i:02d}", '{"i":%d}' % i, plain)
    save(TAIL, '{"last":true}',
         '[{"type":"status","op":"equals","expect":200},{"type":"extract","path":"$.data.login","saveAs":"lateflow"}]',
         precondition='{"field":"var","op":"exists","name":"flowkey"}')
    return pid, cid


def test_t414b_flow_view_order_badges_window(page, collectors, api_client, uniq):
    """Toggle -> execution truth order -> badges -> windowing -> click-through."""
    pid, cid = _seed(api_client, uniq)
    proj = f"T414b流程项目{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test", wait_until="domcontentloaded")
        pick_project_in_header(page, proj)
        pick_apitest_collection(page, f"T414b流程接口集{uniq}")
        browse_rows = page.locator('[data-test^="apitest-request-row-"]')
        expect(browse_rows.first).to_be_visible(timeout=20000)
        # browse rows are themselves windowed : the scrollbar
        # covering the full source is the honest "all loaded" signal.
        page.wait_for_function(
            """() => { const el = document.querySelector('[data-test="apitest-request-rows"]');
            return el && el.scrollHeight >= 30 * 30; }""", timeout=15000
        )

        # --- 1. mutual exclusion: the flow pane replaces the browse rows
        page.locator('[data-test="apitest-flow-flow-btn"]').click()
        expect(page.locator('[data-test="apitest-flow-pane"]')).to_be_visible()
        expect(page.locator('[data-test="apitest-request-rows"]')).to_have_count(0)

        # --- 2. windowing at worst scale (test_t413 posture): the scrollbar
        #        spans the full 30-hop source while the DOM stays at the
        #        window size (small lists render in full only when they fit).
        flow_rows = page.locator('[data-test^="apitest-flow-row-"]')
        expect(flow_rows.first).to_be_visible(timeout=15000)
        page.wait_for_function(
            """() => { const el = document.querySelector('[data-test="apitest-flow-rows"]');
            return el && el.scrollHeight >= 30 * 30; }""", timeout=15000
        )
        dom_rows = flow_rows.count()
        assert dom_rows < HOPS, f"30 跳最坏夹具未窗口化（DOM={dom_rows}）"

        # --- 3. order = execution truth: row #1 carries the producer name,
        #        and the server preview agrees with the drawn seq numbers.
        first = page.locator('[data-test="apitest-flow-row-1"]')
        expect(first).to_contain_text(PRODUCER + uniq)
        preview = api_client.get(f"/apitest/run-sets/previews?collectionId={cid}").json()["data"]
        assert [p["seqNo"] for p in preview] == list(range(1, HOPS + 1)), "预览序不是连续执行序"
        assert preview[0]["name"] == f"{PRODUCER}{uniq}", "首行序与绘制不符（画的即跑破功）"

        # --- 4. orchestration badges joined from the loaded rows (zero new
        #        requests): precondition + loop on hop 1, precondition on tail.
        expect(first.locator('[data-test="apitest-flow-pre-tag"]')).to_contain_text("前置")
        expect(first.locator('[data-test="apitest-flow-loop-tag"]')).to_contain_text("循环")
        expect(first.locator('[data-test="apitest-flow-dep-tag"]')).to_have_count(0)
        # the tail row sits behind the bottom window pad — reach it the honest
        # user way (pane instant search, <=2-gate rule) to prove the badge join
        # covers every loaded row, not just the head of the window.
        page.locator('[data-test="apitest-flow-search"]').fill(TAIL)
        tail_row = page.locator('[data-test^="apitest-flow-row-"]')
        expect(tail_row).to_have_count(1, timeout=5000)
        expect(tail_row).to_contain_text(f"{TAIL}{uniq}")
        expect(tail_row.locator('[data-test="apitest-flow-pre-tag"]')).to_contain_text("flowkey")
        page.locator('[data-test="apitest-flow-search"]').fill("")
        expect(flow_rows.first).to_be_visible(timeout=5000)

        # --- 5. dependency chords reuse the runSet vocabulary.
        consumer_seq = next(p["seqNo"] for p in preview if p["name"] == f"{CONSUMER}{uniq}")
        backward = page.locator(f'[data-test="apitest-flow-row-{consumer_seq}"] [data-test="apitest-flow-dep-tag"]')
        expect(backward.first).to_contain_text("flowkey")
        expect(backward.first).to_contain_text("产出")
        forward_seq = next(p["seqNo"] for p in preview if p["name"] == f"{FORWARD}{uniq}")
        warn = page.locator(f'[data-test="apitest-flow-row-{forward_seq}"] [data-test="apitest-flow-dep-tag"]')
        expect(warn.first).to_contain_text("前向引用")

        # --- 6. instant search filters the pane itself (SE rule).
        page.locator('[data-test="apitest-flow-search"]').fill(CONSUMER)
        expect(flow_rows).to_have_count(1, timeout=5000)
        page.locator('[data-test="apitest-flow-search"]').fill("")
        expect(flow_rows.first).to_be_visible(timeout=5000)
        page.wait_for_function(
            """() => { const el = document.querySelector('[data-test="apitest-flow-rows"]');
            return el && el.scrollHeight >= 30 * 30; }""", timeout=15000
        )

        # --- 7. click-through lands the workspace editor on that request.
        page.locator(f'[data-test="apitest-flow-row-{consumer_seq}"]').click()
        expect(page.locator(".ap-workspace-title")).to_contain_text(CONSUMER + uniq)

        # --- 8. back to browse restores the windowed browse rows.
        page.locator('[data-test="apitest-flow-browse-btn"]').click()
        expect(page.locator('[data-test="apitest-request-rows"]')).to_be_visible()
        expect(page.locator('[data-test="apitest-flow-pane"]')).to_have_count(0)

        assert_no_errors(collectors, "T414b 流程视图序/徽标/窗口化/点击穿透")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
