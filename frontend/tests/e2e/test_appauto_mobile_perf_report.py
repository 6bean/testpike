"""First browser spectrum for the batch-detail mobile-performance block (display face).

Covers (real chain, no mocks):
1. A terminal batch whose snapshot carries the full rollup (8 metric keys with
   median / taskCount / prevMedian / delta across every tone form) renders the
   block with one row per metric; every number is read verbatim from the
   finalize-time snapshot — the page never recomputes.
2. Tone classes honour metric meaning: a rising volume metric (totalFrames)
   reports the change without a good/bad verdict, while lower-is-better
   metrics colour by sign; a metric without prev data shows the honest
   absence dash instead of a fabricated zero.
3. RELOAD keeps the block with identical values (the snapshot row in the
   database is the source, not transient frontend state).
4. A terminal batch without any mobile reading (NULL snapshot column) shows
   no block at all — hard zero-count assertion, never a conditional skip.
5. API response codes + console + browser Issues zero-tolerance via
   collectors (assert_no_errors).

Fixture note: the finalize write path (executor report -> core rollup ->
snapshot row) is covered by the server-side L2/L3 of the preceding packages.
This spectrum feeds terminal batches and snapshot rows straight into THIS
chain's slot database so the display projections are pinned independently of
hardware; inserted rows are deleted by id in teardown. Device lists and
project slices in the slot are shared/registered by other owners, so all
assertions below bind only to the ids this test inserted.
"""
import json

import pytest
from playwright.sync_api import expect

from conftest import (
    ADMIN_PASSWORD,
    BASE_URL,
    api_login,
    assert_no_errors,
    db_connect,
)

pytestmark = pytest.mark.timeout(300)

# key -> (median, prevMedian, delta, taskCount, expectedMedianText,
#         expectedPrevText, expectedDeltaText, expectedToneClass)
# Tone forms exercised in one page: worse / better / flat / none / neutral.
EXPECTED_ROWS = [
    ("jankPct", 3.2, 1.7, 1.5, 3, "3.2%", "1.7%", "+1.5%", "perf-tone-worse"),
    ("pssMb", 128, 130, -2, 3, "128MB", "130MB", "-2MB", "perf-tone-better"),
    ("cpuPct", 45, 45, 0, 2, "45%", "45%", "0%", "perf-tone-flat"),
    ("tempC", 36.5, None, None, 1, "36.5°C", "—", "—", "perf-tone-none"),
    # totalFrames is a volume count: a rise is reported, never judged.
    ("totalFrames", 1200, 1080, 120, 3, "1200", "1080", "+120", "perf-tone-neutral"),
    ("jankyFrames", 39, 42, -3, 3, "39", "42", "-3", "perf-tone-better"),
    ("launchTotalMs", 5652, 5952, -300, 2, "5652ms", "5952ms", "-300ms", "perf-tone-better"),
    ("launchWaitMs", 35, 20, 15, 2, "35ms", "20ms", "+15ms", "perf-tone-worse"),
]


def inject_token(page):
    """Real login + token injection (same pattern as the batch-report precedent)."""
    for attempt in (1, 2):
        token = api_login("admin", ADMIN_PASSWORD)
        page.goto(f"{BASE_URL}/#/login", wait_until="domcontentloaded")
        page.evaluate(
            f"() => {{ localStorage.setItem('tp_access_token', '{token}');"
            " localStorage.setItem('tp_onboarding_dismissed', '1'); }"
        )
        page.goto(f"{BASE_URL}/#/dashboard", wait_until="domcontentloaded")
        try:
            expect(page.locator('[data-test="my-project-table"]').first).to_be_visible(timeout=15000)
            return page
        except AssertionError:
            if attempt == 2:
                raise AssertionError("token injected but dashboard never rendered")
    raise AssertionError("unreachable")


def seed_terminal_batch(conn, name, status, mobile_perf, prev_batch_id=None):
    """Insert one terminal batch + its report snapshot row; returns (batch_id, report_id)."""
    cur = conn.cursor()
    cur.execute("SELECT id FROM tp_project ORDER BY id LIMIT 1")
    project_id = cur.fetchone()[0]
    cur.execute(
        "INSERT INTO tp_exec_batch (project_id, name, status) VALUES (%s, %s, %s) RETURNING id",
        (project_id, name, status),
    )
    batch_id = cur.fetchone()[0]
    payload = None if mobile_perf is None else json.dumps(mobile_perf)
    cur.execute(
        """
        INSERT INTO tp_batch_report
            (batch_id, project_id, total, pass_cnt, fail_cnt, skip_cnt, timeout_cnt,
             pass_rate, fail_dist_json, attr_json, duration_ms, env_version_snapshot,
             mock_task_cnt, llm_task_cnt, mobile_perf_json, prev_batch_id, batch_status)
        VALUES (%s, %s, 5, 4, 1, 0, 0, 0.8000, '{}'::jsonb, '{}'::jsonb, 12345, 'e2e-fixture',
                0, 0, %s, %s, %s)
        RETURNING id
        """,
        (batch_id, project_id, payload, prev_batch_id, status),
    )
    report_id = cur.fetchone()[0]
    conn.commit()
    return batch_id, report_id


