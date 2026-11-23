"""E2E for the cost dashboard per-session attribution lane .

Real browser + real admin -> core -> PG chain (strict-testing-standards sec.3):
 1. the attribution table renders rows for the current month (dev baseline
 carries chat sessions with token-bearing assistant turns);
 2. reconciliation hard assertion: the windowTokens the page shows for a row
 equals the independent recompute of that session's message ledger in the
 same month window (same formula core uses -- prompt+completion sums over
 created_at window) -- the page must not drift from the ledger;
 3. the lifetime column travels next to the window column (drift pair is
 visible, not re-derived by the browser);
 4. three-zero posture: no 4xx/5xx, no console error/warn, no browser issues.
"""

from conftest import (
    assert_no_errors,
    nav_to,
    sql_query,
)
from playwright.sync_api import expect


def _current_month() -> str:
    return sql_query("SELECT to_char(date_trunc('month', now()), 'YYYY-MM')").strip()


def test_cost_session_attribution_reconciles_with_ledger(logged_in, collectors):
    """Attribution row tokens == independent message-ledger recompute (month window)."""
    page = logged_in
    month = _current_month()

    # A session with in-month token-bearing assistant turns must exist, else the
    # lane renders nothing to reconcile against -- seed one through the ledger
    # the same way l3_cost_session_usage_tick1775 observed real rows for.
    sid = sql_query(
        "SELECT agg.session_id FROM ("
        "  SELECT m.session_id,"
        "         count(*) FILTER (WHERE m.role='assistant' AND (m.prompt_tokens>0 OR m.completion_tokens>0)) AS turns,"
        "         COALESCE(SUM(m.prompt_tokens + m.completion_tokens),0) AS window_tokens"
        "  FROM tp_ai_chat_message m"
        f"  WHERE m.created_at >= '{month}-01' AND m.created_at < (date '{month}-01' + interval '1 month')"
        "  GROUP BY m.session_id) agg"
        " WHERE agg.turns > 0 AND agg.window_tokens > 0"
        " ORDER BY agg.window_tokens DESC LIMIT 1"
    ).strip()
    assert sid, f"no session with in-month tokens for {month}; dev baseline missing (run ensure_baseline)"
    expected = int(sql_query(
        "SELECT COALESCE(SUM(m.prompt_tokens + m.completion_tokens),0)"
        " FROM tp_ai_chat_message m"
        f" WHERE m.session_id={sid}"
        f"   AND m.created_at >= '{month}-01' AND m.created_at < (date '{month}-01' + interval '1 month')"
    ).strip())

    nav_to(page, "成本中心", "/cost", '[data-test="cost-dashboard-page"]', submenu="系统管理")
    table = page.locator('[data-test="cost-dim-session"]')
    expect(table).to_be_visible(timeout=10000)

    # The reconciliation row may not sit on page 1 of the table (desc order by
    # window tokens) -- the seeded ledger top row is the largest, so page 1
    # first row must be exactly it.
    first_row = table.locator(".el-table__body tbody tr").first
    expect(first_row).to_be_visible(timeout=10000)
    shown_window = int((first_row.locator('[data-test="cost-session-window"]').inner_text() or "0").replace(",", ""))
    assert shown_window == expected, (
        f"attribution window tokens drift: page={shown_window} ledger={expected} session={sid}"
    )
    shown_lifetime = int((first_row.locator('[data-test="cost-session-lifetime"]').inner_text() or "0").replace(",", ""))
    # lifetime ledger travels next to the window column; both come from core,
    # the page never re-derives them (red line 6 posture = value present & ≥ window)
    assert shown_lifetime >= shown_window, (
        f"lifetime column smaller than window column (session total not backfilled?): {shown_lifetime} < {shown_window}"
    )

    assert_no_errors(collectors, "cost session attribution reconciliation")
