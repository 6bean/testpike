"""Dead-letter ops page E2E (first browser spec for the page).

Real Chromium + the real admin->core read doors (no mocks). Worst-scale
fixture: the ledger already holds ~7088 real pending/UNKNOWN rows, so no
synthetic volume is needed for the list tier; the ENTITY_GONE "replay
forbidden" branch and the payload dialog are covered by two probe rows
that this fixture creates (dedicated queue tp_e2e_deadletter_probe) and
deletes by fingerprint afterwards - zero residue in the ops ledger.
The page's read doors are covered above; the action surface adds
row-level replay/void. The replay write door reposts to the original exchange,
which for probe rows is a non-existent e2e.ex - core then restores the claim and
the row goes back to pending (no broker round-trip residue). The void door only
flips status with a recorded reason and never deletes. Both action specs run
against a rebuilt admin jar (POST doors land with the commit), so
the pre-flight freshness check is mandatory before this window.
Run this file serially (`-n 0`): all specs share the one probe queue name, so
xdist workers would see each other's probe rows in the narrowed ledger and the
per-test row-count assertions cannot hold.
"""
import uuid

import pytest

from conftest import (
    assert_no_errors,
    nav_to,
    sql_query,
)
from issues_monitor import assert_no_browser_issues

PROBE_QUEUE = "tp_e2e_deadletter_probe"
PAGE_ROOT = '[data-test="dead-letter-page"]'
LEDGER_ROWS = '[data-test="dead-letter-table"] .el-table__body tr.el-table__row'


def _insert_probe_rows(suffix: str) -> list:
    """Insert two probe ledger rows (ENTITY_GONE + PROVIDER_TRANSIENT, pending).

    id is omitted so the ALWAYS-identity column assigns it through the same
    channel core uses; payload_json carries a unique token for the dialog
    assertion. Returns the fingerprints for later cleanup.
    """
    fps = []
    for cls in ("ENTITY_GONE", "PROVIDER_TRANSIENT"):
        fp = f"e2e-dlq-{suffix}-{cls}"
        sql_query(
            "INSERT INTO tp_mq_dead_letter (message_fingerprint, queue_name,"
            " orig_exchange, orig_routing_key, payload_json, failure_class,"
            " failure_reason, status)"
            " VALUES ("
            f" '{fp}', '{PROBE_QUEUE}', 'e2e.ex', 'rk.e2e',"
            f" '{{\"probeToken\": \"{suffix}\", \"class\": \"{cls}\"}}',"
            f" '{cls}', 'e2e probe row created by the fixture', 'pending')")
        fps.append(fp)
    return fps


def _delete_probe_rows(fps: list) -> None:
    in_list = ", ".join(f"'{fp}'" for fp in fps)
    sql_query(f"DELETE FROM tp_mq_dead_letter WHERE message_fingerprint IN ({in_list})")


@pytest.fixture
def probe_rows():
    """Function scope: test_dead_letter_ops_page cleans the probe rows inside its
    own run (step 10 verifies the gone-after-delete posture), so a module-scoped
    pair would starve the later specs whenever the suite runs serially (-n 0);
    under xdist the per-worker module copies hid that. Each test now inserts and
    removes its own fingerprinted pair."""
    suffix = uuid.uuid4().hex[:8]
    fps = _insert_probe_rows(suffix)
    yield suffix, fps
    _delete_probe_rows(fps)


