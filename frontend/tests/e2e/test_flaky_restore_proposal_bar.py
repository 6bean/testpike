"""Flaky restore proposal bar on the case list .

Real chain end to end: fixture project created through the admin API, quarantine
ledger advanced to state=1 through the live flakyProbeSweep scheduler (no manual
tp_flaky_probe rows - same recipe as the internal flaky-probe L3 chain),
then the page surface is exercised in a real browser:
 - single-proposal posture (bar + item visible without any expand click),
 - multi-proposal posture (expand -> item count equals the read-channel count,
 scroll containment measured on the list container = worst-scale posture),
 - one-click accept reuses PUT /cases/{id}/flaky (isolated=false), pinned via
 reload + DB (case flaky=0, ledger row leaves state=1 through the next sweep),
 - three zero-tolerance checks (4xx/5xx, console error/warning, browser Issues).
Cleanup purges the scratch project by id only (R37 factory baseline untouched).
"""
import os
import time

import psycopg2
import pytest
from conftest import BASE_URL, assert_no_errors, pick_project_in_header
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
SWEEP = "/scheduler/run-now?job=flakyProbeSweep"


def dsn():
    env = {}
    with open(os.path.join(REPO_ROOT, ".env")) as fh:
        for line in fh:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"')
    url = env["DB_URL"].replace("jdbc:postgresql://", "").replace("jdbc:", "")
    return f"postgresql://{env['DB_USERNAME']}:{env['DB_PASSWORD']}@{url}"


def db_case_flags(cur, case_id):
    cur.execute("SELECT flaky, deleted FROM tp_test_case WHERE id=%s", (case_id,))
    return cur.fetchone()


def propose_case(cur, api, project_id, uniq, tag, green_count):
    """Quarantine-shaped case -> sweep opens the ledger -> live green bar -> state=1.

    Every ledger transition goes through the real scheduler; only the case/script
    fixtures and the post-quarantine green runs (same shapes the L2 suite seeds)
    are inserted directly, exactly as the green L3 chain recipe does."""
    cur.execute(
        """
        INSERT INTO tp_test_case (case_no, project_id, title, status, flaky,
                                  flaky_reason, flaky_marked_at, assignee_id)
        VALUES (%s, %s, %s, 7, 1, '连续结果翻转', now(),
                (SELECT id FROM tp_user WHERE username = 'admin')) RETURNING id""",
        (f"FLK-{uniq}-{tag}", project_id, f"回探转正夹具 {tag}"),
    )
    case_id = cur.fetchone()[0]
    cur.execute(
        "INSERT INTO tp_script (case_id, schema_version, action_json, status)"
        " VALUES (%s, 'v2', '{\"probeFixture\": true}', 1)",
        (case_id,),
    )
    r = api.post(SWEEP)
    assert r.status_code == 200, f"sweep(open) status={r.status_code} {r.text[:200]}"
    probe_id = None
    for _ in range(30):
        cur.execute("SELECT id FROM tp_flaky_probe WHERE case_id=%s AND state IN (0,1)", (case_id,))
        row = cur.fetchone()
        if row:
            probe_id = row[0]
            break
        time.sleep(3)
    assert probe_id is not None, f"ledger session never opened for case {case_id}"
    for _ in range(green_count):
        cur.execute(
            "INSERT INTO tp_exec_task (project_id, case_id, status, started_at, finished_at)"
            " VALUES (%s, %s, 3, now(), now())",
            (project_id, case_id),
        )
    r = api.post(SWEEP)
    assert r.status_code == 200, f"sweep(fold) status={r.status_code} {r.text[:200]}"
    for _ in range(30):
        cur.execute("SELECT state FROM tp_flaky_probe WHERE id=%s", (probe_id,))
        if cur.fetchone()[0] == 1:
            return case_id, probe_id
        time.sleep(3)
    pytest.fail(f"ledger session {probe_id} never reached state=1 for case {case_id}")


