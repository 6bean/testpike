"""Dead-case handoff journey (reported shape 6048-type): a case detail link that
cannot resolve must keep its guidance on screen, typed by the server's own
classification, and the recycle-bin door may only appear when a delete record
actually exists.

Covers the live chain end to end (vite -> admin -> Feign -> core -> PG, no
mocks):
- deleted case (delete record exists) -> typed "case deleted" sentence with
  BOTH the library button and the recycle-bin door;
- id that never existed (no delete record) -> typed "not located" sentence
  with ONLY the library button (the contract of this packet: no dead pointer
  into an empty recycle bin, R63);
- /cases?recycle=1 landing opens the recycle drawer for the handoff click.

Fixture data is created and deleted through the real API with a throwaway
suffix; the never-existed id uses an unreachable high constant so no delete
ledger row can exist for it.
"""
from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, whitelist_expected_reject


def _declare_dead_id_misses(collectors, case_id):
    """Declare the three authoritative misses a dead case id produces.

    The detail page fetches the case, its script and its defect list on mount;
    for a gone id the server answers 404 on each one and that 404 is exactly
    the signal the typed guidance reads (case gone + delete-ledger lookup).
    A GET 404 on a removed resource is correct REST semantics, not an app
    error, so the spectrum names the three endpoints and asserts each drop
    count == 1 (per the shared per-endpoint helper: a missing 404 can never
    turn into a silent pass, and every other 4xx still fails the test).
    """
    for suffix, label in (
        (f"/cases/{case_id}/script", "script"),
        (f"/defects?caseId={case_id}", "defects"),
        (f"/cases/{case_id}", "case"),
    ):
        got = whitelist_expected_reject(collectors, suffix, status=404)
        assert got == 1, f"expected exactly one 404 for the dead-id {label} fetch"


def _first_project(api_client):
    data = api_client.get("/projects").json().get("data") or {}
    items = data.get("items") or data.get("records") or data.get("list") or []
    assert items, "no visible project for E2E seeding"
    return int(items[0]["id"])


def test_deleted_case_keeps_typed_guidance_with_recycle_door(
    logged_in, api_client, collectors, uniq
):
    """Delete-record branch: typed sentence + both actions stay on screen."""
    pid = _first_project(api_client)
    title = f"死链承接旅程-删除账用例-{uniq}"
    cr = api_client.post("/cases", json={"projectId": pid, "title": title})
    assert cr.status_code == 200, f"case create failed: {cr.text}"
    case_id = int(cr.json()["data"]["id"])

    dr = api_client.delete(f"/cases/{case_id}")
    assert dr.status_code == 200, f"case delete failed: {dr.text}"

    page = logged_in
    page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")

    card = page.locator('[data-test="case-load-error"]')
    expect(card).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="case-load-failure-msg"]')).to_contain_text(
        "用例已被删除", timeout=10000
    )
    expect(page.locator('[data-test="case-failure-goto-library"]')).to_be_visible()
    # Contract assertion: the recycle door is offered only on this branch.
    expect(page.locator('[data-test="case-failure-open-recycle"]')).to_be_visible()
    _declare_dead_id_misses(collectors, case_id)
    assert_no_errors(collectors)


def test_never_existed_case_says_not_located_without_recycle_door(
    logged_in, collectors
):
    """No-delete-record branch: the empty recycle bin is never advertised."""
    page = logged_in
    page.goto(f"{BASE_URL}/#/cases/999999999", wait_until="domcontentloaded")

    card = page.locator('[data-test="case-load-error"]')
    expect(card).to_be_visible(timeout=15000)
    expect(page.locator('[data-test="case-load-failure-msg"]')).to_contain_text(
        "无此记录也无删除痕迹", timeout=10000
    )
    expect(page.locator('[data-test="case-failure-goto-library"]')).to_be_visible()
    expect(page.locator('[data-test="case-failure-open-recycle"]')).to_have_count(0)
    _declare_dead_id_misses(collectors, 999999999)
    assert_no_errors(collectors)


def test_recycle_query_lands_with_the_drawer_open(
    logged_in, api_client, collectors
):
    """Handoff target: ?recycle=1 opens the recycle drawer on arrival."""
    pid = _first_project(api_client)
    page = logged_in
    page.goto(
        f"{BASE_URL}/#/cases?projectId={pid}&recycle=1", wait_until="domcontentloaded"
    )
    drawer = page.locator(".el-drawer")
    expect(drawer.first).to_be_visible(timeout=15000)
    assert_no_errors(collectors)