def test_dead_letter_ops_page(logged_in, collectors, probe_rows):
    """Navigate via menu, verify panels/paging/narrowing/dialog/filters/cleanup."""
    suffix, fps = probe_rows
    page = logged_in

    # 1) menu navigation (the page must be reachable through the menu, not only deep links)
    nav_to(page, "死信队列", "/mq-dead-letter", PAGE_ROOT, submenu="系统管理")

    # 2) queue backlog panel: probe queue present, at least one grouped row
    #    (wait first: while loading the el-table is swapped out by TableSkeleton,
    #    so an immediate count() would read 0 - measured race on the rerun)
    queue_rows = page.locator('[data-test="dead-letter-queue-table"] .el-table__body tr')
    queue_rows.first.wait_for(state="visible", timeout=10000)
    assert queue_rows.count() >= 1, f"队列面板行数 {queue_rows.count()} < 1"
    page.wait_for_selector(f'.el-table__row:has-text("{PROBE_QUEUE}")', timeout=10000)

    # 3) class distribution: ENTITY_GONE row carries the replay-forbidden tag + honest basis note
    ban_note = page.locator('[data-test="dead-letter-class-row"]:has-text("实体已删除") '
                            '[data-test="dead-letter-replay-ban-note"]')
    ban_note.wait_for(state="visible", timeout=10000)
    assert "禁重放" in ban_note.inner_text(), f"禁重放标注文案异常: {ban_note.inner_text()}"
    basis = page.locator('[data-test="dead-letter-basis-note"]')
    assert "死信记录" in basis.inner_text() and "实时" in basis.inner_text(), \
        f"积压数量行未如实声明记录口径与实时深度区别: {basis.inner_text()}"

    # 4) backlog halo: pending total exceeds the 500 threshold, copy names the threshold
    halo = page.locator('[data-test="dead-letter-backlog-halo"]')
    halo.wait_for(state="visible", timeout=10000)
    assert "超过重放阈值" in halo.inner_text(), f"晕染行文案异常: {halo.inner_text()}"

    # 5) ledger first page: server-paged, full 20 rows + pagination bar present
    ledger = page.locator(LEDGER_ROWS)
    page.wait_for_selector(LEDGER_ROWS, timeout=10000)
    assert ledger.count() == 20, f"账本首页行数 {ledger.count()} != 20（默认页大小）"
    page.locator('[data-test="dead-letter-pagination"]').wait_for(state="visible", timeout=5000)

    # 6) paging: click page 2 -> first row content changes (real server round-trip)
    first_page1 = ledger.first.inner_text()
    page.locator('[data-test="dead-letter-pagination"] .el-pager li:has-text("2")').first.click()
    page.wait_for_selector(LEDGER_ROWS, timeout=10000)
    assert ledger.first.inner_text() != first_page1, "翻页后首行未变化"

    # 7) row-click narrowing: pick the probe queue -> filter echoes + ledger shows the 2 probes
    page.locator(f'[data-test="dead-letter-queue-table"] .el-table__row:has-text("{PROBE_QUEUE}")').click()
    page.locator(f'[data-test="dead-letter-table"] tr:has-text("{PROBE_QUEUE}")').first.wait_for(state="visible", timeout=10000)
    assert PROBE_QUEUE in page.locator('[data-test="dead-letter-queue-filter"]').inner_text(), \
        "点行后队列筛选未回显收窄"
    assert ledger.count() == 2, f"收窄后账本行数 {ledger.count()} != 2"

    # 8) payload dialog: open shows the probe token, close hides it
    page.locator('[data-test="dead-letter-payload-open"]').first.click()
    dialog = page.locator('[data-test="dead-letter-payload-dialog"]')
    dialog.wait_for(state="visible", timeout=5000)
    assert suffix in page.locator('[data-test="dead-letter-payload-body"]').inner_text(), \
        "payload 原文未含探针 token"
    page.locator('[data-test="dead-letter-payload-close"]').click()
    dialog.wait_for(state="hidden", timeout=5000)

    # 9) three-filter query (status + queue + class): narrows to the single ENTITY_GONE probe
    page.locator('[data-test="dead-letter-status-filter"]').click()
    page.locator('.el-select-dropdown__item:has-text("待处理")').last.click()
    page.locator('[data-test="dead-letter-class-filter"]').click()
    page.locator('.el-select-dropdown__item:has-text("实体已删除")').last.click()
    page.locator('[data-test="dead-letter-query"]').click()
    page.wait_for_function(
        "() => document.querySelectorAll('[data-test=\"dead-letter-table\"] .el-table__body tr.el-table__row').length === 1",
        timeout=10000)
    assert ledger.count() == 1, f"三连筛选后行数 {ledger.count()} != 1（ENTITY_GONE 探针行）"

    # 10) cleanup reconciliation: delete probes, refresh -> probe queue gone + empty state asserted
    _delete_probe_rows(fps)
    page.locator('[data-test="dead-letter-refresh"]').click()
    page.locator('[data-test="dead-letter-table"] .el-table__empty-block:has-text("暂无死信记录")') \
        .wait_for(state="visible", timeout=10000)
    assert ledger.count() == 0, "清账后账本仍有行"
    assert page.locator(f'[data-test="dead-letter-queue-table"] tr:has-text("{PROBE_QUEUE}")').count() == 0, \
        "探针行清账后仍出现在队列面板"

    # 11) three zero-tolerance tiers: API 4xx/5xx, console error/warn, browser Issues (no whitelist)
    assert_no_errors(collectors, "dead letter ops page")
    assert_no_browser_issues(collectors["browser_issues"], "dead letter ops page")


def _filter_probe_queue_class(page, cls_label: str) -> None:
    """Narrow the ledger to the probe queue + one failure class via the three filters."""
    page.locator(f'[data-test="dead-letter-queue-filter"]').click()
    page.locator(f'.el-select-dropdown__item:has-text("{PROBE_QUEUE}")').last.click()
    page.locator('[data-test="dead-letter-class-filter"]').click()
    page.locator(f'.el-select-dropdown__item:has-text("{cls_label}")').last.click()
    page.locator('[data-test="dead-letter-status-filter"]').click()
    page.locator('.el-select-dropdown__item:has-text("待处理")').last.click()
    page.locator('[data-test="dead-letter-query"]').click()


