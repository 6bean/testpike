"""Requirement-echo journey: the case-detail "owning requirement" block echoes real
links and a row click lands on the requirement ledger filtered to that point.

Covers the reported shape end to end (vite -> admin -> Feign -> core -> PG, no
mocks): a generated case (tp_test_case.source=1) linked to a requirement point
must render the requirement id tag (#<id>) and the generation auto-link
provenance tag, then jump through the existing ids URL filter channel (no
second list-state channel). Fixture data is created through the real API with
a throwaway suffix and removed in finally; the only direct DB touch is the
source=1 pin mimicking the generation pipeline's own column value (the create
API has no source field), bound to this chain's slot database through the
single-source bootstrap (db_target + _l3_db), never the main slot.
"""
import re

from playwright.sync_api import expect

from conftest import BASE_URL, assert_no_errors, slot_psql


def _first_project(api_client):
    data = api_client.get("/projects").json().get("data") or {}
    items = data.get("items") or data.get("records") or data.get("list") or []
    assert items, "no visible project for E2E seeding"
    return int(items[0]["id"])


def test_case_detail_requirement_echo_and_ledger_jump(
    logged_in, api_client, collectors, uniq
):
    """929066-type echo: #id + provenance tag + row jump to the ids filter."""
    psql = slot_psql()
    pid = _first_project(api_client)
    req_title = f"回显旅程需求点-{uniq}"
    case_title = f"回显旅程用例-{uniq}"

    rr = api_client.post(f"/projects/{pid}/requirements", json={
        "title": req_title, "acceptanceCriteria": f"ac-{uniq}", "chapter": "01"})
    assert rr.status_code == 200, f"requirement create failed: {rr.text}"
    req_id = int(rr.json()["data"]["id"])

    cr = api_client.post("/cases", json={"projectId": pid, "title": case_title})
    assert cr.status_code == 200, f"case create failed: {cr.text}"
    case_id = int(cr.json()["data"]["id"])

    lr = api_client.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
    assert lr.status_code == 200, f"link failed: {lr.text}"

    # Pin the generation source the way the pipeline does (source 1 = AI
    # generated); this is what switches on the provenance tag.
    psql(f"UPDATE tp_test_case SET source=1 WHERE id={case_id}")

    try:
        page = logged_in
        page.goto(f"{BASE_URL}/#/cases/{case_id}", wait_until="domcontentloaded")

        table = page.locator('[data-test="case-req-table"]')
        expect(table).to_be_visible(timeout=15000)
        # Echo of the linked point: title, #id chip, provenance tag.
        expect(table.get_by_text(req_title).first).to_be_visible(timeout=10000)
        expect(table.locator("td", has_text=f"#{req_id}").first).to_be_visible()
        expect(page.locator('[data-test="req-provenance-auto"]')).to_have_count(1)
        expect(page.locator('[data-test="case-req-empty"]')).to_have_count(0)

        # Row click rides the ledger ids filter channel and lands on one row.
        table.locator(".el-table__body tr").first.click()
        expect(page).to_have_url(
            re.compile(rf"/requirements\??.*ids={req_id}"), timeout=15000)
        ledger_rows = page.locator(".layout-main .el-table__body tr").filter(
            has_text=req_title)
        expect(ledger_rows).to_have_count(1, timeout=15000)
        assert_no_errors(collectors, "t703 req echo journey")
    finally:
        api_client.delete(f"/cases/{case_id}")
        api_client.delete(f"/projects/{pid}/requirements/{req_id}")
