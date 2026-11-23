# -*- coding: utf-8 -*-
"""SE1 searchability digestion E2E .

Proves the selectors fixed by the site-wide selector-searchability audit behave
under worst-scale data instead of only compiling `filterable`: every target
dropdown is fed a real-API fixture well past the "stop the eye from hunting"
threshold (42 tags / 9 environments / 11 pool tags) and one typed substring
must collapse it to the single needle option (1 search / <=2 clicks reachable).

Targets: case-filter-tag (CaseListView), batch-form-env + batch-form-pool
(create dialog), batch-schedule-env (template schedule dialog),
batch-reuse-env (template reuse dialog).

 the three pages digested in the
previous segment — project create dialog template picker and project edit
dialog owner pickers prove one-search-collapse under 12 seeded options;
the skill list page proves its fixed 5-value enums are honestly bounded
(exact option count + the machine-checkable exemption reason on the DOM).

Real browser + real backend chain (admin 8081 -> Feign -> core 8083 -> real
DB; no mocks). Three-class zero tolerance (4xx/5xx, console errors/warnings,
browser Issues) via assert_no_errors. Data isolation: unique scratch project,
purged in finally.
"""
import re

from playwright.sync_api import expect

from conftest import (
    BASE_URL,
    assert_no_errors,
    inject_token,
    nav_to,
    pick_project_in_header,
    purge_project,
    row_cmd,
    seed_project,
)

# Fixture scale (audit law: worst case = 50~200 rows; 30+ options is the
# searchability trip line). Tags ship 42 options past the trip line. Envs are
# domain-capped: ProjectEnvServiceImpl enforces one env per type per project
# with type in 1..4, so the honest worst scale of the env dropdown is the full
# 4-type set — the searchability contract (one typed substring collapses to
# the exact needle) is still exercised on that ceiling.
TAG_FILLERS = 30
POOL_FILLERS = 10

OPTS = ".el-select-dropdown:visible .el-select-dropdown__item:visible"
# The wizard carries several selects whose poppers teleports to body; a
# page-level OPTS match can bind the previous select's popper while it is
# mid-collapse (measured 2026-11-04: the env option slipped into the engine
# readout). Anchor each pin to its own popper through the dedicated
# popper-class, the same convention as the project-owner pickers.
PW_ENV_OPTS = ".tp-pw-env-popper .el-select-dropdown__item:visible"
PW_ENGINE_OPTS = ".tp-pw-engine-popper .el-select-dropdown__item:visible"
CANCEL_RE = re.compile("取消|Cancel")


def _seed_digest_project(api_client, uniq):
    """Create the scratch project with worst-scale tags/envs + one batch template."""
    name = f"选择器{uniq}"
    pid = seed_project(api_client, uniq, "SDIG", display_name=name)
    s6 = uniq[:6]
    tag_needle = f"针标{s6}"
    pool_needle = f"池针{s6}"
    env_needle = f"环针{s6}"
    r = api_client.post("/tags", json={"projectId": pid, "name": tag_needle, "type": 4})
    assert r.json().get("code") == "0", f"create tag failed: {r.text[:200]}"
    for i in range(TAG_FILLERS):
        r = api_client.post("/tags", json={"projectId": pid, "name": f"标签{i:02d}{s6}", "type": 4})
        assert r.json().get("code") == "0", f"create filler tag failed: {r.text[:200]}"
    r = api_client.post("/tags", json={"projectId": pid, "name": pool_needle, "type": 7})
    assert r.json().get("code") == "0", f"create pool tag failed: {r.text[:200]}"
    for i in range(POOL_FILLERS):
        r = api_client.post("/tags", json={"projectId": pid, "name": f"池伴{i:02d}{s6}", "type": 7})
        assert r.json().get("code") == "0", f"create filler pool tag failed: {r.text[:200]}"
    env_id = None
    # Full 4-type set (domain ceiling): needle is stage, peers dev/test/prod.
    for label, etype in ((env_needle, 3), (f"环开{s6}", 1), (f"环测{s6}", 2), (f"环生{s6}", 4)):
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": label, "envType": etype,
            "baseUrl": f"https://sdig-{uniq[:6]}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"create env failed: {r.text[:200]}"
        data = r.json()["data"]
        if label == env_needle:
            env_id = data["id"] if isinstance(data, dict) else data
    case_ids = []
    for title in ("摘要用例A", "摘要用例B"):
        r = api_client.post("/cases", json={
            "projectId": pid, "title": f"{title}{s6}", "precondition": "无",
            "expectedResult": "符合预期", "priority": 1, "confidence": 2,
            "endType": "web", "status": 3,
            "steps": [{"stepNo": 1, "description": "步骤", "isAssert": 1}]})
        assert r.json().get("code") == "0", f"create case failed: {r.text[:300]}"
        case_ids.append(r.json()["data"]["id"])
    r = api_client.post("/batches", json={"projectId": pid, "name": f"摘要批次{s6}",
                                          "envId": env_id, "caseIds": case_ids})
    assert r.json().get("code") == "0", f"create batch failed: {r.text[:300]}"
    data = r.json()["data"]
    batch_id = data["id"] if isinstance(data, dict) else data
    r = api_client.post(f"/batches/{batch_id}/save-as-template",
                        json={"templateName": f"摘要模板{s6}"})
    assert r.json().get("code") == "0", f"save-as-template failed: {r.text[:300]}"
    return pid, name, {"tag": tag_needle, "pool": pool_needle, "env": env_needle}