def test_dead_letter_replay_gating_display(logged_in, collectors, probe_rows):
    """Replay button display: ENTITY_GONE row disabled with an honest hint,
    PROVIDER_TRANSIENT pending row enabled. Pure display prediction (no POST),
    the authoritative rejection stays on the core gate."""
    page = logged_in
    nav_to(page, "死信队列", "/mq-dead-letter", PAGE_ROOT, submenu="系统管理")
    page.wait_for_selector(LEDGER_ROWS, timeout=10000)

    # ENTITY_GONE probe -> replay disabled + tooltip names the reason
    _filter_probe_queue_class(page, "实体已删除")
    gone_replay = page.locator('[data-test="dead-letter-row-replay"]').first
    gone_replay.wait_for(state="visible", timeout=10000)
    assert gone_replay.is_disabled(), "ENTITY_GONE 行的重放按钮应置灰（禁重放分型）"
    assert page.locator('[data-test="dead-letter-col-actions"]').count() == 1, "动作列应存在"

    # PROVIDER_TRANSIENT probe -> replay enabled
    _filter_probe_queue_class(page, "上游瞬断")
    ok_replay = page.locator('[data-test="dead-letter-row-replay"]').first
    ok_replay.wait_for(state="visible", timeout=10000)
    assert not ok_replay.is_disabled(), "待处理非禁重放分型的行应可点重放"

    assert_no_errors(collectors, "dead letter replay gating")
    assert_no_browser_issues(collectors["browser_issues"], "dead letter replay gating")


def test_dead_letter_row_void_flow(logged_in, collectors, probe_rows):
    """Void one PROVIDER_TRANSIENT probe with a recorded reason: success toast ->
    reload -> status flips to voided in both the DOM and the ledger table (persist),
    and the void door never deletes the row."""
    suffix, fps = probe_rows
    page = logged_in
    nav_to(page, "死信队列", "/mq-dead-letter", PAGE_ROOT, submenu="系统管理")
    page.wait_for_selector(LEDGER_ROWS, timeout=10000)

    _filter_probe_queue_class(page, "上游瞬断")
    row = page.locator('[data-test="dead-letter-table"] .el-table__row').first
    row.wait_for(state="visible", timeout=10000)

    # grab the fingerprint so we can assert that exact ledger row afterwards
    fp = f"e2e-dlq-{suffix}-PROVIDER_TRANSIENT"
    page.locator('[data-test="dead-letter-row-void"]').first.click()
    void_dialog = page.locator('[data-test="dead-letter-void-dialog"]')
    void_dialog.wait_for(state="visible", timeout=5000)

    # confirm stays disabled until a reason is typed (required-field guard)
    confirm = page.locator('[data-test="dead-letter-void-confirm"]')
    assert confirm.is_disabled(), "未填理由时作废确认应禁用"
    reason = f"e2e-void-{suffix}"
    # el-input forwards its attributes to the inner control, so the anchor IS the
    # textarea (same posture as the tool/llm description fields elsewhere)
    page.locator('[data-test="dead-letter-void-reason"]').fill(reason)
    assert not confirm.is_disabled(), "填入理由后作废确认可用"
    confirm.click()

    void_dialog.wait_for(state="hidden", timeout=8000)
    page.locator('[data-test="dead-letter-status-filter"]').click()
    page.locator('.el-select-dropdown__item:has-text("已作废")').last.click()
    page.locator('[data-test="dead-letter-query"]').click()
    voided_row = page.locator(f'[data-test="dead-letter-table"] .el-table__row:has-text("{fp[:20]}")')
    voided_row.first.wait_for(state="visible", timeout=10000)

    # sql_query returns a tuples-only CSV string; a single-column SELECT yields the bare value
    status = sql_query(
        "SELECT status FROM tp_mq_dead_letter WHERE message_fingerprint = "
        f"'{fp}'")
    assert status.strip() == "voided", f"作废后账本状态未落库: {status!r}"

    # the void door must never delete: the row is still present in the table
    present = sql_query(
        "SELECT count(*) FROM tp_mq_dead_letter WHERE message_fingerprint = "
        f"'{fp}'")
    assert present.strip() == "1", f"作废不得删除记录（留痕守恒），实际计数 {present!r}"

    assert_no_errors(collectors, "dead letter void flow")
    assert_no_browser_issues(collectors["browser_issues"], "dead letter void flow")
