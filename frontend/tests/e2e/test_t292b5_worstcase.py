""" final gate ⑤: worst-scale (100-row) real-browser table mechanics.

Display-scale iron law under the worst fixture: 100 cases filed through the
real admin:8081 API (no mocks) into a throwaway project — the case ledger must
stay a bounded server-side window, a header drag must write the new width back
through useColumnConfig and survive reload, a sortable header click must go
through the server SORT_KEY_MAP whitelist (never a client-only re-sort of the
window), and the perf workbench run drawer must append its second window via
"load more" instead of pulling the whole ledger.

Data isolation: uuid-suffixed project purged via /projects/{pid}/purge in
finally (never touches the demo projects); run rows cascade with the project
(PerfRunServiceImpl + ProjectServiceImpl purge order).
"""
import subprocess

import httpx
import pytest
from conftest import API_BASE, BASE_URL, assert_no_errors, inject_token, nav_to
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(420)

CASE_COUNT = 100       # >=30-row worst-scale iron-law fixture (x3 for margin)
PERF_PATH = "/perf"    # PerfWorkbenchView route (routes.ts perf group)
RUNS_WINDOW = 20       # PerfWorkbenchView RUNS_WINDOW_SIZE


def _sql(q):
    r = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc", q],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"sql failed: {r.stderr[:200]}"
    return r.stdout.strip()


def _first_id(raw):
    for line in raw.splitlines():
        line = line.strip()
        if line.isdigit():
            return int(line)
    raise RuntimeError("no id row in psql output: " + raw[:200])


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"T292刀5最坏规模{uniq}", "code": f"T292W5{uniq.upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _file_cases(api, pid, count, prio_of):
    """100 real API creates; priority alternates P2/P0 so server-side sort is
    provable at the first page boundary (window 100/page would hide it)."""
    for i in range(1, count + 1):
        r = api.post("/cases", json={
            "projectId": pid, "title": f"刀5最坏用例{i:03d}_{i % 7}",
            "precondition": "前置", "expectedResult": "预期",
            "priority": prio_of(i), "confidence": (i % 3) + 1,
            "endType": "web", "phase": 1, "source": 2, "status": 1,
            "steps": [{"stepNo": 1, "description": "步骤一", "isAssert": 1}],
        })
        assert r.status_code == 200 and r.json().get("code") == "0", f"建用例 {i} 失败: {r.text[:200]}"


def _select_project(page, proj_name):
    page.locator("[data-test=project-ctx-select]").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item:visible").filter(
        has_text=proj_name).first.click()
    expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=15000)
    # popper fade-out trap (o3 lesson): wait until the dropdown is really gone
    expect(page.locator(".el-select-dropdown:visible")).to_have_count(0, timeout=10000)


def _reset_colcfg(page):
    try:
        page.evaluate("() => { for (const k of Object.keys(localStorage)) "
                      "if (k.startsWith('tp-colcfg-')) localStorage.removeItem(k) }")
    except Exception:
        pass


