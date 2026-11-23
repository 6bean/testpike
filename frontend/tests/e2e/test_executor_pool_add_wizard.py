"""Executor-pool add-pool wizard — first real-browser E2E for the write entry.

Covers the three-step wizard landed by the front-end batch (no mocks, real
slot1 stack, real admin -> Feign -> core chain):
1. worst-case pool fixture (32 pool tags under one project) keeps the
   pool picker usable through instant filtering;
2. the existing-pool path issues a one-time enrollment token whose plaintext
   is rendered exactly once, the fingerprint ledger row appears, and revoke
   silently re-reads the ledger to zero for this run's anchors;
3. the create-pool path lands a new pool tag through the single write channel
   (POST /tags type 7) and the pool card appears on the page list without a
   manual refresh (the @landed silent re-read).

Token rows are swept by the run's unique remark anchor in a finally block so
interrupted runs never leak ledger noise (R57 teardown discipline).
"""
import pytest
from playwright.sync_api import expect

from conftest import (
    assert_no_errors,
    open_journey_leaf,
    pick_project_in_header,
    purge_project,
    seed_project,
    sql_query,
)

pytestmark = pytest.mark.timeout(300)

POOL_ROWS = 32  # worst-case scale for the picker (>=30 per display-scale rule)


def _sweep_wizard_tokens(uniq: str) -> None:
    """Remove token rows leaked by earlier interrupted runs of this spec."""
    sql_query(
        f"DELETE FROM tp_cluster_enroll_token WHERE remark LIKE '%{uniq}%'"
    )


def _seed_pools(api_client, pid: int, uniq: str) -> list[int]:
    """32 pool tags through the real single write channel."""
    ids = []
    for i in range(POOL_ROWS):
        tag = api_client.post(
            "/tags",
            json={"projectId": pid, "name": f"WizPool-{uniq}-{i:02d}", "type": 7},
        ).json()["data"]
        ids.append(tag["id"])
    return ids


