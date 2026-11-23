"""R25 mode-1 tab-scoped column settings — E2E functional spectrum.

Reported defect: /tags carries two tabs but ONE column-settings control bound
to one table only, so hiding a column while the other tab was visible changed
nothing on screen (silent scope failure). This spectrum proves the FIX in the
real browser (static template contracts cannot catch "panel present, not
wired" fake-green; R25 requires the functional cross-check):

1. tag page (mode 1 + honest fixed-view pane): hide a column on the tags tab
   -> the tags table really drops the header -> switch to the stats tab ->
   the settings button turns disabled AND its tooltip names the fixed view ->
   clicking it opens nothing -> the stats table shows its own four columns
   -> switch back -> still hidden -> reload -> per-tab persistence holds and
   the fixed pane never writes its own storage key.
2. datafactory page (mode 1, both panes configurable): the popover row SET
   outfits with the active tab (factory columns vs pool columns), an edit in
   one tab never touches the other tab's visible columns, density follows the
   pane, and each pane persists to its own key (tp-colcfg-dataFactories vs
   tp-colcfg-dataPool) -- reload restores both independently.
3. upgrade-center page (same mode-1 wiring, closed the R25 three-piece audit):
   the plans ledger outfits the 9-column panel and a hidden column really
   drops; manifest/matrix/insight are honest fixed views -- each disables the
   scoped button and its tooltip names its own reason; a force click on the
   disabled control opens nothing; no fixed pane ever writes a storage key.

Real browser + real backend login via shared conftest fixtures (credentials
from .env, never inlined here). R4 three-zero tolerance via assert_no_errors.
Header assertions are text-based: el-table-column does not forward data-test
to the rendered th (test_o3_column_config posture, the only assert face).
"""
import json

import pytest
from conftest import BASE_URL, assert_no_errors, inject_token
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)