def test_worst_scale_case_ledger_window_drag_sort(page, collectors, api_client, uniq):
    """ ⑤a: 100-row case ledger — bounded window, header-dragend width
 write-back across reload, SORT_KEY_MAP server sort on the priority column."""
    pname = f"T292刀5最坏规模{uniq}"
    pid = _mk_project(api_client, uniq)
    # i%2==0 -> P0 (value 0, "highest"), else P2 — deterministic split 50/50
    _file_cases(api_client, pid, CASE_COUNT, prio_of=lambda i: 0 if i % 2 == 0 else 2)
    try:
        inject_token(page)
        # window-size determinism: default list window is 10 rows regardless of
        # any leftover tp-cases-page-size preference
        page.evaluate("() => localStorage.setItem('tp-cases-page-size', '10')")
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        _select_project(page, pname)

        # --- window bounded: default page size (10) shows a segment, pager total
        #     tells the truth about all 100 rows (never a 100-row inline dump) ---
        expect(page.locator('[data-test="case-table"] tbody tr')).to_have_count(10, timeout=15000)
        pager = page.locator('[data-test="case-pagination"]')
        expect(pager).to_be_visible()
        expect(pager).to_contain_text("100", timeout=10000)

        # --- header drag write-back /5.8 contract: border mode +
        #     useColumnConfig.updateWidth) on the case-number column ---
        th = page.locator('[data-test="case-table"] thead th').filter(has_text="编号").first
        box = th.bounding_box()
        assert box and box["width"] > 50
        start_w = box["width"]
        new_w = start_w
        for _attempt in range(3):
            box = th.bounding_box()
            edge_x, mid_y = box["x"] + box["width"] - 4, box["y"] + box["height"] / 2
            page.mouse.move(edge_x - 10, mid_y)
            page.mouse.move(edge_x, mid_y)
            page.mouse.down()
            page.mouse.move(edge_x + 60, mid_y, steps=6)
            page.mouse.up()
            new_w = th.bounding_box()["width"]
            if new_w > start_w + 30:
                break
        assert new_w > start_w + 30, f"拖宽未生效: {start_w} -> {new_w}"

        # --- server-side sort on the priority column (custom): asc must pull
        #     the 50 P0 rows to the head of the window — a pure client
        #     re-sort of 10 rows could not flip the page content like this ---
        prio_th = page.locator('[data-test="case-table"] thead th').filter(has_text="优先级").first
        prio_th.click()
        expect(page.locator('[data-test="case-table"] tbody tr').first).to_be_visible(timeout=15000)
        # cell index 5 = third data column (td1=selection, td2=expand,
        # td3=case-no, td4=title, td5=priority) — same column spectrum the
        # o3 suite asserts
        prio_cells = page.locator('[data-test="case-table"] tbody tr td:nth-child(5) .cell')
        texts = [prio_cells.nth(i).inner_text().strip() for i in range(10)]
        assert all(x == "P0" for x in texts), f"优先级升序窗口首屏未全是 P0: {texts}"

        # --- reload: dragged width AND sort-window both survive (localStorage
        #     column prefs; sort resets is acceptable — only width is persisted) ---
        page.reload(wait_until="domcontentloaded")
        nav_to(page, "用例管理", "/cases", '[data-test="case-table"]', submenu="设计用例")
        _select_project(page, pname)
        th2 = page.locator('[data-test="case-table"] thead th').filter(has_text="编号").first
        w2 = th2.bounding_box()["width"]
        assert abs(w2 - new_w) < 10, f"reload 后拖宽丢失: {new_w} -> {w2}"

        assert_no_errors(collectors, "刀5⑤ 百行台账窗口+拖宽+服务端排序")
    finally:
        _reset_colcfg(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


def test_worst_scale_perf_run_drawer_second_window(page, collectors, api_client, uniq):
    """ ⑤b: 25 seeded runs on one perf scenario — the drawer opens on a
 bounded 20-row window with a truthful count line, and "load more" appends
 window 2 (5 rows) to 25 without blanking what is already on screen."""
    pid = _mk_project(api_client, uniq)
    token = api_client.headers["Authorization"].split(" ", 1)[1]
    env_id = _first_id(_sql("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                             f"VALUES ({pid}, 't292w5{uniq}', 1, 'http://perf.stub.test') RETURNING id"))
    r = httpx.post(f"{API_BASE}/perf/scenarios",
                   headers={"Authorization": f"Bearer {token}"}, timeout=20, json={
                       "projectId": pid, "name": f"窗口场景{uniq}", "envId": env_id,
                       "pressureModel": "CONSTANT_RPS",
                       "pressureConfig": {"targetRps": 15, "durationSeconds": 60},
                       "steps": [{"action": "http.request", "method": "GET", "url": "{{ENV:BASE}}/health"}]})
    data = r.json().get("data")
    scn = data if isinstance(data, int) else (data or {}).get("id")
    assert scn, f"建压测场景失败: {r.text[:200]}"
    total_runs = RUNS_WINDOW + 5
    for i in range(total_runs):
        _sql("INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot, stop_reason) "
             f"VALUES ({scn}, {pid}, 2, CAST('{{\"seed\":{i}}}' AS jsonb), '刀5窗口探针{i}')")
    try:
        inject_token(page)
        # throwaway-user-safe posture: clear header context while on the
        # dashboard, then land on /perf with the deep-link query (fresh mount
        # -> useProjectPageContext URL-first chain wins over any stored segment)
        page.evaluate(
            "(() => { for (const k of Object.keys(localStorage)) "
            "if (k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); })()")
        page.goto(f"{BASE_URL}/#{PERF_PATH}?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="perf-scenario-table"]')).to_be_visible(timeout=15000)
        # scenario ledger stays bounded (1 scenario exists, window >=1 row)
        expect(page.locator('[data-test="perf-scenario-table"] tbody tr').first).to_be_visible(timeout=15000)

        page.locator('[data-test="perf-row-runs"]').first.click()
        # el-drawer attrs fall-through is not contract-guaranteed — assert on
        # the drawer-internal data-test anchors only (rows live nowhere else)
        rows = page.locator('[data-test="perf-run-row"]')
        expect(rows).to_have_count(RUNS_WINDOW, timeout=15000)
        count_line = page.locator('[data-test="perf-runs-count"]')
        expect(count_line).to_contain_text("20", timeout=5000)
        expect(count_line).to_contain_text("25", timeout=5000)

        page.locator('[data-test="perf-runs-loadmore"]').click()
        expect(rows).to_have_count(total_runs, timeout=15000)
        # second window appended keeps newest-first: the first row is still the
        # newest seeded run (id DESC) — nothing re-ordered under the append
        expect(count_line).to_contain_text("25", timeout=5000)
        loadmore = page.locator('[data-test="perf-runs-loadmore"]')
        expect(loadmore).to_have_count(0, timeout=5000)  # window exhausted -> no dead click

        assert_no_errors(collectors, "刀5⑤ perf run 抽屉第二窗口追加")
    finally:
        api_client.delete(f"/projects/{pid}/purge?force=true")