def _filter_to_needle(page, anchor, total_count, needle_text, type_text):
    """Open a selector, prove worst scale, then prove one search collapses it.

    Hard assertions on exact counts (audit law: no conditional wrappers):
    `total_count` visible options before typing; exactly one matching the
    needle after typing `type_text`; the needle is then clicked, pinning the
    "target option reachable in <=2 clicks / 1 search" contract.
    """
    page.locator(f'[data-test="{anchor}"]').click()
    opts = page.locator(OPTS)
    expect(opts).to_have_count(total_count, timeout=10000)
    page.keyboard.type(type_text)
    expect(opts).to_have_count(1, timeout=5000)
    expect(opts.first).to_contain_text(needle_text)
    opts.first.click()


def test_case_tag_filter_searchable_under_worst_scale(page, collectors, api_client, uniq):
    """Case list tag filter: 42 options render, one search reaches the needle."""
    pid, pname, needles = _seed_digest_project(api_client, uniq)
    try:
        inject_token(page)
        nav_to(page, "用例管理", "/cases", '[data-test="case-page"]', submenu="设计用例")
        pick_project_in_header(page, pname)
        expect(page.locator('[data-test="case-page"]')).to_be_visible(timeout=10000)
        total_tags = TAG_FILLERS + POOL_FILLERS + 2  # 42 options in one dropdown
        _filter_to_needle(page, "case-filter-tag", total_tags, needles["tag"], "针标")
        assert_no_errors(collectors, "case tag filter searchability")
    finally:
        purge_project(api_client, pid)


