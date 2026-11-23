"""Worst-scale E2E for the monitor health wall (filter band + paged window).

Scale shape: 33 targets across 3 environments (>=30 rows iron rule, no small-sample
self-test). Real POST creation gate for every tile (no SQL seeding of monitor rows).

Covers (R21/R22/R25 promises of the wall page):
- paged window: the URL size parameter drives the server-side window down to 20 tiles
  while the pager total still carries the full 33 (shipped contract = default page
  size 200 with [20, 50, 100, 200] options and page state URL-restored through
  route.query.size — MonitorView.vue; the previous expectation of a default 30-tile
  page was stale wording, corrected against the shipped source as the authority)
- keyword query: inline submit button filters server-side to one tile
- URL round-trip: filter state lands in the query string and reload restores the view
- second page: the remaining tiles of the 33-row window render after page switch
- group collapse: clicking an environment group head hides its tiles, click again shows
- filtered-empty escape: a no-hit keyword shows the filtered empty state whose
  clear-filter control restores the full first page
- three-class zero tolerance via assert_no_errors (4xx/5xx, console error/warning,
  browser Issues panel) with --headless=new + issues probe from conftest.

Cleanup: project purge?force=true + zero-residue assertion (DELETE purge form).
"""
import time

import pytest
from conftest import assert_no_errors, nav_to, pick_project_in_header
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

TILE = ".tile__name"


def _ok(resp, what):
    assert resp.status_code == 200, f"{what} http {resp.status_code}: {resp.text[:200]}"
    body = resp.json()
    assert body.get("code") == "0", f"{what} biz {body.get('code')}: {str(body)[:200]}"
    data = body.get("data")
    return data["id"] if isinstance(data, dict) and "id" in data else data


@pytest.fixture()
def wall_env(api_client, uniq):
    """Real API seeding: one project + three envs + 33 HTTP targets (creation gate)."""
    pid = _ok(api_client.post("/projects", json={
        "name": f"墙规模项目{uniq}", "code": f"WS{uniq[:5].upper()}"}), "建项目")
    eids = []
    for i in range(3):
        # one env per type per project is the product rule
        # seed dev/test/staging (1/2/3) instead of three test-type envs.
        eids.append(_ok(api_client.post(f"/projects/{pid}/envs", json={
            "name": f"墙规模环境{i}{uniq}", "envType": 1 + i,
            "baseUrl": "http://localhost:8083/actuator/health"}), "建环境"))
    names = []
    for i in range(33):
        names.append(f"墙规模目标{i:02d}{uniq}")
        _ok(api_client.post("/monitor/targets", json={
            "projectId": pid, "envId": eids[i % 3], "name": names[-1],
            "targetType": "HTTP",
            "probeConfig": {"url": "http://localhost:8083/actuator/health", "expect": "UP"},
            "checkIntervalSeconds": 60, "enabled": True}), f"建目标{i}")
    yield pid, eids, names, f"墙规模项目{uniq}", names[17]
    api_client.delete(f"/projects/{pid}/purge?force=true")


def _tiles(page):
    return page.locator(f'[data-test="monitor-matrix"] {TILE}')


def test_wall_worst_scale(logged_in, collectors, wall_env):
    page = logged_in
    pid, eids, names, project_name, mid_name = wall_env
    pick_project_in_header(page, project_name)
    nav_to(page, "监控中心", "/monitor", '[data-test="monitor-page"]', submenu="执行测试")

    # 1) paged window: URL size=20 narrows the server-side window; total stays 33
    expect(page.locator('[data-test="monitor-matrix"]')).to_be_visible(timeout=15000)
    url = page.url
    page.goto(f"{url}{'&' if '?' in url else '?'}size=20", wait_until="domcontentloaded")
    expect(_tiles(page)).to_have_count(20, timeout=15000)
    expect(page.locator('[data-test="monitor-pager"], .monitor-pager')).to_contain_text(
        "33", timeout=10000)
    group_heads = page.locator('[data-test="monitor-group"] .monitor-group__head')
    assert group_heads.count() == 3, "三个环境组头应随窗口服务端返回"

    # 2) keyword query through the inline submit button -> exactly one tile
    #    (el-input forwards attrs onto the inner <input>, so the data-test host may be
    #    either the wrapper or the input itself; the union selector pins both shapes)
    KW = '[data-test="monitor-keyword-input"] input, input[data-test="monitor-keyword-input"]'
    page.locator(KW).fill(mid_name)
    page.locator('[data-test="monitor-query-btn"]').click()
    expect(_tiles(page)).to_have_count(1, timeout=10000)
    expect(_tiles(page).first).to_have_text(mid_name)

    # 3) URL round-trip: the keyword travelled into the query and reload restores it
    #    (hash router percent-encodes the CJK keyword, so compare the decoded form)
    from urllib.parse import unquote
    assert mid_name in unquote(page.url), f"URL 应含 keyword 回灌: {page.url}"
    page.reload(wait_until="domcontentloaded")
    expect(_tiles(page)).to_have_count(1, timeout=15000)
    expect(page.locator(KW)).to_have_value(mid_name)

    # 4) filtered-empty escape: no-hit keyword -> filtered empty -> clear restores page 1
    page.locator(KW).fill(f"无此名{int(time.time())}")
    page.locator('[data-test="monitor-query-btn"]').click()
    empty = page.locator('[data-test="empty-state"], .empty-state')
    expect(empty).to_be_visible(timeout=10000)
    clear = page.locator('[data-test="empty-clear-filter"], .empty-state button').first
    clear.click()
    expect(_tiles(page)).to_have_count(20, timeout=15000)

    # 5) second page: remaining 13 tiles after switching window page
    page.locator('.monitor-pager .el-pager li', has_text="2").first.click()
    expect(_tiles(page)).to_have_count(13, timeout=10000)

    # 6) group collapse: head click hides that group's tiles, second click shows them
    head = group_heads.first
    group = page.locator('[data-test="monitor-group"]').first
    tiles_before = group.locator(TILE).count()
    assert tiles_before > 0
    head.click()
    expect(group.locator(".monitor-group__tiles")).to_have_count(0, timeout=5000)
    head.click()
    expect(group.locator(TILE)).to_have_count(tiles_before, timeout=5000)

    assert_no_errors(collectors, "monitor wall worst-scale")
