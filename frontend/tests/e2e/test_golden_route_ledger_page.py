"""Golden replay ledger on the LLM config page .

Real chain end to end: the ledger conclusion table has no write API by design
(only the replay gate registers verdict rows), so worst-scale fixtures are
inserted directly into tp_ai_golden_drift_verdict and purged by captured id
(R37 factory baseline untouched, same direct-insert shape as the flaky-ledger
spec). The page surface itself is exercised through the admin bridge endpoint
GET /api/v1/llm-configs/route-replays:
 - namespace scoping: each spec seeds its own run-unique template_domain and
 asserts inside that scope only — a whole-ledger window is nondeterministic
 whenever two specs share the table in parallel (two such cross-seedings
 were observed while writing this spec),
 - route-name filter -> only that route's rows survive, page 2 the overflow,
 - one-click adopt from a model row -> dialog opens prefilled; a model with no
 verdict shows the honest empty state naming ai.behavior.drift-block-threshold,
 - three zero-tolerance checks (4xx/5xx, console error/warning, browser Issues).
"""
import os

import psycopg2
import pytest
from conftest import assert_no_errors, nav_to
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PAGE_SIZE = 20


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


def _seed_namespace(domain, rows_per_route=30):
    """Route-axis verdict rows for one run-unique template domain.

    Returns captured ids; the caller owns the purge. Route names derive from
    the same domain suffix, so filling the domain isolates a whole spec."""
    conn = psycopg2.connect(dsn())
    conn.autocommit = True
    cur = conn.cursor()
    ids = []
    for axis in ("a", "b"):
        route = f"{domain}-{axis}"
        # Route-axis contract (pinned by the core gate test): template_domain
        # carries the real template family domain, the switch sides live in
        # control_ref / candidate_ref — the domain filter is exact-match.
        for i in range(rows_per_route):
            cur.execute(
                """
                INSERT INTO tp_ai_golden_drift_verdict
                    (template_domain, template_name, candidate_version_id,
                     control_version_id, pack_name, pack_version, snapshot_digest,
                     sample_total, drifted_count, drift_percent_pp, threshold_pp,
                     pass_flag, note, comparison_kind, control_ref, candidate_ref)
                VALUES (%s, %s, 9000, 9001,
                        'ut-drift-pack', 1,
                        rpad(%s::text, 64, '0'),
                        40, %s, %s, %s, %s, 'E2E 夹具', 'route',
                        'ut-model-old', %s)
                RETURNING id""",
                (domain, route, i, i % 5,
                 format(i % 5 * 1.25, ".2f"), 15, 1 if i % 5 == 0 else 0, route),
            )
            ids.append(cur.fetchone()[0])
    cur.close()
    conn.close()
    return ids


def _purge(ids):
    conn = psycopg2.connect(dsn())
    conn.autocommit = True
    cur = conn.cursor()
    cur.execute("DELETE FROM tp_ai_golden_drift_verdict WHERE id = ANY(%s)", (ids,))
    cur.close()
    conn.close()


@pytest.fixture()
def drift_ledger(uniq):
    """60 worst-scale verdict rows (30 + 30) inside one run-unique domain."""
    domain = f"ut-drift-{uniq}"
    ids = _seed_namespace(domain)
    try:
        yield domain, ids
    finally:
        _purge(ids)


def _open_ledger(page):
    nav_to(page, "大模型配置", "/llm-configs", "[data-test='llm-config-page']", submenu="系统管理")
    page.locator("[data-test='golden-ledger-open']").click()
    dialog = page.locator("[data-test='golden-ledger-dialog']")
    expect(dialog).to_be_visible()
    return dialog


def _model(api_client, tag):
    name = f"UtDriftNoVerdict{tag}"
    r = api_client.post("/llm-configs", {
        "name": name, "provider": "deepseek", "modelName": "deepseek-chat",
        "apiKey": "e2e-***", "baseUrl": "https://api.deepseek.com/v1",
        "status": 1, "isDefault": 0, "description": "T544 台账夹具模型",
    })
    assert r.status_code == 200, f"create model: {r.status_code} {r.text[:200]}"
    return name