def full_rollup():
    metrics = {}
    for key, median, prev, delta, task_count, *_ in EXPECTED_ROWS:
        entry = {"median": median, "taskCount": task_count}
        if prev is not None:
            entry["prevMedian"] = prev
        if delta is not None:
            entry["delta"] = delta
        # tempC keeps both prev fields absent entirely: the honest-absence form.
        metrics[key] = entry
    return {"metrics": metrics, "sampleCount": 19}


def test_batch_mobile_perf_report_full_spectrum(page, collectors, uniq):
    """Terminal batch with the full rollup: block renders every metric row verbatim."""
    conn = db_connect()
    inserted = []
    try:
        inject_token(page)
        batch_id, report_id = seed_terminal_batch(conn, f"e2e-mperf-{uniq[:6]}", 6, full_rollup())
        inserted.append((batch_id, report_id))

        url = f"{BASE_URL}/#/batches/{batch_id}"
        page.goto(url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-detail-page"]')).to_be_visible()
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible()

        block = page.locator('[data-test="batch-mobile-perf"]')
        expect(block).to_be_visible()
        # jsonb reorders keys by its own rules, so the row count and the
        # per-key anchors are asserted, never the visual order.
        expect(page.locator('[data-test="batch-mobile-perf-table"] tbody tr')).to_have_count(8)

        for (key, _m, _p, _d, task_count, median_text, prev_text, delta_text, tone) in EXPECTED_ROWS:
            expect(page.locator(f'[data-test="mobile-perf-delta-{key}"]')).to_have_text(delta_text)
            expect(page.locator(f'[data-test="mobile-perf-delta-{key}"]')).to_have_class(tone)

        medians = page.locator('[data-test="mobile-perf-median"]')
        expect(medians).to_have_count(8)
        expected_medians = sorted(str(row[5]) for row in EXPECTED_ROWS)
        rendered_medians = sorted(
            medians.nth(i).inner_text() for i in range(8)
        )
        assert rendered_medians == expected_medians, (
            f"median column drifted: {rendered_medians} != {expected_medians}"
        )
        prevs = page.locator('[data-test="mobile-perf-prev"]')
        expect(prevs).to_have_count(8)
        expected_prevs = sorted(row[6] for row in EXPECTED_ROWS)
        assert sorted(prevs.nth(i).inner_text() for i in range(8)) == expected_prevs
        counts = page.locator('[data-test="mobile-perf-taskcount"]')
        expect(counts).to_have_count(8)
        expected_counts = sorted(str(row[4]) for row in EXPECTED_ROWS)
        assert sorted(counts.nth(i).inner_text() for i in range(8)) == expected_counts

        # Reload persistence: the snapshot row is the single source.
        page.goto("about:blank")
        page.goto(url, wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-mobile-perf"]')).to_be_visible()
        expect(page.locator('[data-test="mobile-perf-delta-jankPct"]')).to_have_text("+1.5%")
        expect(page.locator('[data-test="mobile-perf-delta-totalFrames"]')).to_have_text("+120")
        expect(page.locator('[data-test="mobile-perf-delta-totalFrames"]')).to_have_class("perf-tone-neutral")

        # Honest absence: a terminal batch without any mobile reading must
        # render no block at all — a hard zero-count, never a silent skip.
        none_batch_id, none_report_id = seed_terminal_batch(
            conn, f"e2e-mperf-none-{uniq[:6]}", 6, None)
        inserted.append((none_batch_id, none_report_id))
        page.goto("about:blank")
        page.goto(f"{BASE_URL}/#/batches/{none_batch_id}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="batch-report-card"]')).to_be_visible()
        assert page.locator('[data-test="batch-mobile-perf"]').count() == 0, (
            "NULL mobile_perf_json must render no performance block, not a zero-filled one"
        )

        assert_no_errors(collectors, "batch mobile perf report spectrum")
    finally:
        cur = conn.cursor()
        for batch_id, report_id in inserted:
            cur.execute("DELETE FROM tp_batch_report WHERE id = %s", (report_id,))
            cur.execute("DELETE FROM tp_exec_batch WHERE id = %s", (batch_id,))
        conn.commit()
        conn.close()