def test_batch_env_and_pool_selectors_searchable_under_worst_scale(page, collectors, api_client, uniq):
    """Batch create/schedule/reuse dialogs: env x3 + pool selectors all filter."""
    pid, pname, needles = _seed_digest_project(api_client, uniq)
    try:
        inject_token(page)
        nav_to(page, "执行批次", "/batches", '[data-test="batch-table"]', submenu="执行测试")
        pick_project_in_header(page, pname)
        total_envs = 4  # domain ceiling: one env per type, types 1..4

        # Create dialog: env + pool selectors.
        page.locator('[data-test="batch-create-btn"]').click()
        dlg = page.locator('[data-test="batch-create-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        _filter_to_needle(page, "batch-form-env", total_envs, needles["env"], "环针")
        _filter_to_needle(page, "batch-form-pool", POOL_FILLERS + 1, needles["pool"], "池针")
        dlg.locator("button", has_text=CANCEL_RE).first.click()

        # Template dialog -> schedule dialog (env selector gated behind the
        # enable switch: disabled until schedule enabled, openSchedule starts
        # a fresh template at enabled=0).
        page.locator('[data-test="batch-template-btn"]').click()
        tpl_dlg = page.locator('[data-test="batch-template-dialog"]')
        expect(tpl_dlg).to_be_visible(timeout=10000)
        expect(tpl_dlg.locator('[data-test="batch-template-table"]')).to_contain_text(
            f"摘要模板{uniq[:6]}", timeout=10000)
        tpl_dlg.locator('[data-test="batch-tpl-schedule-btn"]').first.click()
        sch_dlg = page.locator('[data-test="batch-schedule-dialog"]')
        expect(sch_dlg).to_be_visible(timeout=10000)
        sch_dlg.locator('[data-test="batch-schedule-enable"]').click()
        _filter_to_needle(page, "batch-schedule-env", total_envs, needles["env"], "环针")
        sch_dlg.locator("button", has_text=CANCEL_RE).first.click()

        # Template dialog -> reuse dialog: env selector.
        tpl_dlg.locator('[data-test="batch-tpl-reuse-btn"]').first.click()
        reu_dlg = page.locator('[data-test="batch-reuse-dialog"]')
        expect(reu_dlg).to_be_visible(timeout=10000)
        _filter_to_needle(page, "batch-reuse-env", total_envs, needles["env"], "环针")
        reu_dlg.locator("button", has_text=CANCEL_RE).first.click()

        assert_no_errors(collectors, "batch env/pool selector searchability")
    finally:
        purge_project(api_client, pid)


# --------------------------------------------------------------------------
# segment 2 : project dialog pickers + perf wizard env + skill enums.
# --------------------------------------------------------------------------

TEMPLATE_TOTAL = 12  # 11 fillers + 1 needle, past the >10 searchability trip line
OWNER_TOTAL = 12     # 11 filler members + 1 needle member (floored; the project
                     # creator may ride along, so exactness lives in the collapse)


def _mk_scratch_template(api, name):
    """Create one valid H7 template over the real chain (admin only write path)."""
    payload = {
        "name": name,
        "industry": "零售",
        "content": {
            "skillIds": [],
            "tags": [{"name": "冒烟", "type": 1}],
            "envs": [{"name": "示例测试环境", "envType": 2,
                      "baseUrl": "http://127.0.0.1:8899/login"}],
            "roleHints": ["建议配置：测试负责人 1 名"],
        },
    }
    r = api.post("/project-templates", json=payload)
    assert r.json().get("code") == "0", f"create template failed: {r.text[:200]}"
    data = r.json()["data"]
    return data if isinstance(data, int) else data["id"]


def _collapse_to_needle(page, anchor, floor_count, type_text, needle_text):
    """Open a select, prove the worst-scale floor, then one search -> exactly 1.

    Floor is a hard web-first assertion on the floor_count-th rendered option
    (never a conditional skip); the collapse asserts exact count 1 + needle
    text, pinning the "<=2 clicks / 1 search reachable" contract.
    """
    page.locator(f'[data-test="{anchor}"]').click()
    opts = page.locator(OPTS)
    # Floor: the (floor_count)-th option must exist and render — a web-first
    # hard assertion equivalent to "at least floor_count options", never a
    # conditional skip (audit law 5d).
    expect(opts.nth(floor_count - 1)).to_be_visible(timeout=15000)
    page.keyboard.type(type_text)
    expect(opts).to_have_count(1, timeout=5000)
    expect(opts.first).to_contain_text(needle_text)
    opts.first.click()


def test_project_dialog_selectors_searchable_under_worst_scale(
        page, collectors, api_client, uniq):
    """Project create/edit dialogs: template x12 floor and owner x12 floor,
    each collapsed to the single needle by one typed substring."""
    s6 = uniq[:6]
    tpl_ids = [_mk_scratch_template(api_client, f"模针{s6}")]
    tpl_ids += [_mk_scratch_template(api_client, f"模伴{i:02d}{s6}")
                for i in range(TEMPLATE_TOTAL - 1)]
    pid = seed_project(api_client, uniq, "SDG2", display_name=f"选择二{uniq}")
    user_ids = []
    needle_disp = "针负"
    uid = api_client.post("/users", json={
        "username": f"owneedle{s6}", "password": "E2e!Passw0rd",
        "displayName": needle_disp, "email": f"owneedle{s6}@tp-local.test",
        "phone": None, "role": 1}).json()["data"]
    user_ids.append(uid if isinstance(uid, int) else uid["id"])
    for i in range(OWNER_TOTAL - 1):
        uid = api_client.post("/users", json={
            "username": f"ownfill{i:02d}{s6}", "password": "E2e!Passw0rd",
            "displayName": f"员伴{i:02d}", "email": f"ownfill{i:02d}{s6}@tp-local.test",
            "phone": None, "role": 1}).json()["data"]
        user_ids.append(uid if isinstance(uid, int) else uid["id"])
    for u in user_ids:
        r = api_client.post(f"/projects/{pid}/members", json={"userId": u, "role": 1})
        assert r.json().get("code") == "0", f"add member failed: {r.text[:200]}"
    try:
        inject_token(page)
        nav_to(page, "项目管理", "/projects", '[data-test="project-table"]',
               submenu="系统管理")
        # Create dialog -> template picker (system-wide H7 catalogue).
        page.locator('[data-test="project-create"]').click()
        dlg = page.locator('[data-test="project-dialog"]')
        expect(dlg).to_be_visible(timeout=10000)
        _collapse_to_needle(page, "project-template-select", TEMPLATE_TOTAL,
                            "模针", f"模针{s6}")
        dlg.locator("button", has_text=CANCEL_RE).first.click()

        # Edit dialog of the scratch project -> both owner pickers (source =
        page.locator('[data-test="project-search"]').fill(f"选择二{uniq}")
        page.locator('[data-test="project-search"]').press("Enter")
        row = page.locator('[data-test="project-table"] tbody tr',
                           has_text=f"选择二{uniq}").first
        expect(row).to_be_visible(timeout=10000)
        row_cmd(page, row, "edit")
        expect(dlg).to_be_visible(timeout=10000)
        _collapse_to_needle(page, "project-test-owner-select", OWNER_TOTAL,
                            needle_disp, f"owneedle{s6}")
        _collapse_to_needle(page, "project-dev-owner-select", OWNER_TOTAL,
                            needle_disp, f"owneedle{s6}")
        dlg.locator("button", has_text=CANCEL_RE).first.click()
        assert_no_errors(collectors, "project dialog selector searchability")
    finally:
        for tid in tpl_ids:
            api_client.delete(f"/project-templates/{tid}")
        for u in user_ids:
            api_client.delete(f"/users/{u}")
        purge_project(api_client, pid)


def test_perf_wizard_env_selector_searchable_under_domain_ceiling(
        page, collectors, api_client, uniq):
    """Perf scenario wizard step 1: env select is filterable and one search
    collapses the full 4-type domain ceiling to the needle env."""
    s6 = uniq[:6]
    env_needle = f"环针{s6}"
    pid = seed_project(api_client, uniq, "PWEV", display_name=f"选择环{uniq}")
    for label, etype in ((env_needle, 3), (f"环开{s6}", 1), (f"环测{s6}", 2),
                         (f"环生{s6}", 4)):
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": label, "envType": etype,
            "baseUrl": f"https://pwev-{s6}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"create env failed: {r.text[:200]}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
        page.locator('[data-test="perf-create-btn"]').click()
        expect(page.locator('[data-test="perf-wizard"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="pw-env"]').click()
        opts = page.locator(OPTS)
        expect(opts).to_have_count(4, timeout=10000)
        page.keyboard.type("环针")
        expect(opts).to_have_count(1, timeout=5000)
        expect(opts.first).to_contain_text(env_needle)
        opts.first.click()
        page.locator('[data-test="perf-wizard"] .el-dialog__headerbtn').click()
        expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)
        assert_no_errors(collectors, "perf wizard env searchability")
    finally:
        purge_project(api_client, pid)


def test_skill_fixed_enum_selectors_are_honestly_bounded(page, collectors):
    """Skill list exemption proof: both filter enums render their fixed
    5-option catalogue exactly (bounded source), and each select carries the
    machine-checkable exemption reason on the DOM."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/skills", wait_until="domcontentloaded")
    expect(page.locator('[data-test="skill-list-page"]')).to_be_visible(timeout=15000)
    for anchor in ("skill-type-select", "skill-status-select"):
        sel = page.locator(f'[data-test="{anchor}"]')
        sel.click()
        expect(page.locator(OPTS)).to_have_count(5, timeout=5000)
        page.keyboard.press("Escape")
        reason = sel.get_attribute("data-se1-exempt")
        assert reason, f"{anchor}: exemption reason missing on the DOM"
    assert_no_errors(collectors, "skill fixed enum bounded selectors")


# tail : the two wizard selectors that had no assertion —
# pw-engine (remote catalogue door, lazy first frame) and pw-schedule-env
# (schedule section on the SLA step, shares the project env source).

def _open_wizard_on_pressure_step(page, pid, s6):
    """Drive the perf scenario wizard to the pressure step.

    The step gate mirrors the server save rule (project + env + name + one
    http.request url), so the preamble fills exactly those required fields
    and never bypasses the flow."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/perf?projectId={pid}", wait_until="domcontentloaded")
    expect(page.locator('[data-test="perf-page"]')).to_be_visible(timeout=15000)
    page.locator('[data-test="perf-create-btn"]').click()
    expect(page.locator('[data-test="perf-wizard"]')).to_be_visible(timeout=10000)
    page.locator('[data-test="pw-name"]').fill(f"向导尾{s6}")
    page.locator('[data-test="pw-env"]').click()
    expect(page.locator(PW_ENV_OPTS).first).to_be_visible(timeout=10000)
    page.locator(PW_ENV_OPTS).first.click()
    url = page.locator('[data-test="pw-step-url"]').first
    expect(url).to_be_visible(timeout=10000)
    url.fill("https://pwtail.tp-local.test/api/ping")
    page.locator('[data-test="pw-next"]').click()
    expect(page.locator('[data-test="pw-step-pressure"]')).to_be_visible(timeout=10000)
    # The env popper is teleported to body; the pressure step must see it
    # fully collapsed so a later page-level readout cannot bind its residue.
    # EP stamps the class on the popper wrapper and the inner dropdown (two
    # nodes), so count the visible ones instead of a strict single locator.
    expect(page.locator(".tp-pw-env-popper:visible")).to_have_count(0, timeout=10000)