def test_flaky_restore_proposal_bar(logged_in, collectors, api_client, uniq):
    page = logged_in
    name = f"FlakyBar{uniq}"
    r = api_client.post("/projects", {"name": name, "code": f"T441E2E{uniq}"})
    assert r.status_code == 200, f"create project: {r.status_code} {r.text[:200]}"
    data = r.json()["data"]
    project_id = data if isinstance(data, int) else data["id"]

    conn = psycopg2.connect(dsn())
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO tp_project_env (project_id, name, env_type, base_url)"
        " VALUES (%s, 'e2e-env', 1, 'http://e2e-probe.invalid')",
        (project_id,),
    )
    cur.execute("SELECT config_value FROM tp_sys_config WHERE config_key = 'ai.flaky.probe.green-count'")
    row = cur.fetchone()
    green_count = int(row[0]) if row else 5
    try:
        case1, _probe1 = propose_case(cur, api_client, project_id, uniq, "A", green_count)

        # Browser: header segment -> case list -> bar with a single proposal, no expand needed
        pick_project_in_header(page, name)
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        bar = page.locator('[data-test="flaky-proposal-bar"]')
        expect(bar).to_be_visible(timeout=20000)
        expect(page.locator('[data-test="flaky-proposal-summary"]')).to_contain_text("1")
        expect(page.locator('[data-test="flaky-proposal-toggle"]')).to_have_count(0)
        items = page.locator('[data-test="flaky-proposal-item"]')
        expect(items).to_have_count(1, timeout=10000)
        expect(items.first.locator('[data-test="case-flaky-restore-badge"]')).to_be_visible()
        expect(page.locator(f'[data-test="flaky-proposal-case-no-{case1}"]')).to_contain_text(f"FLK-{uniq}-A")
        expect(page.locator(f'[data-test="flaky-proposal-confirm-{case1}"]')).to_be_visible()

        # Second proposal -> refresh -> expand -> count hard-equals backend rows,
        # and the list container carries the scroll containment (worst-scale posture)
        case2, probe2 = propose_case(cur, api_client, project_id, uniq, "B", green_count)
        page.locator('[data-test="flaky-proposal-refresh"]').click()
        expect(page.locator('[data-test="flaky-proposal-summary"]')).to_contain_text("2", timeout=10000)
        page.locator('[data-test="flaky-proposal-toggle"]').click()
        expect(items).to_have_count(2, timeout=10000)
        box = page.evaluate(
            """() => { const el = document.querySelector('[data-test="flaky-proposal-list"]');
                       const cs = getComputedStyle(el);
                       return { maxH: cs.maxHeight, overflowY: cs.overflowY }; }"""
        )
        assert box["maxH"] not in ("", "none") and box["overflowY"] == "auto", f"containment missing: {box}"

        # One-click accept on case2 -> bar back to 1 -> reload + DB pin -> sweep closes the ledger
        page.locator(f'[data-test="flaky-proposal-confirm-{case2}"]').click()
        expect(page.locator('[data-test="flaky-proposal-summary"]')).to_contain_text("1", timeout=15000)
        page.goto(f"{BASE_URL}/#/cases", wait_until="domcontentloaded")
        expect(bar).to_be_visible(timeout=20000)
        expect(items).to_have_count(1, timeout=10000)
        expect(page.locator(f'[data-test="flaky-proposal-case-no-{case2}"]')).to_have_count(0)
        assert db_case_flags(cur, case2) == (0, 0), "accepted case must have flaky=0, deleted=0"
        assert db_case_flags(cur, case1) == (1, 0), "untouched case keeps its isolation"
        r = api_client.post(SWEEP)
        assert r.status_code == 200
        state = None
        for _ in range(20):
            cur.execute("SELECT state, closed_at IS NOT NULL FROM tp_flaky_probe WHERE id=%s", (probe2,))
            state, closed = cur.fetchone() or (None, False)
            if state == 2 and closed:
                break
            time.sleep(3)
        assert state == 2, f"released ledger row must leave state=1 (closed), got {state}"

        assert_no_errors(collectors, "flaky restore proposal bar")
        assert_no_browser_issues(collectors["browser_issues"], "flaky restore proposal bar")
    finally:
        # purging via the admin API is the R37-safe door (by project id only),
        # same posture as the conftest scratch-project precedent
        rr = api_client.delete(f"/projects/{project_id}/purge?force=true")
        assert rr.status_code == 200, f"purge scratch project failed: {rr.text[:200]}"
        conn.close()
