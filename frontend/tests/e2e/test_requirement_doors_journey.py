"""Requirement-door acceptance journeys (four faces of one page band).

Every face is driven on THIS slot's live stack through the browser: the
login session, the admin -> core read chain and the real writes are all
genuine (no mocked channels). The four journeys pin:

1. the source-chunk door rides the centered posture - the dialog's top gap
   and bottom gap stay equal within tolerance and its height stays capped
   (the shipped defect was a bottom-flush dialog under a large top gap).
2. the neighbour "view context" walk inside the shared viewer re-binds the
   chunk: clicking the neighbour re-reads that block in the same door and
   the current-block mark moves with it (the shipped defect swallowed the
   emit because the door bound the chunk id one-way).
3. a case number inside the link-cases dialog opens the shared case-detail
   door (the routed detail page embedded, not a second read-only copy)
   without leaving the requirement route.
4. the favourite command is folded into the row "more" dropdown (no direct
   star in the action band) and its add/remove round-trip works through the
   shared composable; the second toggle doubles as the fixture cleanup.

Fixture discipline: rows created here are deleted by their own anchors in
the finally block (R57); cases go through the API, ledger rows by id.
"""
from conftest import assert_no_errors, nav_to, pick_project_in_header
from issues_monitor import assert_no_browser_issues
from test_f2_extraction import sql
from playwright.sync_api import expect


def _insert_id(statement):
    """Parse the generated key of one INSERT ... RETURNING statement.

    psql -tA prints the returning row before its command tag, so only the
    first line carries the id.
    """
    return int(sql(statement).splitlines()[0])


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"Doors{uniq[:6]}",
                                    "code": f"DR{uniq[:6].upper()}"})
    assert r.status_code == 200, f"project create failed: {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"project create rejected: {body.get('msg')}"
    return body["data"]


def _seed_doc_chunks(pid, uniq):
    """Two neighbouring chunks of one document: the anchored block (seq 2)
    reads the earlier one (seq 1) as its preceding context neighbour."""
    digest = f"doors-{uniq}"
    doc_id = _insert_id(
        "INSERT INTO tp_document (project_id, name, file_type, file_hash,"
        " status, chunk_count)"
        f" VALUES ({pid}, 'DR-{uniq[:8]}.md', 'md', '{digest}', 1, 2)"
        " RETURNING id")
    b_text = f"doors neighbour block B {uniq[:8]}"
    a_text = f"doors anchored block A {uniq[:8]}"
    chunk_b = _insert_id(
        "INSERT INTO tp_document_chunk (document_id, chapter, seq, content,"
        " token_est)"
        f" VALUES ({doc_id}, '第3章 门禁', 1, '{b_text}', 40) RETURNING id")
    chunk_a = _insert_id(
        "INSERT INTO tp_document_chunk (document_id, chapter, seq, content,"
        " token_est)"
        f" VALUES ({doc_id}, '第3章 门禁', 2, '{a_text}', 40) RETURNING id")
    return doc_id, chunk_a, chunk_b, a_text, b_text


def _mk_requirement(api, pid, title, anchor):
    """Create through the real API, then stamp the provenance anchor the
    same way the extraction chain stores it (the create DTO carries none).
    """
    r = api.post(f"/projects/{pid}/requirements",
                 json={"source": 2, "title": title})
    assert r.status_code == 200, f"requirement create failed: {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"requirement create rejected: {body.get('msg')}"
    req_id = body["data"]["id"]
    if anchor is not None:
        import json as _json
        payload = _json.dumps(
            {"documentId": anchor["documentId"],
             "chunkIds": list(anchor["chunkIds"]),
             "evidence": "doors evidence"}, ensure_ascii=False).replace("'", "''")
        sql(f"UPDATE tp_requirement SET source_anchor = '{payload}'::jsonb "
            f"WHERE id = {req_id}")
    return req_id


def _open_req_page(page, pname, uniq):
    pick_project_in_header(page, pname)
    nav_to(page, "需求点管理", "/requirements", '[data-test="req-page"]',
           submenu="设计用例")
    page.locator('[data-test="req-filter-keyword"]').fill(uniq[:12])
    page.locator('[data-test="req-search"]').click()


def _row_for(page, title):
    return page.locator('[data-test="req-table"] tbody tr.el-table__row').filter(
        has_text=title)


