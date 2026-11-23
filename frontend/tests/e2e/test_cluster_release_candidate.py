""" candidate-confirmation journey E2E (Playwright real browser + real
admin 8081 -> core 8083 chain, no mocks).

AF two-state model: detection lands in the candidate table, publishing stays a
human-authority act. Journey:
1. Seed two detected candidate rows in the real table (payload jsonb = the
 detection frame the release-feed probe would have stored)
2. Upgrade center shows halo cards under the latest banner; changelog keeps a
 pending group
3. View -> confirm drawer prefilled from the payload (version pinned, notes
 prefilled into breaking changes, blank fields highlighted not hard-blocked)
4. Impact preview through compat-diff-with (core scores every executor; the
 page only counts the out-of-range verdict, zero local re-scoring)
5. Fill matrix/range/path and confirm -> banner converges onto the new version
 and the card turns into the create-plan entry in place
6. DB reconciliation: candidate row confirmed + authoritative manifest row born
 with source=import
7. Dismissal negative spectrum: blank reason is rejected in plain words (the
 card stays), a real reason succeeds (status dismissed + trail kept)
8. Teardown deletes the seeded rows (never pollute the dev database); API
 response codes + console stay clean

Data isolation: uuid-suffixed versions, precise finally-cleanup of candidate
and manifest rows; the executor ledger is read-only here.
"""
import json
import subprocess
import uuid

import httpx
import pytest
from conftest import ADMIN_PASSWORD, BASE_URL, api_login, assert_no_errors, open_journey_leaf
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

API = BASE_URL.replace(":5173", ":8081")


def sql(q):
    r = subprocess.run(
        ["docker", "exec", "testpike-postgres", "psql", "-U", "testpike", "-d", "testpike", "-tAc", q],
        check=True, capture_output=True, text=True)
    return r.stdout.strip()


def seed_candidate(version, notes):
    """Insert a detected candidate row straight into the real table (detection
    frame payload mirrors what the release-feed probe would have stored)."""
    payload = json.dumps({
        "release_name": f"E2E Release {version}",
        "release_notes_md": notes,
        "assets": [{"name": "bundle.tgz", "url": "https://example.invalid/bundle.tgz"}],
    }, ensure_ascii=False)
    # Dollar-quote the jsonb literal so embedded quotes need no escaping.
    sql(f"INSERT INTO tp_release_candidate (version, payload) VALUES ('{version}', $${payload}$$::jsonb)")


def inject_token(page):
    """Real login + token injection (aligned with the conftest
    inject_user_token settled sequence: a full-page reload avoids the SPA
    same-hash navigation mount race that swallowed early injections)."""
    token = api_login("admin", ADMIN_PASSWORD)
    page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
    page.evaluate(f"() => {{ localStorage.setItem('tp_access_token', '{token}'); localStorage.setItem('tp_onboarding_dismissed', '1') }}")
    page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
    page.reload(wait_until="domcontentloaded")
    expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
    return page, token


