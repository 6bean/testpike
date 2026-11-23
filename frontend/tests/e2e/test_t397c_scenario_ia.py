""" scenario-surface information-architecture E2E (new page).

Worst-scale iron law: 35 scenarios filed through the real admin:8081 -> core
bridge (no mocks), the list must stay a bounded server-side window (20/page),
instant search filters only the served window and says so honestly, one graph
renders per detail screen, and the deep-link round trip (list -> detail ->
back) rides the space/project context.

Ownership-echo iron law (rules 1-3) on the same surface:
 1. no header context -> create entry disabled + plain "pick in header"
 tooltip + honest no-project block on the page;
 2. context deep-linked -> the create drawer echoes the header project name,
 read-only, zero nested selectors;
 3. the page subtitle always states the list is one project's segment.

S-4 (declared-vs-rendered width, live browser, data-loaded state) is measured
in-page on the real filter-bar so the dead-attribute scanner has a live
first-run witness on this surface.

Data isolation: throwaway project/space with a uuid suffix, purged via direct
SQL in finally (same posture as the internal paged-doors L3 script — never
touches the demo projects).
"""
import re
import subprocess
import time
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = "http://localhost:8081/api/v1"
LIST_URL = f"{BASE_URL}/#/mock/scenarios"
SPACES_URL = f"{BASE_URL}/#/mock/spaces"
SCENARIO_COUNT = 35   # >=30 worst-scale fixture (display-scale iron law)
PAGE_WINDOW = 20      # MockScenarioListView pageSize


def _wb(sid, pid, tab="scenarios"):
    """ AN-4: the scenario list screen = the workbench tab of a space."""
    return f"{SPACES_URL}/{sid}?projectId={pid}&tab={tab}"


def _clear_ctx(page):
    page.evaluate(
        "() => { for (const k of Object.keys(localStorage)) "
        "{ if (k.startsWith('tp_proj_ctx_')) localStorage.removeItem(k); } }")


def _boot(page, token):
    """Real login token injection + app boot (t396b owner-echo posture)."""
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(
        "() => { localStorage.setItem('tp_access_token', '%s'); "
        "localStorage.setItem('tp_onboarding_dismissed', '1'); }" % token)
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)


def _sql(q):
    r = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc", q],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"sql failed: {r.stderr[:200]}"
    return r.stdout.strip()


def _file_scenarios(api, headers, space_id, count):
    ids = []
    for i in range(1, count + 1):
        definition = ('{"states":["new","done"],"initial":"new","scope":"session",'
                      '"transitions":[{"from":"new","to":"done"}]}')
        r = api.post(f"/mock/spaces/{space_id}/scenarios", headers=headers,
                     json={"name": f"scn-{i:02d}", "definitionJson": definition})
        assert str(r.json().get("code")) == "0", f"file scn-{i:02d} failed: {r.text[:160]}"
        ids.append(r.json()["data"])
    return ids


