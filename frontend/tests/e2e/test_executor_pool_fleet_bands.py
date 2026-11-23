"""Executor-pool fleet bands — first real-browser E2E for the elasticity slices.

Covers the read side landed by the fleet batch (no mocks, real slot stack):
1. the page reaches all five fleet bands from the sidebar entry
   (elasticity overview / node matrix / scale-event timeline / watermark
   curve / command ledger);
2. the timeline renders its worst-case fixture (32 ledger rows over two
   pools, newest-first, reason text verbatim) instead of only an empty state;
3. the node matrix renders seeded component-ledger rows through the shared
   verdict badge single source, and the three write buttons stay honestly
   disabled while the apply channel is not wired;
4. refresh reloads every band under one command (pool card appears after a
   pool tag is seeded, timeline row count survives the reload), and a route
   re-entry re-reads the same DB truth (no front-end-only state);
5. cleanup returns the ledger to zero rows for this run's anchors.

Ledger rows are seeded through psql against THIS chain's slot database
(conftest.sql_query resolves the db name from the loaded chain env), because
the autoscaler apply channel that writes them lands in a later package; the
asserted path itself (browser -> admin -> Feign -> core -> slot DB) is real.
"""
import pytest
from playwright.sync_api import expect

from conftest import (
    assert_no_errors,
    open_journey_leaf,
    purge_project,
    seed_project,
    sql_query,
)

pytestmark = pytest.mark.timeout(300)

POOL_A_ROWS = 20
POOL_B_ROWS = 12
COMPONENT_ROWS = 5
TIMELINE_ANCHOR = '[data-test="fleet-timeline-table"] .el-table__body tbody tr'


def _sweep_fleet_fixtures():
    """Remove leaked fixture rows from earlier interrupted runs.

    The seed helper backdates reasons with the fleet-e2e- prefix, so the
    pattern only ever matches this spec's own ledger rows — never real
    autoscaler writes. Without the sweep, an interrupted run (seed failure
    before the try/finally was reachable) left 32 rows behind and the
    cross-pool read window (core ledger default 50) then rendered a count
    no equal to this run's seed — a stale-data false red.
    """
    sql_query(
        "DELETE FROM tp_cluster_scale_event WHERE reason LIKE 'fleet-e2e-%'"
    )
    sql_query(
        "DELETE FROM tp_cluster_component WHERE node_id LIKE 'fleet-e2e-node-%'"
    )
    sql_query(
        "DELETE FROM tp_cluster_command WHERE operator LIKE 'fleet-e2e-%'"
    )


def _seed_scale_events(uniq):
    """32 timeline rows across two pools; one sentinel row is the newest."""
    pool_a = f"web-e2e-{uniq}"
    pool_b = f"app-e2e-{uniq}"
    rows = []
    for i in range(POOL_A_ROWS):
        reason = (
            f"fleet-e2e-{uniq}-latest"
            if i == 0
            else f"fleet-e2e-{uniq}-a{i:03d}"
        )
        # Direction-consistent fixture (vision closing round, real defect #1):
        # a SCALE_OUT row must carry desired > current and a SCALE_IN row
        # desired < current. The page renders both numbers verbatim, so an
        # inconsistent row would show "scale in" while replicas grow.
        if i % 2 == 0:
            action, desired, current = "SCALE_OUT", 4 + i, 3 + i
        else:
            action, desired, current = "SCALE_IN", 3 + i, 4 + i
        rows.append(
            f"('{pool_a}','{action}',{desired},{current},'{reason}',"
            f"now() - make_interval(mins => {i * 2}))"
        )
    for i in range(POOL_B_ROWS):
        rows.append(
            f"('{pool_b}','HELD',2,2,'fleet-e2e-{uniq}-b{i:03d}',"
            f"now() - make_interval(mins => {POOL_A_ROWS * 2 + i * 3}))"
        )
    sql_query(
        "INSERT INTO tp_cluster_scale_event "
        "(pool_key, action, desired_replicas, current_replicas, reason, created_at) VALUES "
        + ",".join(rows)
    )
    return pool_a, pool_b