def test_perf_wizard_engine_selector_searchable_after_lazy_first_frame(
        page, collectors, api_client, uniq):
    """Pressure step engine pin: options arrive from the remote catalogue
    door, so the window waits for the first frame instead of asserting a
    hardcoded size (the server domain is the single source), then one
    full-label search collapses the list to exactly the needle."""
    s6 = uniq[:6]
    pid = seed_project(api_client, uniq, "PWEN", display_name=f"向导引擎{uniq}")
    env = api_client.post(f"/projects/{pid}/envs", json={
        "name": f"环启{s6}", "envType": 1,
        "baseUrl": f"https://pwen-{s6}.tp-local.test", "endTypes": "web"})
    assert env.json().get("code") == "0", f"create env failed: {env.text[:200]}"
    try:
        _open_wizard_on_pressure_step(page, pid, s6)
        page.locator('[data-test="pw-engine"]').click()
        opts = page.locator(PW_ENGINE_OPTS)
        expect(opts.first).to_be_visible(timeout=10000)  # lazy first frame
        labels = [opts.nth(i).inner_text().strip() for i in range(opts.count())]
        assert len(labels) >= 2, f"catalogue door looks degenerate: {labels}"
        needle = labels[0]
        page.keyboard.type(needle)
        expect(opts).to_have_count(1, timeout=5000)
        expect(opts.first).to_contain_text(needle)
        opts.first.click()
        # The pin must stick on the control, not stay a phantom click.
        expect(page.locator('[data-test="pw-engine"]')).to_contain_text(needle,
                                                                        timeout=5000)
        page.locator('[data-test="perf-wizard"] .el-dialog__headerbtn').click()
        expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)
        assert_no_errors(collectors, "perf wizard engine lazy catalogue search")
    finally:
        purge_project(api_client, pid)


