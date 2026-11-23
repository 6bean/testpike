""" RED anchors: deep-link project honesty on the API-test workbench.

Case 1: a dead/unauthorized ?projectId deep link must be named in plain words
(the page must NOT render the "create your first interface-set" empty-state
guide on top of a project that does not exist) and the data area must stay
honestly absent.

Case 2: a valid ?projectId deep link must show the page-side ownership echo
(ProjectOwnerEcho single source) so the user can see which project segment the
lists belong to.

Both anchors are written against the post-fix contract (data-test
'apitest-project-invalid'); they are expected RED today and go GREEN with the
fix segment. Chain: real API project -> SQL bulk-free single collection seed ->
token injection after seeding (auth.projects snapshot) -> deep link.
Known traps: a) source .env first; b) cleanup via project purge force=true;
c) browser runs --headless=new via conftest; d) web-first assertions only.
"""
import re
import subprocess

import pytest
from conftest import BASE_URL, assert_no_errors, inject_token
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(180)

# 99999999 is far above any live tp_project row (max id observed ~1.43M).
DEAD_PROJECT_ID = 99999999
DEAD_ID_CALLOUT = "项目不存在或你无权限"


def _sql(q):
    r = subprocess.run(["docker", "exec", "testpike-postgres", "psql", "-U", "testpike",
                        "-d", "testpike", "-tAc", q],
                       capture_output=True, text=True)
    assert r.returncode == 0, f"sql failed: {r.stderr[:200]}"
    return r.stdout.strip()


def _seed_project_with_collection(api_client, uniq):
    """Real API project + one collection row (deep link needs a visible segment)."""
    r = api_client.post("/projects", json={"name": f"T415回显项目{uniq}", "code": f"T415{uniq[:6].upper()}"})
    assert r.status_code == 200 and r.json().get("code") == "0", f"create project failed: {r.text[:300]}"
    data = r.json()["data"]
    pid = data["id"] if isinstance(data, dict) else data
    _sql("INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags) "
         f"VALUES ({pid}, 0, 'T415回显接口集', '')")
    return pid


def test_t415_dead_deeplink_is_named_and_does_not_induce_create(page, collectors):
    """Case 1: dead ?projectId deep link -> plain-word callout, no create-inducement empty state."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/api-test?projectId={DEAD_PROJECT_ID}", wait_until="domcontentloaded")

    # Anchor A (RED today): the page must name the invalid project in human words.
    callout = page.locator('[data-test="apitest-project-invalid"]')
    expect(callout).to_be_visible(timeout=10000)
    expect(callout).to_contain_text(DEAD_ID_CALLOUT)

    # Anchor B (RED today): the rail must NOT show the empty-state guide that
    # invites creating a collection inside the non-existent project context.
    rail = page.locator('[data-test="apitest-collection-list"]')
    expect(rail).not_to_contain_text(re.compile("还没有接口集"))

    # The dead id must not be echoed as if it were the owner.
    expect(page.locator('[data-test="owner-echo-value"]')).to_have_count(0)

    assert_no_errors(collectors, "T415 dead deep link named + no create inducement")


def test_t415_valid_deeplink_shows_owner_echo(page, collectors, api_client, uniq):
    """Case 2: valid ?projectId deep link -> page-side owner echo = the project name."""
    pid = _seed_project_with_collection(api_client, uniq)
    proj_name = f"T415回显项目{uniq}"
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/api-test?projectId={pid}", wait_until="domcontentloaded")

        # The segment really loaded (collection visible) before judging the echo.
        expect(page.locator('[data-test="apitest-collection-list"]')).to_contain_text(
            "T415回显接口集", timeout=10000)

        # Anchor: ProjectOwnerEcho single source shows the owning project name.
        echo = page.locator('[data-test="owner-echo-value"]')
        expect(echo).to_be_visible(timeout=10000)
        expect(echo).to_have_text(proj_name)

        assert_no_errors(collectors, "T415 valid deep link owner echo")
    finally:
        api_client.delete(f"/projects/{pid}?force=true")