WATERMARK_TYPES = 2
WATERMARK_SAMPLES = 26

COMMAND_ROWS = 34
COMMANDS_ANCHOR = '[data-test="fleet-commands-table"] .el-table__body tbody tr'


def _sweep_command_fixtures():
    """Remove leaked command-ledger rows from earlier interrupted runs.

    The fixture marks every seeded row with the fleet-e2e- operator, a
    value the enqueue service never writes (it records the real account
    name), so the pattern only ever matches this spec's own rows — never
    live fleet commands.
    """
    sql_query("DELETE FROM tp_cluster_command WHERE operator LIKE 'fleet-e2e-%'")


def _seed_fleet_commands(uniq):
    """34 ledger rows over the six-status value domain, sentinel newest.

    The core list read has no LIMIT and sorts newest-first by id, so the
    count assertion below is the DB total (baseline rows included) and the
    first rendered row is the sentinel we insert last (status 2 = EXECUTING,
    progress sentence attached). The receipt sentence rides a FAILED row
    (status 4) one cycle earlier — the same protocol shape as engine
    commands that finished with a reason. Status labels and the
    progress/receipt sentences must cross the bridge unchanged; the page
    derives no second verdict.
    """
    types = ("engine-install", "node-self-check", "scale-in", "node-remove")
    rows = []
    for i in range(COMMAND_ROWS):
        status = 2 if i == COMMAND_ROWS - 1 else i % 6
        operator = (
            f"fleet-e2e-{uniq}-latest"
            if i == COMMAND_ROWS - 1
            else f"fleet-e2e-{uniq}-c{i:03d}"
        )
        progress = (
            f"'{{\"message\":\"fleet-e2e-{uniq}-progress\"}}'::jsonb"
            if status == 2
            else "NULL"
        )
        receipt = (
            f"'fleet-e2e-{uniq}-receipt'" if status == 4 else "NULL"
        )
        # claimed_by is a bigint column: NULL stays an unquoted SQL NULL,
        # executor ids ride as unquoted numeric literals. Quoting NULL
        # turns it into the string 'NULL' and the insert is rejected.
        claimed = "NULL" if status == 0 else str(940000 + i)
        rows.append(
            f"('{types[i % 4]}','executor',{950000 + i},'{{}}'::jsonb,"
            f"'{status}',{claimed},{progress},{receipt},"
            f"now() + interval '30 minutes','{operator}')"
        )
    sql_query(
        "INSERT INTO tp_cluster_command "
        "(command_type, target_type, target_id, payload, status, claimed_by, "
        " last_progress, result_detail, expires_at, operator) VALUES "
        + ",".join(rows)
        + " RETURNING id"
    )
    # Re-read this run's ids (newest last) instead of parsing INSERT output,
    # so a psql -tAc format change cannot silently shift the anchor ids.
    ids = [
        int(v)
        for v in sql_query(
            "SELECT id FROM tp_cluster_command WHERE operator LIKE "
            f"'fleet-e2e-{uniq}-%' ORDER BY id"
        ).split()
    ]
    return ids


def _sweep_watermark_fixtures():
    """Remove watermark rows owned by this spec family.

    The fixture type tokens start with WME2E-, which the autoscaler sampler
    never writes (it records real executor types), so the pattern only ever
    matches this suite's own rows — never live history.
    """
    sql_query("DELETE FROM tp_cluster_pool_watermark WHERE executor_type LIKE 'WME2E-%'")