def test_scenario_ia_worst_scale_window_search_detail_and_owner_echo(page, collectors):
    token = api_login("admin", ADMIN_PASSWORD)
    headers = {"Authorization": f"Bearer {token}"}
    suffix = uuid.uuid4().hex[:6].upper()
    pname = f"T397c场景{suffix}"
    scenario_ids = []
    pid = sid = None

    with httpx.Client(base_url=API, timeout=20) as api:
        r = api.post("/projects", headers=headers,
                     json={"name": pname, "code": f"T397C{suffix}"})
        assert r.status_code == 200, f"create project failed: {r.text[:200]}"
        pid = r.json()["data"]
        r = api.post("/mock/spaces", headers=headers,
                     json={"projectId": pid, "name": "t397c space",
                           # space keys ride the /mocks/ path: lowercase spectrum
                           "spaceKey": f"t397c{suffix.lower()}", "basePathMode": "prefix",
                           "authMode": "none", "priority": 10})
        assert str(r.json().get("code")) == "0", f"create space failed: {r.text[:200]}"
        sid = r.json()["data"]
        scenario_ids = _file_scenarios(api, headers, sid, SCENARIO_COUNT)

    try:
        _boot(page, token)

        # --- iron rule 2 (context-less): entry disabled + pointer + honest
        # block. AN-4: the retired /mock/scenarios bookmark lands on the
        # spaces list (tab intent carried), so the disabled entry under test
        # is the list page's own create button. ---
        _clear_ctx(page)
        page.goto(LIST_URL, wait_until="domcontentloaded")
        expect(page).to_have_url(re.compile(r"/#/mock/spaces\?"), timeout=15000)
        btn = page.locator('[data-test="mock-spaces-new"]')
        expect(btn).to_be_visible(timeout=15000)
        expect(btn).to_be_disabled()
        expect(page.locator('[data-test="mock-spaces-no-project"]')).to_be_visible()
        btn.hover()
        tip = page.get_by_role("tooltip").filter(has_text="顶栏")
        expect(tip.first).to_be_visible(timeout=8000)

        # --- deep-link into the segment (fresh reload re-fetches visible fleet) ---
        page.goto(_wb(sid, pid), wait_until="domcontentloaded")
        page.reload(wait_until="domcontentloaded")
        rows = page.locator('[data-test="mock-scenarios-row"]')
        expect(rows).to_have_count(PAGE_WINDOW, timeout=20000)
        # iron rule 3: the segment is visible — AN-4 moved the naming to
        # the workbench header (tab host), the embedded list keeps no shell
        expect(page.locator('[data-test="mock-workbench-page"] .page-title')).to_contain_text(
            "t397c space", timeout=10000)

        # --- bounded window + truthful pager at worst scale ---
        pager = page.locator('[data-test="mock-scenarios-pager"]')
        expect(pager).to_be_visible()
        expect(pager).to_contain_text(str(SCENARIO_COUNT))
        pager.locator(".el-pager li", has_text="2").first.click()
        expect(rows).to_have_count(SCENARIO_COUNT - PAGE_WINDOW, timeout=20000)

        # --- instant search filters the served window only, with a note ---
        search = page.locator('[data-test="mock-scenarios-search"]')
        search.fill("scn-05")   # lives in window 1; window 2 must honestly show no match
        expect(rows).to_have_count(0, timeout=10000)
        pager.locator(".el-pager li", has_text="1").first.click()
        expect(rows).to_have_count(1, timeout=10000)
        expect(page.locator('[data-test="mock-scenarios-search-note"]')).to_be_visible()
        search.fill("")
        expect(rows).to_have_count(PAGE_WINDOW, timeout=10000)

        # --- S-4 live witness: declared vs rendered width on the real filter-bar ---
        s4 = page.evaluate(
            r"""() => {
                const out = {measured: 0, violations: []};
                for (const bar of document.querySelectorAll('.filter-bar, .header-actions')) {
                    for (const el of bar.children) {
                        const rect = el.getBoundingClientRect();
                        if (!rect.width) continue;
                        const declared = el.getAttribute('width')
                            || (el.style.width || '').match(/^(\d+(?:\.\d+)?)px$/)?.[1];
                        if (declared == null) continue;
                        out.measured += 1;
                        if (Math.abs(rect.width - parseFloat(declared)) > 1) {
                            out.violations.push({
                                dt: el.getAttribute('data-test'),
                                declared: parseFloat(declared),
                                rendered: Math.round(rect.width * 10) / 10,
                            });
                        }
                    }
                }
                return out;
            }""")
        assert s4["measured"] >= 1, (
            f"S-4 probe never saw declared widths in the data-loaded state: {s4}")
        assert not s4["violations"], f"S-4 declared-vs-rendered violations: {s4}"

        # --- row click -> one-graph detail screen, context rides in the subtitle ---
        rows.first.click()
        detail = page.locator('[data-test="mock-scenario-detail-page"]')
        expect(detail).to_be_visible(timeout=15000)
        expect(page.locator('[data-test="mock-scenario-detail-graph-card"]')).to_have_count(1)
        # the svg element itself carries the anchor (pit 23 family) — assert on it
        graph = page.locator('svg[data-test="mock-scenario-detail-graph"]')
        expect(graph).to_have_count(1, timeout=10000)
        assert graph.locator("[data-node]").count() == 2, "detail graph node count != states"
        assert graph.locator('[data-edge="solid"]').count() == 1, "detail graph edge count"
        expect(page.locator('[data-test="mock-scenario-detail-subtitle"]')).to_contain_text(
            pname, timeout=10000)
        expect(detail.locator(".page-title")).to_contain_text("scn-01")

        # --- back door rides the same segment (no dead ends, window restored).
        # AN-4: detail back pushes the workbench scenarios tab. ---
        page.locator('[data-test="mock-scenario-detail-back"]').click()
        expect(page.locator('[data-test="mock-scenarios-page"]')).to_be_visible(timeout=15000)
        assert "tab=scenarios" in page.url and f"projectId={pid}" in page.url, page.url
        expect(rows).to_have_count(PAGE_WINDOW, timeout=15000)

        # --- iron rule 1: create drawer echoes the header project, read-only.
        # AN-4: on the workbench tab the entry is the embedded list's own
        # create button (header-actions survives the embedded contract). ---
        btn = page.locator('[data-test="mock-scenarios-new"]')
        expect(btn).to_be_enabled()
        btn.click()
        echo = page.locator('[data-test="owner-echo"]')
        expect(echo).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="owner-echo-value"]')).to_have_text(pname)
        expect(echo.locator(".el-select")).to_have_count(0)

        assert_no_errors(collectors, "T397c scenario IA surface")
    finally:
        _clear_ctx(page)
        stmts = []
        if scenario_ids:
            stmts.append("DELETE FROM tp_mock_scenario WHERE id IN ("
                         + ",".join(str(i) for i in scenario_ids) + ");")
        if sid is not None:
            stmts.append(f"DELETE FROM tp_mock_hit_log WHERE space_id = {sid};")
            stmts.append(f"DELETE FROM tp_mock_space WHERE id = {sid};")
        if pid is not None:
            stmts.append(f"DELETE FROM tp_user_project WHERE project_id = {pid};")
            stmts.append(f"DELETE FROM tp_project WHERE id = {pid};")
        if stmts:
            _sql(" ".join(stmts))
        residue = _sql(f"SELECT count(*) FROM tp_mock_space WHERE space_key LIKE 't397c%';")
        assert residue == "0", f"fixture residue: spaces={residue}"