def _mk_project(api, uniq, tag):
    r = api.post("/projects", json={"name": f"页签作用域{tag}{uniq}", "code": f"TabScope{tag}{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建项目失败: {r.text[:300]}"
    return r.json()["data"]


def _land(page, path, pid):
    """Fresh mount on the page with the header project context (deep link +
    reload; hash-only goto does not remount -- T410a posture)."""
    page.goto(f"{BASE_URL}/#{path}?projectId={pid}", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")


def _col_header(page, table_sel, label):
    return page.locator(f"{table_sel} thead th").filter(
        has=page.locator(f'.cell:text-is("{label}")'))


def _header_labels(page, table_sel):
    return [h.inner_text().strip() for h in page.locator(f"{table_sel} thead th .cell").all()
            if h.inner_text().strip()]


def _settings_btn(page):
    btn = page.locator('[data-test="column-settings-btn"]')
    expect(btn).to_be_visible(timeout=10000)
    return btn


def _open_pop(page):
    _settings_btn(page).click()
    pop = page.locator('[data-test="column-settings-pop"]:visible')
    expect(pop).to_be_visible(timeout=5000)
    return pop


def _close_pop(page):
    # trigger=click popovers toggle closed on a second reference click
    _settings_btn(page).click()
    expect(page.locator('[data-test="column-settings-pop"]:visible')).to_have_count(0, timeout=5000)


def _pop_row_keys(page):
    """Keys of the rows the open popover currently renders (the outfit set)."""
    pop = _open_pop(page)
    keys = [el.get_attribute("data-test").removeprefix("col-cfg-row-")
            for el in pop.locator('[data-test^="col-cfg-row-"]').all()]
    _close_pop(page)
    return keys


def _clear_colcfg(page):
    page.evaluate("() => { for (const k of Object.keys(localStorage)) if (k.startsWith('tp-colcfg-')) localStorage.removeItem(k) }")


# ── spectrum 1: /tags (mode 1 with an honestly disabled fixed-view pane) ─────

def test_tags_tab_scope_settings(page, collectors, api_client, uniq):
    """Tags tab: hide a column and the table really drops it; the stats tab
    turns the button disabled with a tooltip naming the fixed view and opening
    nothing; the stats table keeps its own four columns; back on the tags tab
    the column is still hidden; after reload the pane-scoped key persists and
    the fixed pane never writes storage."""
    pid = _mk_project(api_client, uniq, "T")
    r = api_client.post("/tags", json={"projectId": pid, "name": f"页签标签{uniq[:6]}", "type": 4})
    assert r.status_code == 200 and r.json().get("code") == "0", f"建标签失败: {r.text[:300]}"
    try:
        inject_token(page)
        _clear_colcfg(page)
        _land(page, "tags", pid)
        tags_table = '[data-test="tag-table"]'
        expect(page.locator(f"{tags_table} tbody tr").first).to_be_visible(timeout=15000)
        expect(_col_header(page, tags_table, "引用用例数")).to_have_count(1)

        # panel on the tags tab lists exactly the tags-pane column set
        assert _pop_row_keys(page) == ["name", "caseCount"], "tags 页签面板行集应=tags 列集"

        # hide caseCount on the tags tab -> the visible table really loses it
        pop = _open_pop(page)
        pop.locator('label.el-checkbox[data-test="col-cfg-caseCount"]').click()
        _close_pop(page)
        expect(_col_header(page, tags_table, "引用用例数")).to_have_count(0, timeout=5000)

        # switch to the stats tab: the scoped button turns disabled and names why
        page.locator('[data-test="tag-tabs"] .el-tabs__item').filter(has_text="覆盖率统计").first.click()
        stats_table = '[data-test="tag-stats-table"]'
        expect(page.locator(stats_table)).to_be_visible(timeout=10000)
        btn = _settings_btn(page)
        expect(btn).to_be_disabled(timeout=5000)
        btn.hover()
        expect(page.locator(".el-popper:visible").filter(has_text="覆盖率统计是固定四列视图").first).to_be_visible(timeout=5000)
        # clicking a disabled control opens nothing (R25: no invisible no-op)
        btn.click(force=True)
        expect(page.locator('[data-test="column-settings-pop"]:visible')).to_have_count(0, timeout=2000)

        # the stats table is its own fixed four columns, untouched by the hide
        labels = _header_labels(page, stats_table)
        assert "通过率" in labels and len(labels) == 4, f"stats 表列不受 tags 页签改动影响: {labels}"

        # switch back: the hidden state belongs to the tags tab and survives
        page.locator('[data-test="tag-tabs"] .el-tabs__item').filter(has_text="标签管理").first.click()
        expect(_col_header(page, tags_table, "引用用例数")).to_have_count(0, timeout=5000)
        expect(_col_header(page, tags_table, "标签名称")).to_have_count(1)

        # reload persistence, isolated per pane key
        _land(page, "tags", pid)
        expect(_col_header(page, tags_table, "引用用例数")).to_have_count(0, timeout=5000)
        stored = page.evaluate("() => localStorage.getItem('tp-colcfg-tags')")
        assert stored, "tags 页签持久化键应存在"
        hidden = {c["key"]: c["visible"] for c in json.loads(stored)["columns"]}
        assert hidden == {"name": True, "caseCount": False}, f"tags 页签持久化隔离: {hidden}"
        assert page.evaluate("() => localStorage.getItem('tp-colcfg-tagStats')") is None, \
            "固定视图页签不得写存储键（动作惰性=诚实禁用）"
        assert_no_errors(collectors, "tags 页签作用域")
    finally:
        _clear_colcfg(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


# ── spectrum 2: /data-factories (mode 1, both panes configurable, outfit swap) ─

def test_datafactory_tab_scope_settings(page, collectors, api_client, uniq):
    """Factory tab: hide a column and the table drops it; switching to the
    pool tab outfits the popover row set to the pool column set; edits and
    density stay isolated per pane (same-named keys on both tables never
    cross); after reload both storage keys restore independently."""
    pid = _mk_project(api_client, uniq, "F")
    r = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"页签环境{uniq[:6]}", "envType": 2, "baseUrl": f"https://tabsc-{uniq[:6]}.tp-local.test",
        "endTypes": "web"})
    assert r.json().get("code") == "0", f"建环境失败: {r.text[:200]}"
    env_id = r.json()["data"]
    content = json.dumps({"method": "GET", "url": "https://tabsc-none.tp-local.test/x",
                          "headers": {}, "body": {}, "extractExpr": "$.data[*].v"}, ensure_ascii=False)
    r = api_client.post("/data-factories", json={
        "projectId": pid, "envId": env_id, "type": 1, "name": f"页签造数{uniq[:6]}", "content": content})
    assert r.json().get("code") == "0", f"建造数脚本失败: {r.text[:300]}"
    try:
        inject_token(page)
        _clear_colcfg(page)
        _land(page, "data-factories", pid)
        factory_table = '[data-test="datafactory-table"]'
        expect(page.locator(f"{factory_table} tbody tr").first).to_be_visible(timeout=15000)

        assert _pop_row_keys(page) == ["name", "type", "envName", "status", "createdByName", "createdAt"], \
            "factory 页签面板行集应=工厂列集"

        # hide status on the factory tab -> the factory table really drops it
        pop = _open_pop(page)
        pop.locator('label.el-checkbox[data-test="col-cfg-status"]').click()
        _close_pop(page)
        expect(_col_header(page, factory_table, "状态")).to_have_count(0, timeout=5000)

        # switch to the pool tab: the panel outfits to the POOL column set
        page.locator('[data-test="datafactory-tabs"] .el-tabs__item').filter(has_text="数据池").first.click()
        pool_table = '[data-test="pool-table"]'
        expect(page.locator(pool_table)).to_be_visible(timeout=10000)
        pool_labels = _header_labels(page, pool_table)
        assert "池状态" in pool_labels and "环境" in pool_labels and len(pool_labels) == 7, \
            f"池表列数不受工厂页签改动影响: {pool_labels}"
        assert _pop_row_keys(page) == ["value", "type", "itemKey", "envName", "status", "createdAt"], \
            "pool 页签面板行集应=池列集（切页签即换装）"

        # pool tab hides envName + switches density -> factory tab untouched,
        # same-name columns ('envName'/'status') stay isolated per pane
        pop = _open_pop(page)
        pop.locator('label.el-checkbox[data-test="col-cfg-envName"]').click()
        pop.locator('.el-radio-button').filter(has_text="紧凑").click()
        _close_pop(page)
        expect(_col_header(page, pool_table, "环境")).to_have_count(0, timeout=5000)

        # switch back: factory keeps its envName + its own status hide
        page.locator('[data-test="datafactory-tabs"] .el-tabs__item').filter(has_text="造数脚本").first.click()
        expect(_col_header(page, factory_table, "目标环境")).to_have_count(1, timeout=5000)
        expect(_col_header(page, factory_table, "状态")).to_have_count(0)

        # reload: each pane restores its own persisted view, no cross-pollution
        _land(page, "data-factories", pid)
        f_stored = json.loads(page.evaluate("() => localStorage.getItem('tp-colcfg-dataFactories')"))
        p_stored = json.loads(page.evaluate("() => localStorage.getItem('tp-colcfg-dataPool')"))
        f_vis = {c["key"]: c["visible"] for c in f_stored["columns"]}
        p_vis = {c["key"]: c["visible"] for c in p_stored["columns"]}
        assert f_vis["status"] is False and f_vis["envName"] is True, f"factory 持久化: {f_vis}"
        assert p_vis["envName"] is False and p_vis["status"] is True, f"pool 持久化: {p_vis}"
        assert p_stored["density"] == "small" and f_stored["density"] == "default", "密度按页签独立持久化"
        expect(_col_header(page, factory_table, "状态")).to_have_count(0, timeout=5000)
        assert_no_errors(collectors, "datafactory 页签作用域")
    finally:
        _clear_colcfg(page)
        api_client.delete(f"/projects/{pid}/purge?force=true")


# ── spectrum 3: /cluster/upgrade (plans = mode-1 configurable ledger,
#    manifest/matrix/insight = honest fixed views) ──────────────────────────

def _open_plans_ledger(page):
    """Plans pane lands on the create-wizard by default; the reviewer entry
    button switches the same pane to the existing-plan ledger (planStep=2).
    Precondition step for any ledger assertion — without it the table stays
    rendered but hidden (v-show), which is a fixture gap, not a product bug."""
    btn = page.locator('[data-test="upgrade-plan-open-list"]')
    if btn.is_visible():
        btn.click()


def test_upgrade_center_tab_scope_settings(page, collectors, api_client):
    pass
    plans_table = '[data-test="upgrade-plans-table"]'
    tabs = '[data-test="upgrade-tabs"] .el-tabs__item'
    try:
        inject_token(page)
        _clear_colcfg(page)
        # cluster-level page (no project segment); fresh mount = goto + reload
        page.goto(f"{BASE_URL}/#/cluster/upgrade", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        page.locator(tabs).filter(has_text="升级计划").first.click()
        _open_plans_ledger(page)
        expect(page.locator(plans_table)).to_be_visible(timeout=15000)

        assert _pop_row_keys(page) == ["id", "scope", "targetEngineCodes", "status",
                                       "manifestVersion", "brakeReason", "operator",
                                       "reviewedBy", "createdAt"], "升级计划页签面板行集应=计划台账列集"

        # hide status on the plans tab -> the ledger really drops it
        pop = _open_pop(page)
        pop.locator('label.el-checkbox[data-test="col-cfg-status"]').click()
        _close_pop(page)
        expect(_col_header(page, plans_table, "状态")).to_have_count(0, timeout=5000)

        # each fixed pane: disabled control + tooltip naming its own reason
        for label, hint in (("版本清单", "版本清单页签是横幅"),
                            ("版本全景", "三色对照台账"),
                            ("升级洞察", "列即分析口径")):
            page.locator(tabs).filter(has_text=label).first.click()
            btn = _settings_btn(page)
            expect(btn).to_be_disabled(timeout=5000)
            btn.hover()
            expect(page.locator(".el-popper:visible").filter(has_text=hint).first).to_be_visible(timeout=5000)
            btn.click(force=True)
            expect(page.locator('[data-test="column-settings-pop"]:visible')).to_have_count(0, timeout=2000)

        # switch back: the hidden state belongs to the plans pane and survives
        page.locator(tabs).filter(has_text="升级计划").first.click()
        expect(_col_header(page, plans_table, "状态")).to_have_count(0, timeout=5000)
        expect(_col_header(page, plans_table, "ID")).to_have_count(1)

        # reload: pane-scoped key restores, fixed panes never wrote storage
        page.goto(f"{BASE_URL}/#/cluster/upgrade", wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        page.locator(tabs).filter(has_text="升级计划").first.click()
        _open_plans_ledger(page)
        expect(_col_header(page, plans_table, "状态")).to_have_count(0, timeout=5000)
        stored = page.evaluate("() => localStorage.getItem('tp-colcfg-upgradePlans')")
        assert stored, "升级计划页签持久化键应存在"
        vis = {c["key"]: c["visible"] for c in json.loads(stored)["columns"]}
        assert vis["status"] is False and vis["createdAt"] is True, f"plans 页签持久化: {vis}"
        for fixed_key in ("upgradeManifest", "upgradeMatrix", "upgradeInsight"):
            assert page.evaluate(f"() => localStorage.getItem('tp-colcfg-{fixed_key}')") is None, \
                f"固定视图页签不得写存储键: {fixed_key}"
        assert_no_errors(collectors, "升级中心页签作用域")
    finally:
        _clear_colcfg(page)