def _seed_watermarks(uniq):
    """52 samples over two pools (worst-case window, newest-first read).

    Values are plain fixture numbers; the curve test asserts card count and
    canvas presence while every plotted number stays a server-side observation
    (the page must not re-decide anything). sampled_at is back-dated per row
    so the two series interleave in time and sort ascending on the client.
    """
    types = [f"WME2E-{uniq}-A", f"WME2E-{uniq}-B"]
    rows = []
    for ti, etype in enumerate(types):
        for i in range(WATERMARK_SAMPLES):
            rows.append(
                f"('{etype}',{i + ti},{2 + (i // 8)},{1 + (i // 12)},"
                f"{i * 0.5 + ti},now() - make_interval(mins => {i * 3 + ti}))"
            )
    sql_query(
        "INSERT INTO tp_cluster_pool_watermark "
        "(executor_type, pending_dispatch_depth, current_replicas, "
        " active_executors, p95_wait_seconds, sampled_at) VALUES "
        + ",".join(rows)
    )
    return types


def _seed_components(uniq):
    node_ids = [f"fleet-e2e-node-{uniq}-{i}" for i in range(COMPONENT_ROWS)]
    values = ",".join(
        f"('{nid}','browser-engine','1.4{i}.0','1.4{i}.1',"
        f"'{['GREEN', 'YELLOW', 'RED', 'UNKNOWN'][i % 4]}','heartbeat',now())"
        for i, nid in enumerate(node_ids)
    )
    sql_query(
        "INSERT INTO tp_cluster_component "
        "(node_id, component_family, current_version, target_version, "
        " support_state, source, reported_at) VALUES " + values
    )
    return node_ids


