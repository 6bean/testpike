"""Y-wave golden journey: one user path through every shipped program-lens surface.

The flow walks the real browser screens end to end:
create a program -> attach two projects -> see the published asset in the
asset pool -> author a cross-project journey on the ordered-segment form ->
start a run (expansion into per-project batches) -> read the timeline
traffic lights -> the journey owner signs the terminal run off, while a
second plain user is refused on the same door (signing right = owner only).

Signing reality (documented, not mocked): a fully green run needs a live
executor claiming the launched batches, and this lane runs none. The run
therefore expands for real (segment 1 gets a batch), the params-less asset
segment then fails on the shipped PARAMS_MISSING rule, downstream segments
are skipped, and the run reaches the terminal state the product allows the
owner to sign (the gate is "owner + terminal"; a running run is refused).
Success-side signing stays on the main E2E lane with a live executor.

All data goes through the real admin API and real UI actions — no SQL
writes, no mocks. Projects are purged by the fixture (single-source helper,
R40); the lens delete is first proved to refuse while journeys still
reference it (conflict business code, never a generic 500), then the
fixture-side journey rows drop through the conftest SQL door (children
first) and the program deletes cleanly. Teardown SQL is not an exercised
product surface — no journey delete endpoint ships in this wave.

Entry paths (user view): /#/programs, /#/asset-pool, /#/journeys,
/#/journeys/{id}, /#/journeys/runs/{runId}.
"""

import json

import httpx
from urllib.parse import quote
from playwright.sync_api import Page, expect

from conftest import (
    API_BASE,
    BASE_URL,
    api_login,
    assert_no_errors,
    mk_tester,
    publish_shared_asset,
    purge_project,
    seed_cases,
    seed_project,
    sql_query,
)

ASSET_NAME = "旅程夹具发布件"


def _program_name(uniq: str) -> str:
    return f"黄金旅程项目集{uniq}"


def _purge_journey(journey_id: int) -> None:
    """Fixture-side journey rows, children first (foreign key order).

    Teardown only: this wave ships no journey delete endpoint, so the rows
    the UI authored are cleared through the conftest single-source SQL door.
    """
    sql_query(
        f"DELETE FROM tp_journey_run_segment WHERE run_id IN "
        f"(SELECT id FROM tp_journey_run WHERE journey_id={journey_id})")
    sql_query(f"DELETE FROM tp_journey_segment WHERE journey_id={journey_id}")
    sql_query(f"DELETE FROM tp_journey_run WHERE journey_id={journey_id}")
    sql_query(f"DELETE FROM tp_journey WHERE id={journey_id}")


def _segment_row_count(page: Page) -> int:
    return page.locator('[data-test="journey-segment-table"] tbody tr').count()


def _add_segment(page: Page, form, seq: int, project_name: str, kind_index: int,
                 *, asset_name: str | None = None, case_id: int | None = None,
                 params: str | None = None):
    """Fill the ordered-segment form exactly as a user would, one row per save."""
    # Element Plus attaches a data-test attribute to the component root, not to the
    # inner editable control: fill must reach the descendant input/textarea.
    form.locator('[data-test="journey-form-seq"] input').first.fill(str(seq))
    form.locator('[data-test="journey-form-project"]').click()
    option = page.locator(".el-select-dropdown__item:visible").filter(has_text=project_name).first
    option.wait_for(state="visible")
    option.click()
    # radio order is fixed in the view (asset first, case second): position,
    # not locale text, keeps the step stable across translations.
    form.locator('[data-test="journey-form-kind"]').locator(".el-radio").nth(kind_index).click()
    if kind_index == 0:
        form.locator('[data-test="journey-form-asset"]').click()
        page.locator(".el-select-dropdown__item:visible").filter(has_text=asset_name).first.click()
    else:
        form.locator('[data-test="journey-form-case"] input').first.fill(str(case_id))
    # The params box differs: el-input passes the attribute through to the inner
    # <textarea> element, so the root selector IS the editable control here.
    form.locator('[data-test="journey-form-params"]').fill(params or "")
    form.locator('[data-test="journey-segment-add"]').click()
    expect(page.locator('[data-test="journey-segment-table"] tbody tr')).to_have_count(seq)