def test_cluster_release_candidate_journey(page, collectors):
    """Detection -> halo card -> drawer prefill -> impact preview -> confirm convergence -> dismissal."""
    page, token = inject_token(page)
    h = {"Authorization": f"Bearer {token}"}
    api = httpx.Client(base_url=API, timeout=30, headers=h)
    suffix = uuid.uuid4().hex[:6]
    # Numeric-only segments keep the version inside the core parse spectrum;
    # uniqueness rides the prerelease label (hex suffix never int()-parsed).
    # The dismiss target MUST outrank the confirm target: confirming a higher
    # version demotes lower detected rows to `replaced` (core single spectrum),
    # which would silently empty the pending group mid-journey.
    minor = int(suffix[:2], 16)
    patch = int(suffix[2:4], 16)
    v_conf = f"9.{minor:02d}.{patch:02d}.0-e2e{suffix}a"
    v_dis = f"9.{minor + 1:02d}.{patch:02d}.0-e2e{suffix}b"
    notes = f"e2e 变更说明 {suffix}：升级前需手动执行迁移"

    # write-back fixture: platform release-tracked rows seeded before the
    # confirmation (the ledger write-back only updates existing rows and
    # re-judges them on the single verdict spectrum; current 1.0.0 behind the
    # confirmed 9.x = YELLOW, current NULL = honest gray).
    node = f"e2e-t352-{suffix}"
    sql(f"""INSERT INTO tp_cluster_component(node_id, component_family, current_version, requirement, source)
             VALUES ('{node}', 'core', '1.0.0', '>=1.0.0', 'heartbeat')""")
    sql(f"""INSERT INTO tp_cluster_component(node_id, component_family, current_version, requirement, source)
             VALUES ('{node}', 'admin', NULL, NULL, 'heartbeat')""")
    core_id = sql(f"SELECT id FROM tp_cluster_component WHERE node_id='{node}' AND component_family='core'")
    admin_id = sql(f"SELECT id FROM tp_cluster_component WHERE node_id='{node}' AND component_family='admin'")

    try:
        # -- 1. seed two detected rows in the real table
        seed_candidate(v_conf, notes)
        seed_candidate(v_dis, notes)

        # -- 2. halo cards on the upgrade center page (below the latest banner)
        open_journey_leaf(page, "menu-upgrade-center", "system")
        expect(page.locator('[data-test="upgrade-center-page"]')).to_be_visible(timeout=10000)
        card_conf = page.locator(f'[data-test="upgrade-candidate-{v_conf}"]')
        expect(card_conf).to_be_visible(timeout=10000)
        expect(card_conf).to_contain_text("待确认")
        expect(page.locator(f'[data-test="upgrade-candidate-{v_dis}"]')).to_be_visible()
        # the in-card plain-language note states the two-state model (detected != published)
        expect(page.locator('[data-test="upgrade-candidate-note"]').first).to_contain_text("发布确认仍须人工权威")

        # -- 3. view -> confirm drawer prefill (version pinned read-only, notes prefilled, blanks highlighted not hard-blocked)
        card_conf.locator('[data-test^="upgrade-candidate-view-"]').first.click()
        drawer = page.locator('[data-test="upgrade-confirm-drawer"]')
        expect(drawer).to_be_visible(timeout=8000)
        expect(page.locator('[data-test="upgrade-confirm-version"]')).to_have_value(v_conf)
        expect(page.locator('[data-test="upgrade-confirm-source"]')).to_have_value("import")
        # notes prefills breakingChanges as a JSON array string (openConfirm posture);
        # the el-input textarea carries it as a value attribute, not DOM text -> to_have_value spectrum
        expect(page.locator('[data-test="upgrade-confirm-breaking"]')).to_have_value(f'["{notes}"]')
        # empty matrix/range/path -> the blank-field hint is shown (hint, not a submit gate)
        expect(page.locator('[data-test="upgrade-confirm-blank-hint"]')).to_be_visible()

        # -- 4. impact preview: lower bound above the seeded executor version -> core scores out-of-range rows
        page.locator('[data-test="upgrade-confirm-min"]').fill("2.0.0")
        page.locator('[data-test="upgrade-confirm-max"]').fill("9.9.9")
        page.locator('[data-test="upgrade-confirm-impact-btn"]').click()
        impact = page.locator('[data-test="upgrade-confirm-impact-result"]')
        expect(impact).to_be_visible(timeout=10000)
        # the ledger executor version 1.0.0 sits below 2.0.0 = out of range; the
        # verdict sentence has two spectra (N out of range / all in range), so only
        # the plain-language prefix is asserted while the count floats with the ledger
        expect(impact).to_contain_text("按当前区间预检")

        # -- 5. fill the authoritative fields and submit the confirmation
        # Support-only matrix (no pinned): the confirm drawer does not collect
        # artifacts yet — declaring a pinned family without its download would
        # correctly hit the supply-chain gate and fail this journey.
        page.locator('[data-test="upgrade-confirm-matrix"]').fill(
            json.dumps([{"component": "executor", "support": "0.0.0 ~ 9.9.9"}]))
        page.locator('[data-test="upgrade-confirm-min"]').fill("0.0.0")
        page.locator('[data-test="upgrade-confirm-max"]').fill("9.9.9")
        page.locator('[data-test="upgrade-confirm-path"]').fill(f"e2e 引导路径 {suffix}")
        page.locator('[data-test="upgrade-confirm-submit"]').click()
        expect(page.locator('.el-message--success').first).to_contain_text("已确认发布", timeout=15000)

        # -- 5b. convergence: the card disappears -> the in-place create-plan entry
        expect(card_conf).to_be_hidden(timeout=15000)
        published = page.locator(f'[data-test="upgrade-published-{v_conf}"]')
        expect(published).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="upgrade-candidate-create-plan-{v_conf}"]')).to_be_visible()

        # -- 5c. the banner converges onto the new version (latest recomputed from the authoritative table)
        expect(page.locator('[data-test="upgrade-banner-version"]')).to_have_text(v_conf, timeout=15000)

        # -- 5d. one-click linkage: the create-plan entry presets the plan
        #        wizard at step 1 with the released version pinned as manifest
        page.locator(f'[data-test="upgrade-candidate-create-plan-{v_conf}"]').click()
        plan_form = page.locator('[data-test="upgrade-plan-form"]')
        expect(plan_form).to_be_visible(timeout=10000)
        # a wizard left at step 2/3 from an earlier run must never hijack this
        # entry: preview/progress panes stay hidden (resetPlanFlow first posture)
        expect(page.locator('[data-test="upgrade-plan-preview"]')).to_be_hidden()
        expect(page.locator('[data-test="upgrade-plan-progress"]')).to_be_hidden()
        # el-select renders the chosen label in one of the .el-select__selected-item
        # variants (the input-wrapper sibling stays empty), so filter by text
        expect(page.locator('[data-test="upgrade-plan-manifest"] .el-select__selected-item', has_text=v_conf))\
            .to_be_visible(timeout=10000)

        # -- 5e. ledger write-back tri-color panorama: confirmed release
        #        lands as target on release-tracked families and re-judges them
        #        (core current 1.0.0 < release = YELLOW; admin not-reported = honest gray)
        assert sql(f"SELECT target_version FROM tp_cluster_component WHERE id={core_id}") == v_conf
        assert sql(f"SELECT support_state FROM tp_cluster_component WHERE id={core_id}") == "YELLOW"
        assert sql(f"SELECT target_version FROM tp_cluster_component WHERE id={admin_id}") == v_conf
        assert sql(f"SELECT support_state FROM tp_cluster_component WHERE id={admin_id}") == "UNKNOWN"
        page.get_by_role("tab", name="版本全景").click()
        matrix = page.locator('[data-test="upgrade-matrix-state-%s"]' % core_id)
        expect(matrix).to_be_visible(timeout=10000)
        expect(matrix).to_have_text("可升级")
        expect(page.locator('[data-test="upgrade-matrix-state-%s"]' % admin_id)).to_have_text("未知")
        # back to the manifest tab for the changelog/dismissal segments below
        page.get_by_role("tab", name="版本清单").click()
        expect(published).to_be_visible(timeout=10000)

        # -- 6. DB reconciliation: candidate confirmed + manifest row born with source=import
        assert sql(f"SELECT status FROM tp_release_candidate WHERE version='{v_conf}'") == "confirmed"
        row = sql(f"SELECT source FROM tp_release_manifest WHERE version='{v_conf}'")
        assert row == "import", f"confirmed row must carry source=import, got {row!r}"

        # -- 6b. changelog drawer: the confirmed version joins the published group
        #        while the remaining candidate stays in the pending group on top
        #        (the changelog button is disabled without a latest row = existing
        #        contract, hence this block runs after the confirmation)
        page.locator('[data-test="upgrade-changelog-btn"]').click()
        changelog = page.locator('[data-test="upgrade-changelog-drawer"]')
        expect(changelog).to_be_visible(timeout=8000)
        pending = page.locator('[data-test="upgrade-changelog-pending-group"]')
        expect(pending).to_contain_text(v_dis, timeout=8000)
        expect(pending).not_to_contain_text(v_conf)
        page.keyboard.press("Escape")
        expect(changelog).to_be_hidden(timeout=8000)

        # -- 7. dismissal negative spectrum: blank reason rejected in plain words, card stays
        card_dis = page.locator(f'[data-test="upgrade-candidate-{v_dis}"]')
        card_dis.locator('[data-test^="upgrade-candidate-dismiss-"]').first.click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=8000)
        box.locator('.el-message-box__btns button.el-button--primary').click()
        expect(page.locator('.el-message--warning').first).to_contain_text("作废理由不能为空", timeout=8000)
        assert sql(f"SELECT status FROM tp_release_candidate WHERE version='{v_dis}'") == "detected"
        expect(card_dis).to_be_visible(timeout=8000)

        # a real reason dismisses: card disappears + reason kept in the payload trail
        card_dis.locator('[data-test^="upgrade-candidate-dismiss-"]').first.click()
        box = page.locator('.el-message-box')
        expect(box).to_be_visible(timeout=8000)
        box.locator("textarea").fill(f"e2e 作废理由 {suffix}")
        box.locator('.el-message-box__btns button.el-button--primary').click()
        expect(card_dis).to_be_hidden(timeout=15000)
        assert sql(f"SELECT status FROM tp_release_candidate WHERE version='{v_dis}'") == "dismissed"
        assert suffix in sql(f"SELECT payload->>'dismiss_reason' FROM tp_release_candidate WHERE version='{v_dis}'")

        assert_no_errors(collectors, "候选确认体验面")
    finally:
        sql(f"DELETE FROM tp_cluster_component WHERE node_id = '{node}'")
        sql(f"DELETE FROM tp_release_candidate WHERE version IN ('{v_conf}','{v_dis}')")
        sql(f"DELETE FROM tp_release_manifest WHERE version IN ('{v_conf}','{v_dis}')")
        api.close()