def test_fleet_bands_render_ledger_truth_and_reload_atomic(
    logged_in, api_client, collectors, uniq
):
    """Four bands visible -> timeline worst-case fixture -> reload/route re-entry."""
    page = logged_in
    project_name = f"FleetBands-{uniq}"
    pid = seed_project(api_client, uniq, "FB", display_name=project_name)
    tag = api_client.post(
        "/tags",
        json={"projectId": pid, "name": f"FleetPool{uniq}", "type": 7},
    ).json()["data"]
    pool_id = tag["id"]
    pool_a = pool_b = None
    node_ids: list[str] = []
    wm_types: list[str] = []
    command_ids: list[int] = []
    try:
        _sweep_fleet_fixtures()
        _sweep_watermark_fixtures()
        _sweep_command_fixtures()
        pool_a, pool_b = _seed_scale_events(uniq)
        node_ids = _seed_components(uniq)
        wm_types = _seed_watermarks(uniq)
        command_ids = _seed_fleet_commands(uniq)
        expected_rows = POOL_A_ROWS + POOL_B_ROWS

        open_journey_leaf(page, "menu-executor-pools", "system")
        expect(page.locator('[data-test="pool-page"]')).to_be_visible(timeout=15000)

        # Band presence: all five fleet slices render on first mount.
        for band in ("fleet-scale-band", "fleet-matrix-band", "fleet-timeline-band",
                     "fleet-watermark-band", "fleet-commands-band"):
            expect(page.locator(f'[data-test="{band}"]')).to_be_visible(timeout=10000)

        # Elasticity band honest shape: either per-type cards (online executors)
        # or the honest empty note — both are valid states, silence is not.
        expect(
            page.locator(
                '[data-test^="fleet-scale-card-"], [data-test="fleet-scale-empty"]'
            ).first
        ).to_be_visible(timeout=10000)

        # Timeline: 32 seeded rows, newest sentinel first, reason verbatim.
        expect(page.locator('[data-test="fleet-timeline-table"]')).to_be_visible(timeout=10000)
        expect(page.locator(TIMELINE_ANCHOR)).to_have_count(expected_rows, timeout=10000)
        first_row = page.locator(TIMELINE_ANCHOR).first
        expect(first_row).to_contain_text(f"fleet-e2e-{uniq}-latest")
        expect(page.locator(f'[data-test="fleet-timeline-table"]:has-text("{pool_a}")')).to_be_visible()

        # Direction parity (R3 test truth): every rendered row's action token
        # — carried by its locale-independent anchor name — must agree with
        # the current→desired change as actually rendered in the table.
        import re as _re
        for i in range(expected_rows):
            change_text = page.locator(
                f'[data-test="fleet-timeline-change-{i}"]'
            ).inner_text()
            m = _re.search(r"(\d+)\s*→\s*(\d+)", change_text)
            assert m, f"timeline row {i}: unparseable change {change_text!r}"
            current_n, desired_n = int(m.group(1)), int(m.group(2))
            for token, agrees in (
                ("SCALE_OUT", current_n < desired_n),
                ("SCALE_IN", current_n > desired_n),
                ("HELD", current_n == desired_n),
            ):
                if page.locator(
                    f'[data-test="fleet-timeline-action-{token}-{i}"]'
                ).count():
                    assert agrees, (
                        f"timeline row {i}: badge {token} contradicts "
                        f"change {change_text!r}"
                    )
                    break
            else:
                pytest.fail(f"timeline row {i}: no known action badge rendered")

        # Watermark curve band: both seeded types each plot a lazily mounted
        # echarts canvas; every plotted point is the seeded server observation.
        for etype in wm_types:
            expect(page.locator(f'[data-test="fleet-watermark-card-{etype}"]')).to_be_visible(timeout=20000)
        expect(
            page.locator('[data-test^="fleet-watermark-card-"] canvas').first
        ).to_be_visible(timeout=20000)

        # Command ledger band: the DB total is the row truth (core list has
        # no LIMIT, newest-first). The sentinel seeded last is the newest id,
        # so it renders first with its progress sentence and EXECUTING label
        # crossing the bridge unchanged; the receipt sentence rides the
        # FAILED row. The band stays a bounded viewport under the
        # worst-case fixture (34 rows) like the timeline band.
        commands_total = int(sql_query("SELECT count(*) FROM tp_cluster_command"))
        expect(page.locator('[data-test="fleet-commands-table"]')).to_be_visible(timeout=10000)
        expect(page.locator(COMMANDS_ANCHOR)).to_have_count(commands_total, timeout=10000)
        first_cmd_row = page.locator(COMMANDS_ANCHOR).first
        expect(first_cmd_row).to_contain_text(f"fleet-e2e-{uniq}-progress")
        expect(first_cmd_row).to_contain_text("执行中")
        expect(
            page.locator(f'[data-test="fleet-command-status-{command_ids[-1]}"]')
        ).to_have_text("执行中", timeout=10000)
        expect(
            page.locator('[data-test="fleet-commands-table"]')
        ).to_contain_text(f"fleet-e2e-{uniq}-receipt", timeout=10000)
        band_height = page.locator(
            '[data-test="fleet-commands-table"]'
        ).bounding_box()["height"]
        assert band_height <= 600, (
            f"command ledger band must stay a bounded viewport "
            f"under 34 rows, measured {band_height}px"
        )

        # Residual-ticket close: the command band filter box is a live control,
        # not static text (the vision reviewer read it as prose). The progress
        # sentence rides every EXECUTING row, so the truth for a filtered view
        # is the DB count for that message, not a hand-guessed 1. Assert the
        # band narrows to exactly the DB truth, that narrowing really happened
        # (strictly below the unfiltered total), and that clearing restores the
        # full ledger — control position equals effect scope (R25), pinned at
        # browser level this time.
        filtered_truth = int(sql_query(
            "SELECT count(*) FROM tp_cluster_command "
            f"WHERE last_progress->>'message' = 'fleet-e2e-{uniq}-progress'"
        ))
        assert 0 < filtered_truth < commands_total, (
            "the seeded progress message must match a strict subset "
            f"of the ledger, got {filtered_truth}/{commands_total}"
        )
        commands_filter = page.locator('[data-test="fleet-commands-filter"]')
        expect(commands_filter).to_be_visible(timeout=10000)
        commands_filter.fill(f"fleet-e2e-{uniq}-progress")
        expect(page.locator(COMMANDS_ANCHOR)).to_have_count(
            filtered_truth, timeout=10000)
        expect(page.locator(COMMANDS_ANCHOR).first).to_contain_text(
            f"fleet-e2e-{uniq}-progress")
        commands_filter.fill("")
        expect(page.locator(COMMANDS_ANCHOR)).to_have_count(
            commands_total, timeout=10000)

        # Node matrix: the band renders the ledger truth for this slot (the
        # factory baseline may carry components of its own), through the shared
        # badge single source; the write trio stays disabled with the honest
        # operation note.
        matrix_total = int(sql_query("SELECT count(*) FROM tp_cluster_component"))
        expect(page.locator('[data-test="fleet-matrix-table"]')).to_be_visible(timeout=10000)
        expect(page.locator(
            '[data-test="fleet-matrix-table"] .el-table__body tbody tr'
        )).to_have_count(matrix_total, timeout=10000)
        for action in ("fleet-action-scale-out", "fleet-action-drain", "fleet-action-pause"):
            expect(page.locator(f'[data-test="{action}"]')).to_be_disabled()

        # Refresh reloads every band under one command: the pool card seeded
        # after mount appears while the timeline keeps its full row count.
        page.locator('[data-test="pool-refresh"]').first.click()
        expect(page.locator(f'[data-test="pool-card-{pool_id}"]')).to_be_visible(timeout=10000)
        expect(page.locator(TIMELINE_ANCHOR)).to_have_count(expected_rows, timeout=10000)
        expect(page.locator(COMMANDS_ANCHOR)).to_have_count(commands_total, timeout=10000)

        # Route re-entry re-reads DB truth (no front-end-only state).
        page.goto(page.url.split("#")[0] + "#/dashboard", wait_until="domcontentloaded")
        page.goto(page.url.split("#")[0] + "#/execution/pools", wait_until="domcontentloaded")
        expect(page.locator(TIMELINE_ANCHOR)).to_have_count(expected_rows, timeout=15000)

        # Cleanup first, then one reload proves the ledger drives the band.
        sql_query(
            "DELETE FROM tp_cluster_scale_event WHERE pool_key IN "
            f"('{pool_a}','{pool_b}')"
        )
        sql_query(
            "DELETE FROM tp_cluster_component WHERE node_id IN ("
            + ",".join(f"'{n}'" for n in node_ids)
            + ")"
        )
        sql_query(f"DELETE FROM tp_cluster_command WHERE id IN ({','.join(map(str, command_ids))})")
        purge_project(api_client, pid)
        page.locator('[data-test="pool-refresh"]').first.click()
        expect(page.locator('[data-test="fleet-timeline-empty"]')).to_be_visible(timeout=10000)
        expect(page.locator(f'[data-test="pool-card-{pool_id}"]')).to_have_count(0, timeout=10000)
        # The sentinel row leaves the ledger on reload (DB drives the band;
        # no front-end-only state). With the seeded rows gone the render
        # must equal whatever the slot DB still holds — zero means the
        # honest empty note, non-zero keeps the table truth.
        remaining = int(sql_query("SELECT count(*) FROM tp_cluster_command"))
        if remaining == 0:
            expect(page.locator('[data-test="fleet-commands-empty"]')).to_be_visible(timeout=10000)
        else:
            expect(page.locator(COMMANDS_ANCHOR)).to_have_count(remaining, timeout=10000)
            expect(
                page.locator('[data-test="fleet-commands-table"]')
            ).not_to_contain_text(f"fleet-e2e-{uniq}-latest", timeout=10000)

        assert_no_errors(collectors, "fleet-bands")
    finally:
        # Guards: an interrupted seed leaves these empty and the IN lists
        # would build invalid SQL; the sweep above already namespaces rows.
        if pool_a:
            sql_query(
                "DELETE FROM tp_cluster_scale_event WHERE pool_key IN "
                f"('{pool_a}','{pool_b}')"
            )
        if node_ids:
            sql_query(
                "DELETE FROM tp_cluster_component WHERE node_id IN ("
                + ",".join(f"'{n}'" for n in node_ids)
                + ")"
            )
        if command_ids:
            sql_query(
                "DELETE FROM tp_cluster_command WHERE id IN ("
                + ",".join(map(str, command_ids))
                + ")"
            )
        if pid:
            try:
                purge_project(api_client, pid)
            except Exception:
                pass