def test_wizard_existing_pool_filter_issue_and_revoke(
    logged_in, api_client, collectors, uniq
):
    """Filter 32 pools -> issue one-time token once -> ledger row -> revoke."""
    page = logged_in
    pid = seed_project(api_client, uniq, "WZA", display_name=f"WizA-{uniq}")
    try:
        _seed_pools(api_client, pid, uniq)
        _sweep_wizard_tokens(uniq)
        open_journey_leaf(page, "menu-executor-pools", "system")
        expect(page.locator('[data-test="pool-page"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="pool-wizard-open"]').click()
        expect(page.locator('[data-test="pool-wizard-dialog"]')).to_be_visible()

        # Step 1: existing pool with instant filter over the worst case.
        expect(page.locator('[data-test="pool-wizard-pane-pool"]')).to_be_visible()
        page.locator('[data-test="pool-wizard-pool-select"]').click()
        page.keyboard.type(f"WizPool-{uniq}-07")
        items = page.locator(".el-select-dropdown__item:visible")
        expect(items).to_have_count(1, timeout=10000)
        items.first.click()
        page.locator('[data-test="pool-wizard-next"]').click()

        # Step 2: enroll pane shows the ledger and a fresh one-time token.
        expect(page.locator('[data-test="pool-wizard-pane-enroll"]')).to_be_visible()
        page.locator('[data-test="pool-wizard-issue"]').click()
        token_block = page.locator('[data-test="pool-wizard-token"]')
        expect(token_block).to_be_visible(timeout=15000)
        plaintext = page.locator(
            '[data-test="pool-wizard-token"] .wizard-token-value'
        ).inner_text()
        assert plaintext.strip(), "one-time token plaintext must render once"

        ledger_rows = page.locator('[data-test="pool-wizard-ledger"] tbody tr')
        expect(ledger_rows.first).to_be_visible(timeout=10000)
        before = ledger_rows.count()
        assert before >= 1

        # Revoke -> the ledger silently re-reads and this run's row is gone.
        page.locator('[data-test^="pool-wizard-revoke-"]').first.click()
        expect(page.locator('[data-test="pool-wizard-ledger"] tbody tr')).to_have_count(
            before - 1, timeout=10000
        )

        page.locator('[data-test="pool-wizard-next"]').click()
        expect(page.locator('[data-test="pool-wizard-pane-done"]')).to_be_visible()
        page.locator('[data-test="pool-wizard-finish"]').click()
        expect(page.locator('[data-test="pool-wizard-dialog"]')).to_be_hidden()

        assert_no_errors(collectors)
    finally:
        _sweep_wizard_tokens(uniq)
        purge_project(api_client, pid)


def test_wizard_create_pool_lands_without_manual_refresh(
    logged_in, api_client, collectors, uniq
):
    """Create path -> skip enroll -> finish -> new pool card appears silently."""
    page = logged_in
    pid = seed_project(api_client, uniq, "WZB", display_name=f"WizB-{uniq}")
    new_name = f"WizNew-{uniq}"
    try:
        open_journey_leaf(page, "menu-executor-pools", "system")
        expect(page.locator('[data-test="pool-page"]')).to_be_visible(timeout=15000)

        # The wizard creates the tag under the top-bar project segment (R30: the
        # header is the only project switch), so land it on this run's project
        # first — with no context the guard honestly warns and stays on step 1.
        pick_project_in_header(page, f"WizB-{uniq}")

        page.locator('[data-test="pool-wizard-open"]').click()
        expect(page.locator('[data-test="pool-wizard-dialog"]')).to_be_visible()

        # Step 1: switch to the create choice and name the new pool.
        # (el-radio hides its native input visually; click the label text —
        # wrapper-only clicks proved flaky in the 2026-11-22 window run.)
        page.locator(".wizard-choice label.el-radio", has_text="新建").click()
        # EP forwards non-prop attrs to the native input of el-input, so
        # data-test sits ON the input itself (repo precedent: upgrade-plan
        # window fills `input[data-test=...]` the same way).
        page.locator('[data-test="pool-wizard-pool-name"]').fill(new_name)
        page.locator('[data-test="pool-wizard-next"]').click()

        # Step 2: enroll pane reached without issuing -> skip is honest.
        expect(page.locator('[data-test="pool-wizard-pane-enroll"]')).to_be_visible()
        # The skip control names the skipped action and the terminal outcome
        # (locale pool.wizardEnrollSkip) and its handler is
        # finish() itself (single source in FleetPoolWizard): the pool tag was
        # already landed on step-0 advance, so skipping closes the dialog and
        # emits @landed — no summary pane in between (the done pane stays on
        # the issue -> next route).
        page.locator('[data-test="pool-wizard-skip"]').click()
        expect(page.locator('[data-test="pool-wizard-dialog"]')).to_be_hidden()

        # The list re-reads silently after finish (@landed): the new pool card
        # must show WITHOUT any manual refresh click.
        expect(
            page.locator(".pool-card").filter(has_text=new_name).first
        ).to_be_visible(timeout=15000)

        assert_no_errors(collectors)
    finally:
        # The wizard creates the tag under the top-bar project context, which
        # may differ from this run's seeded project -> sweep by name anchor.
        sql_query(f"DELETE FROM tp_tag_dict WHERE name = '{new_name}'")
        purge_project(api_client, pid)


def test_wizard_access_command_block_forms_and_plaintext(
    logged_in, api_client, collectors, uniq
):
    """Access-command block: placeholder before issuance, real plaintext
    after, the k8s form never carries a secret, copy is never silent.

    Pins the wizard surface landed with the add-pool command package; the
    key names rendered here are contract-pinned to the installer template
    by the fleetPoolWizard vitest suite, this spectrum proves the live
    browser actually assembles them from page facts and component state.
    """
    page = logged_in
    pid = seed_project(api_client, uniq, "WZE", display_name=f"WizE-{uniq}")
    pool_name = f"WizPool-{uniq}-00"
    try:
        api_client.post(
            "/tags", json={"projectId": pid, "name": pool_name, "type": 7}
        )
        _sweep_wizard_tokens(uniq)
        open_journey_leaf(page, "menu-executor-pools", "system")
        expect(page.locator('[data-test="pool-page"]')).to_be_visible(timeout=15000)

        page.locator('[data-test="pool-wizard-open"]').click()
        expect(page.locator('[data-test="pool-wizard-dialog"]')).to_be_visible()
        page.locator('[data-test="pool-wizard-pool-select"]').click()
        page.keyboard.type(pool_name)
        items = page.locator(".el-select-dropdown__item:visible")
        expect(items).to_have_count(1, timeout=10000)
        items.first.click()
        page.locator('[data-test="pool-wizard-next"]').click()

        command = page.locator('[data-test="pool-wizard-command"]')
        expect(command).to_be_visible()
        value = command.locator(".wizard-command-value")

        # Bare form BEFORE issuance: a visible placeholder, never an empty key.
        text = value.inner_text()
        assert "EXECUTOR_NODE_BASE_URL=" in text
        assert "EXECUTOR_NODE_ENROLL_TOKEN=<EXECUTOR_NODE_ENROLL_TOKEN>" in text
        assert "./deploy/fleet/bare/install-executor-node.sh" in text

        # k8s form: pool-fact registration, no token travels on screen at all.
        page.locator('[data-test="pool-wizard-form-k8s"]').click()
        text = value.inner_text()
        assert "EXECUTOR_POOL_API_URL=" in text
        assert "./deploy/fleet/k8s/install-executor-pool.sh" in text
        assert "ENROLL_TOKEN" not in text

        # Back to bare, issue once -> the command carries the shown plaintext.
        page.locator('[data-test="pool-wizard-form-bare"]').click()
        page.locator('[data-test="pool-wizard-issue"]').click()
        token_block = page.locator('[data-test="pool-wizard-token"]')
        expect(token_block).to_be_visible(timeout=15000)
        plaintext = token_block.locator(".wizard-token-value").inner_text().strip()
        assert plaintext, "one-time token plaintext must render once"
        text = value.inner_text()
        assert f"EXECUTOR_NODE_ENROLL_TOKEN={plaintext}" in text
        assert "<EXECUTOR_NODE_ENROLL_TOKEN>" not in text

        # Copy is never silent: a success or a manual-fallback toast must show.
        page.locator('[data-test="pool-wizard-command-copy"]').click()
        expect(page.locator(".el-message").first).to_be_visible(timeout=5000)

        page.locator('[data-test="pool-wizard-next"]').click()
        expect(page.locator('[data-test="pool-wizard-pane-done"]')).to_be_visible()
        page.locator('[data-test="pool-wizard-finish"]').click()
        expect(page.locator('[data-test="pool-wizard-dialog"]')).to_be_hidden()

        assert_no_errors(collectors)
    finally:
        _sweep_wizard_tokens(uniq)
        sql_query(f"DELETE FROM tp_tag_dict WHERE name = '{pool_name}'")
        purge_project(api_client, pid)