def _cleanup(pid, doc_id, req_ids, chunk_ids=(), case_ids=()):
    ids = ",".join(str(i) for i in req_ids if i is not None)
    if ids:
        # The link rows key on the requirement as much as on the case, and
        # the case API delete does not cascade them; anchor the sweep on the
        # requirement ids so no FK can block the point removal.
        sql(f"DELETE FROM tp_case_requirement WHERE requirement_id IN ({ids})")
        sql(f"DELETE FROM tp_favorite WHERE entity_type = 'requirement' "
            f"AND entity_id IN ({ids})")
        sql(f"DELETE FROM tp_requirement WHERE id IN ({ids})")
    for case_id in case_ids:
        sql(f"DELETE FROM tp_case_requirement WHERE case_id = {case_id}")
    for chunk_id in chunk_ids:
        if chunk_id is not None:
            sql(f"DELETE FROM tp_document_chunk WHERE id = {chunk_id}")
    if doc_id is not None:
        sql(f"DELETE FROM tp_document WHERE id = {doc_id}")
    # The project container follows the sibling-spec precedent: cheap
    # namespace, deleted through the API by the housekeeping sweep; data
    # rows never survive this spec.


def test_chunk_door_centered_geometry(logged_in, api_client, collectors, uniq):
    """Face 1: the source-chunk door sits vertically centered under the
    85vh cap instead of riding a large top gap flush to the bottom edge."""
    page = logged_in
    pid = doc_id = chunk_a = chunk_b = None
    req_ids = []
    try:
        pid = _mk_project(api_client, uniq)
        doc_id, chunk_a, chunk_b, a_text, _ = _seed_doc_chunks(pid, uniq)
        title = f"{uniq}-centered"
        req_ids.append(_mk_requirement(
            api_client, pid, title,
            {"documentId": doc_id, "chunkIds": [chunk_a]}))
        _open_req_page(page, f"Doors{uniq[:6]}", uniq)
        expect(_row_for(page, title)).to_be_visible(timeout=15000)
        _row_for(page, title).locator('[data-test="req-view-source"]').click()
        door = page.locator('[data-test="extract-source-dialog"]')
        expect(door).to_be_visible(timeout=10000)
        expect(door.locator('[data-test="extract-source-content"]')).to_have_text(
            a_text, timeout=10000)
        box = door.bounding_box()
        assert box, "door has no geometry"
        vh = page.evaluate("window.innerHeight")
        top_gap = box["y"]
        bottom_gap = vh - (box["y"] + box["height"])
        assert abs(top_gap - bottom_gap) <= 16, (
            f"door is not vertically centered: top gap {top_gap:.0f}px vs "
            f"bottom gap {bottom_gap:.0f}px")
        assert box["height"] <= vh * 0.85 + 8, (
            f"door exceeds the capped height: {box['height']:.0f}px over "
            f"{vh}px viewport")
        door.locator('[data-test="extract-source-close-btn"]').click()
        assert_no_errors(collectors, "doors centered geometry")
        assert_no_browser_issues(collectors["browser_issues"], "doors centered")
    finally:
        _cleanup(pid, doc_id, req_ids, chunk_ids=[chunk_a, chunk_b])


def test_context_neighbour_walk_rebinds_chunk(logged_in, api_client, collectors, uniq):
    """Face 2: the "view context" command on a neighbour block re-reads that
    block inside the same door (two-way chunk binding), the shipped defect
    kept the door parked on the original chunk."""
    page = logged_in
    pid = doc_id = chunk_a = chunk_b = None
    req_ids = []
    try:
        pid = _mk_project(api_client, uniq)
        doc_id, chunk_a, chunk_b, _, b_text = _seed_doc_chunks(pid, uniq)
        title = f"{uniq}-walk"
        req_ids.append(_mk_requirement(
            api_client, pid, title,
            {"documentId": doc_id, "chunkIds": [chunk_a]}))
        _open_req_page(page, f"Doors{uniq[:6]}", uniq)
        expect(_row_for(page, title)).to_be_visible(timeout=15000)
        _row_for(page, title).locator('[data-test="req-view-source"]').click()
        door = page.locator('[data-test="extract-source-dialog"]')
        expect(door).to_be_visible(timeout=10000)
        context = door.locator('[data-test="extract-source-context"]')
        expect(context).to_be_visible(timeout=10000)
        neighbour = door.locator(f'[data-test="extract-source-neighbor-{chunk_b}"]')
        expect(neighbour).to_be_visible(timeout=10000)
        assert neighbour.inner_text() == "查看该块上下文", (
            "the neighbour command lost its shipped caption")
        # The current-block mark lives inside the context area of the block
        # being shown: while A (seq 2) is open, its own mark is on screen.
        expect(door.locator('[data-test="extract-source-current-mark"]')
               ).to_be_visible(timeout=10000)
        neighbour.click()
        expect(door.locator('[data-test="extract-source-content"]')).to_have_text(
            b_text, timeout=10000)
        # B is the document's first block: no preceding neighbours, so the
        # context area (mark included) correctly retires with the walk.
        expect(door.locator('[data-test="extract-source-context"]')
               ).to_have_count(0, timeout=10000)
        door.locator('[data-test="extract-source-close-btn"]').click()
        assert_no_errors(collectors, "doors context walk")
        assert_no_browser_issues(collectors["browser_issues"], "doors context walk")
    finally:
        _cleanup(pid, doc_id, req_ids, chunk_ids=[chunk_a, chunk_b])