def _drop_model(api_client, name):
    r = api_client.get("/llm-configs")
    for x in r.json().get("data", []):
        if x.get("name") == name:
            api_client.delete(f"/llm-configs/{x['id']}")


def test_golden_ledger_pages_and_filters_at_worst_scale(
        logged_in, collectors, uniq, drift_ledger):
    """Namespace of 60 rows: domain window, route filter, second page."""
    page = logged_in
    domain, _ids = drift_ledger
    dialog = _open_ledger(page)
    table = dialog.locator("[data-test='golden-ledger-table']")
    expect(table).to_be_visible()
    dialog.locator("[data-test='golden-ledger-domain-input']").fill(domain)
    dialog.locator("[data-test='golden-ledger-search']").click()

    # The newest-first window inside the namespace is one page of 20, total 60.
    expect(table.locator(".el-table__body tr.el-table__row")).to_have_count(PAGE_SIZE)
    pager = dialog.locator(".el-pagination")
    expect(pager).to_be_visible()
    expect(pager.locator(".el-pagination__total")).to_contain_text("60")

    # Filter to one route: 30 rows over two windows.
    dialog.locator("[data-test='golden-ledger-route-input']").fill(f"{domain}-a")
    dialog.locator("[data-test='golden-ledger-search']").click()
    expect(table.locator(".el-table__body tr.el-table__row")).to_have_count(PAGE_SIZE)
    expect(pager.locator(".el-pagination__total")).to_contain_text("30")

    # Page 2 carries the remaining window of the same route.
    pager.get_by_role("listitem", name="2").click()
    expect(table.locator(".el-table__body tr.el-table__row")).to_have_count(10)

    # Back to the namespace window: both fixture routes mixed in.
    dialog.locator("[data-test='golden-ledger-route-input']").fill("")
    dialog.locator("[data-test='golden-ledger-search']").click()
    expect(table.locator(".el-table__body tr.el-table__row")).to_have_count(PAGE_SIZE)

    assert_no_errors(collectors, "golden ledger worst-scale paging")
    assert_no_browser_issues(collectors.get("browser_issues", []), "golden ledger paging")


def test_adopt_from_row_opens_ledger_and_empty_state_points_key(
        logged_in, collectors, api_client, uniq):
    """One-click adopt: model row opens the ledger prefilled; no verdict -> honest
    empty state naming the threshold key (AI-feature off/empty guidance law)."""
    page = logged_in
    # Own namespace (uniq + "x"): the paging spec seeds the same route shape
    # with the bare uniq suffix and runs in parallel — disjoint domains keep
    # both assertions deterministic.
    name = _model(api_client, uniq + "x")
    ledger_ids = _seed_namespace(f"ut-drift-{uniq}x", rows_per_route=10)
    try:
        nav_to(page, "大模型配置", "/llm-configs", "[data-test='llm-config-page']", submenu="系统管理")
        row = page.locator("[data-test='llm-table'] .el-table__body tr.el-table__row",
                           has_text=name).first
        expect(row).to_be_visible()
        row.locator("[data-test='llm-adopt-ledger']").click()
        dialog = page.locator("[data-test='golden-ledger-dialog']")
        expect(dialog).to_be_visible()
        expect(dialog.locator("[data-test='golden-ledger-route-input']")).to_have_value(name)
        expect(dialog.locator("[data-test='golden-ledger-table'] .el-table__body tr.el-table__row")
               .first).to_have_count(0)
        hint = dialog.locator(".empty-state__hint")
        expect(hint).to_be_visible()
        expect(hint).to_contain_text("ai.behavior.drift-block-threshold")
        assert_no_errors(collectors, "golden ledger adopt empty state")
        assert_no_browser_issues(collectors.get("browser_issues", []), "golden ledger adopt")
    finally:
        _drop_model(api_client, name)
        _purge(ledger_ids)