def test_perf_wizard_schedule_env_selector_searchable_on_sla_step(
        page, collectors, api_client, uniq):
    """SLA step schedule env pin: the select mounts inside the schedule
    section and shares the project env source, so under the 4-type domain
    ceiling one search on the needle label must collapse 4 options to 1."""
    s6 = uniq[:6]
    env_needle = f"环针{s6}"
    pid = seed_project(api_client, uniq, "PWSL", display_name=f"向导调度{uniq}")
    for label, etype in ((env_needle, 3), (f"环开{s6}", 1), (f"环测{s6}", 2),
                         (f"环生{s6}", 4)):
        r = api_client.post(f"/projects/{pid}/envs", json={
            "name": label, "envType": etype,
            "baseUrl": f"https://pwsl-{s6}.tp-local.test", "endTypes": "web"})
        assert r.json().get("code") == "0", f"create env failed: {r.text[:200]}"
    try:
        _open_wizard_on_pressure_step(page, pid, s6)
        page.locator('[data-test="pw-next"]').click()
        expect(page.locator('[data-test="pw-step-sla"]')).to_be_visible(timeout=10000)
        page.locator('[data-test="pw-schedule-env"]').click()
        opts = page.locator(OPTS)
        expect(opts).to_have_count(4, timeout=10000)
        page.keyboard.type("环针")
        expect(opts).to_have_count(1, timeout=5000)
        expect(opts.first).to_contain_text(env_needle)
        opts.first.click()
        page.locator('[data-test="perf-wizard"] .el-dialog__headerbtn').click()
        expect(page.locator('[data-test="perf-wizard"]')).to_be_hidden(timeout=10000)
        assert_no_errors(collectors, "perf wizard schedule env searchability")
    finally:
        purge_project(api_client, pid)