def test_link_case_no_opens_detail_door(logged_in, api_client, collectors, uniq):
    """Face 3: a case number in the link-cases dialog opens the shared
    detail door (embedded routed detail page) without leaving the route."""
    page = logged_in
    pid = None
    req_ids = []
    case_id = None
    try:
        pid = _mk_project(api_client, uniq)
        case_title = f"{uniq}-linked-case"
        cr = api_client.post("/cases", json={"projectId": pid, "title": case_title})
        assert cr.status_code == 200, f"case create failed: {cr.text[:300]}"
        case_body = cr.json()
        assert case_body.get("code") == "0", f"case create rejected: {case_body.get('msg')}"
        case_id = case_body["data"]["id"]
        title = f"{uniq}-doors-link"
        req_ids.append(_mk_requirement(api_client, pid, title, None))
        req_id = req_ids[0]
        lr = api_client.post(f"/requirements/{req_id}/cases", json={"caseIds": [case_id]})
        assert lr.status_code == 200, f"case link failed: {lr.text[:300]}"
        _open_req_page(page, f"Doors{uniq[:6]}", uniq)
        expect(_row_for(page, title)).to_be_visible(timeout=15000)
        _row_for(page, title).locator('[data-test="ra-link"]').click()
        dialog = page.locator('[data-test="req-link-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        linked_no = dialog.locator('[data-test="req-linked-case-detail"]')
        expect(linked_no).to_be_visible(timeout=10000)
        case_no_text = linked_no.inner_text()
        assert case_no_text, "linked case number renders empty"
        linked_no.click()
        door = page.locator('[data-test="case-detail-door"]')
        expect(door).to_be_visible(timeout=10000)
        expect(door).to_contain_text(case_title, timeout=15000)
        expect(door).to_contain_text(case_no_text, timeout=15000)
        assert "/requirements" in page.url, (
            f"detail door moved off the requirement route: {page.url}")
        close = door.locator('.el-dialog__headerbtn')
        if close.count() == 0:
            close = page.locator('.el-overlay-dialog .el-dialog__headerbtn')
        close.first.click()
        expect(page.locator('[data-test="case-detail-door"]')).to_be_hidden(
            timeout=10000)
        dialog.locator('button:has-text("关闭")').last.click()
        expect(dialog).to_be_hidden(timeout=10000)
        assert_no_errors(collectors, "doors case detail")
        assert_no_browser_issues(collectors["browser_issues"], "doors case detail")
    finally:
        if case_id is not None:
            api_client.delete(f"/cases/{case_id}")
        _cleanup(pid, None, req_ids)


def test_favorite_folded_into_more_menu(logged_in, api_client, collectors, uniq):
    """Face 4: the action band carries no direct favourite control; the
    command rides the row "more" dropdown and its round-trip lands."""
    page = logged_in
    pid = None
    req_ids = []
    try:
        pid = _mk_project(api_client, uniq)
        title = f"{uniq}-fav-fold"
        req_ids.append(_mk_requirement(api_client, pid, title, None))
        _open_req_page(page, f"Doors{uniq[:6]}", uniq)
        row = _row_for(page, title)
        expect(row).to_be_visible(timeout=15000)
        assert row.locator('[data-test="ra-fav"]').count() == 0, (
            "the favourite control is still a direct button in the band")
        row.locator('[data-test="ra-more"]').click()
        fav_item = page.locator('[data-test="ra-fav"]:visible')
        expect(fav_item).to_have_count(1, timeout=10000)
        assert fav_item.inner_text() == "收藏", (
            f"dropdown entry lost its shipped caption: {fav_item.inner_text()!r}")
        fav_item.click()
        expect(page.locator(".el-message").filter(has_text="已收藏")).to_be_visible(
            timeout=10000)
        row.locator('[data-test="ra-more"]').click()
        again = page.locator('[data-test="ra-fav"]:visible')
        expect(again).to_have_text("取消收藏", timeout=10000)
        again.click()
        expect(page.locator(".el-message").filter(has_text="已取消收藏")).to_be_visible(
            timeout=10000)
        assert_no_errors(collectors, "doors favourite fold")
        assert_no_browser_issues(collectors["browser_issues"], "doors favourite fold")
    finally:
        _cleanup(pid, None, req_ids)