def test_golden_journey_program_to_signoff(
    logged_in, page: Page, collectors, api_client, uniq
):
    program_name = _program_name(uniq)
    program_id = None
    journey_id = None
    pa = pb = None
    try:
        pa_name = f"Y金线A{uniq}"
        pb_name = f"Y金线B{uniq}"
        pa = seed_project(api_client, uniq, "ygolden-a", display_name=pa_name)
        pb = seed_project(api_client, uniq, "ygolden-b", display_name=pb_name)
        # Batch start refuses a launch without an execution environment and the
        # run expansion takes the env strictly from params_override_json.envId
        # (core JourneyRunServiceImpl.launchSegment; same contract as the L3
        # crossdomain spectrum, which seeds the env and pins its id per segment).
        # The case segments below therefore carry the seeded env id explicitly.
        env_resp = api_client.post(f"/projects/{pb}/envs", json={
            "name": f"Y黄金旅程环境{uniq}", "envType": 2,
            "baseUrl": "http://localhost:8083", "endTypes": "web"})
        assert env_resp.status_code == 200, env_resp.text[:200]
        env_id = env_resp.json()["data"]
        publish_shared_asset(api_client, pa, "case_fragment", ASSET_NAME, "[]")
        seed_cases(api_client, pb, f"{uniq}-ycase", 1)
        listed = api_client.get(f"/cases?projectId={pb}&page=1&size=10")
        assert listed.status_code == 200, listed.text[:200]
        case_id = listed.json()["data"]["list"][0]["id"]

        # 1) create the program on the real list page
        page.goto(f"{BASE_URL}/#/programs", wait_until="domcontentloaded")
        expect(page.locator('[data-test="program-list-page"]')).to_be_visible()
        page.locator('[data-test="program-create-btn"]').click()
        dialog = page.locator('[data-test="program-create-dialog"]')
        dialog.locator('[data-test="program-form-name"]').first.fill(program_name)
        dialog.locator('[data-test="program-form-submit"]').click()
        expect(page.locator('[data-test="program-table"]')).to_be_visible()
        expect(page.get_by_text(program_name).first).to_be_visible()
        prog = api_client.get(f"/programs?keyword={quote(program_name)}&page=1&size=10")
        # GET /programs mirrors the lens contract: data is a plain array, not a page object
        program_id = next(r["id"] for r in prog.json()["data"] if r["name"] == program_name)

        # The drawer picker reads auth.projects captured at loadMe; projects
        # seeded after login land in the snapshot only after a page reload
        # (precedent W1C: reload /users/me then retry).
        page.reload(wait_until="domcontentloaded")
        expect(page.locator('[data-test="program-list-page"]')).to_be_visible()

        # 2) attach both projects through the drawer (membership = the lens grants)
        #    scope the action to the freshly created row: other lens rows exist
        row = page.locator('[data-test="program-table"] .el-table__row', has_text=program_name)
        row.locator('[data-test="program-projects-btn"]').click()
        # the lens drawer carries its own data-test; the page also mounts
        # ai-chat-dock and help-drawer el-drawer shells (strict-mode collision)
        drawer = page.locator('[data-test="program-drawer-projects"]')
        expect(drawer).to_be_visible()
        for project_name in (pa_name, pb_name):
            drawer.locator('[data-test="program-attach-select"]').click()
            opt = page.locator(".el-select-dropdown__item:visible").filter(has_text=project_name).first
            opt.wait_for(state="visible")
            opt.click()
            drawer.locator('[data-test="program-attach-btn"]').click()
            expect(drawer.get_by_text(project_name).first).to_be_visible()

        # 3) the published head is discoverable on the asset pool page
        page.goto(f"{BASE_URL}/#/asset-pool", wait_until="domcontentloaded")
        expect(page.get_by_text(ASSET_NAME).first).to_be_visible()

        # 4) author the journey on the wall; program/owner fields are
        #    read-only echoes (no second picker on create dialogs)
        page.goto(f"{BASE_URL}/#/journeys", wait_until="domcontentloaded")
        page.locator('[data-test="journey-program-select"]').click()
        opt = page.locator(".el-select-dropdown__item:visible").filter(has_text=program_name).first
        opt.wait_for(state="visible")
        opt.click()
        page.locator('[data-test="journey-create"]').click()
        create_dialog = page.locator('[data-test="journey-create-dialog"]')
        create_dialog.locator('[data-test="journey-create-name"]').first.fill(f"黄金旅程{uniq}")
        expect(create_dialog.locator('[data-test="journey-create-program-echo"]')).to_be_visible()
        expect(create_dialog.locator('[data-test="journey-create-owner-echo"]')).to_be_visible()
        create_dialog.locator('[data-test="journey-create-submit"]').click()
        page.wait_for_url("**/#/journeys/*")
        journey_id = int(page.url.rsplit("/", 1)[1])

        # 5) orchestration: three ordered segments across two projects
        form = page.locator('[data-test="journey-segment-form"]')
        _add_segment(page, form, 1, pb_name, 1, case_id=case_id,
                     params=json.dumps({"caseIds": [case_id], "envId": env_id}))
        _add_segment(page, form, 2, pa_name, 0, asset_name=ASSET_NAME)
        _add_segment(page, form, 3, pb_name, 1, case_id=case_id,
                     params=json.dumps({"caseIds": [case_id], "envId": env_id}))

        # 6) expansion: the run starts and navigates to its timeline
        page.locator('[data-test="journey-start-run"]').click()
        page.wait_for_url("**/#/journeys/runs/*")
        expect(page.locator('[data-test="journey-timeline-page"]')).to_be_visible()
        run_id = int(page.url.rsplit("/", 1)[1])

        # 7) traffic lights: launched / failed(PARAMS_MISSING) / skipped
        lights = page.locator("[data-test^='journey-light-']")
        expect(lights).to_have_count(3)
        classes = [lights.nth(i).get_attribute("class") or "" for i in range(3)]
        assert any("light-3" in c for c in classes), f"no failed segment: {classes}"
        assert any("light-4" in c for c in classes), f"downstream not skipped: {classes}"
        assert any("light-1" in c for c in classes), f"first segment never launched: {classes}"
        failure = page.locator("[data-test^='journey-failure-']").first
        expect(failure).to_be_visible()
        expect(page.locator('[data-test="journey-run-unsigned"]')).to_be_visible()

        # 8) signing right = owner only: a second plain user is refused by the
        #    server (real door, before any button state can hide the truth).
        mk_tester(api_client, uniq)
        other_token = api_login(f"ttester_{uniq}", "Test@12345")
        denial = httpx.post(
            f"{API_BASE}/journeys/runs/{run_id}/signoff",
            headers={"Authorization": f"Bearer {other_token}"},
            timeout=15,
        )
        refused = denial.status_code in (401, 403) or (
            denial.status_code == 200 and denial.json().get("code") != "0"
        )
        assert refused, f"non-owner sign-off must be refused, got {denial.status_code} {denial.text[:200]}"

        # 9) the owner (the logged-in admin who authored it) signs on the UI
        signoff = page.locator('[data-test="journey-signoff"]')
        expect(signoff).to_be_enabled()
        signoff.click()
        expect(page.locator('[data-test="journey-run-signed"]')).to_be_visible()

        assert_no_errors(collectors, "golden journey program->signoff")

        # 10) dropping the lens must REFUSE while the journey still references it:
        #     conflict business code, never a generic 500 the user cannot act on.
        blocked = api_client.delete(f"/programs/{program_id}")
        assert blocked.status_code == 409, blocked.text[:200]
        assert blocked.json().get("code") == "40904", blocked.text[:200]

        # teardown order follows the foreign key: journey rows first through the
        # SQL door, then the lens drops cleanly and really disappears.
        _purge_journey(journey_id)
        journey_id = None
        deleted = api_client.delete(f"/programs/{program_id}")
        assert deleted.status_code == 200 and deleted.json().get("code") == "0", deleted.text[:200]
        # read-back after delete follows the house contract for missing rows:
        # HTTP 404 + business code 40401 with the human message (same shape as
        # the project-purge and requirement-unlink spectra).
        gone = api_client.get(f"/programs/{program_id}")
        assert gone.status_code == 404, gone.text[:200]
        assert gone.json().get("code") == "40401", gone.text[:200]
        program_id = None
    finally:
        if journey_id is not None:
            _purge_journey(journey_id)
        if program_id is not None:
            # failure-path sweep so the lens row never leaks into the ledger
            api_client.delete(f"/programs/{program_id}")
        for project in (pa, pb):
            if project:
                purge_project(api_client, project)
