"""Stage-C acceptance journey for the requirement "view original" door .

Faces asserted end to end on THIS slot's stack, browser + real admin->core
chain (no mocked reads):

1. chunk-backed requirement -> the shared viewer shows the chunk verbatim,
 equal to the stored source text measured straight from the ledger. The
 requirement-row door and the extraction-preview door are one component
 over one GET /ai/documents/{id}/chunks endpoint: the vitest contracts
 (requirementSourceViewer / extractSourceJump, green since the previous
 segment) pin the preview-side wiring, and the shipped residual-banner
 journey already drives that preview entry live on the main slot.
2. evidence-only requirement (no chunk anchor) -> the door stays open and
 answers through the fallback face with the recorded excerpt.
3. stale chunk id (row gone after adoption) -> the read failure keeps the
 dialog open on the fallback face instead of vanishing (R63: no dead end).
4. requirement without any source record -> the door is disabled and the
 tooltip names where a source comes from (verbatim shipped sentence).
5. the ID list filter and the fingerprint filter each pin the row set to the
 seeded point (SE-group filter contract).

Fixture discipline: every row created here is deleted by its own anchor in
the finally block (R57); the project container follows the sibling-spec
precedent (projects are cheap namespaces, data rows are not left behind).
"""
import hashlib
import json

from conftest import assert_no_errors, nav_to, pick_project_in_header
from issues_monitor import assert_no_browser_issues
from test_f2_extraction import sql
from playwright.sync_api import expect

_VERBATIM = "原文对照旅程段落 verbatim source paragraph"


def _insert_id(statement):
    """Parse the generated key of one INSERT ... RETURNING statement.

    psql -tA prints the returning row followed by its command tag
    ("INSERT 0 1"), so only the first line is the new id; int() over the
    whole output raises ValueError (seen live in the fleet window log).
    """
    return int(sql(statement).splitlines()[0])


def _mk_project(api, uniq):
    r = api.post("/projects", json={"name": f"ViewOrig{uniq[:6]}",
                                    "code": f"VO{uniq[:6].upper()}"})
    assert r.status_code == 200, f"project create failed: {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"project create rejected: {body.get('msg')}"
    return body["data"]


def _seed_doc_and_chunk(pid, uniq):
    """Backfill the document + chunk rows (fixture plane, not the chain under
    test: the viewer's read still goes through the real admin API)."""
    digest = hashlib.sha256(f"vieworig-{uniq}".encode()).hexdigest()
    doc_id = _insert_id(
        "INSERT INTO tp_document (project_id, name, file_type, file_hash,"
        " status, chunk_count)"
        f" VALUES ({pid}, 'VO-{uniq[:8]}.md', 'md', '{digest}', 1, 1)"
        " RETURNING id")
    chunk_id = _insert_id(
        "INSERT INTO tp_document_chunk (document_id, chapter, seq, content,"
        " token_est)"
        f" VALUES ({doc_id}, '第9章 采集', 1,"
        f" '{_VERBATIM} {uniq[:8]}', 40)"
        " RETURNING id")
    return doc_id, chunk_id


def _mk_requirement(api, pid, title, anchor):
    """Create the point through the real API, then backfill the provenance.

    The create DTO carries no anchor field by design (extraction stamps it),
    so the ledger-side UPDATE writes exactly the JSONB shape the extraction
    chain stores. The read under test still travels admin -> core.
    Returns the row id: cleanup and the identity filters key on it.
    """
    r = api.post(f"/projects/{pid}/requirements", json={"source": 2, "title": title})
    assert r.status_code == 200, f"requirement create failed: {r.text[:300]}"
    body = r.json()
    assert body.get("code") == "0", f"requirement create rejected: {body.get('msg')}"
    req_id = body["data"]["id"]
    if anchor is not None:
        payload = json.dumps(
            {"documentId": anchor.get("documentId"),
             "chunkIds": list(anchor.get("chunkIds", [])),
             "evidence": anchor.get("evidence")},
            ensure_ascii=False).replace("'", "''")
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


def _cleanup(pid, doc_id, chunk_id, req_ids):
    ids = ",".join(str(i) for i in req_ids if i is not None)
    if ids:
        sql(f"DELETE FROM tp_requirement WHERE id IN ({ids})")
    if chunk_id is not None:
        sql(f"DELETE FROM tp_document_chunk WHERE id={chunk_id}")
    if doc_id is not None:
        sql(f"DELETE FROM tp_document WHERE id={doc_id}")


def test_view_original_chunk_and_fallback_faces(logged_in, api_client, collectors, uniq):
    """Door opens on the verbatim chunk; evidence-only and stale-chunk rows
    answer through the fallback face; the recordless row is disabled with the
    shipped guidance sentence."""
    page = logged_in
    pid = _mk_project(api_client, uniq)
    doc_id = chunk_id = None
    req_ids = []
    try:
        doc_id, chunk_id = _seed_doc_and_chunk(pid, uniq)
        verbatim = f"{_VERBATIM} {uniq[:8]}"
        title_ok = f"{uniq}-chunk"
        title_evid = f"{uniq}-evidence-only"
        title_stale = f"{uniq}-stale-chunk"
        title_plain = f"{uniq}-recordless"
        req_ids.append(_mk_requirement(
            api_client, pid, title_ok,
            {"documentId": doc_id, "chunkIds": [chunk_id],
             "evidence": "备用证据行 evidence-basis"}))
        req_ids.append(_mk_requirement(
            api_client, pid, title_evid,
            {"chunkIds": [], "evidence": f"仅证据摘录 evidence-only {uniq[:8]}"}))
        req_ids.append(_mk_requirement(
            api_client, pid, title_stale,
            {"documentId": doc_id, "chunkIds": [chunk_id + 900_000_007],
             "evidence": f"失效块兜底句 stale-chunk-fallback {uniq[:8]}"}))
        req_ids.append(_mk_requirement(api_client, pid, title_plain, None))

        _open_req_page(page, f"ViewOrig{uniq[:6]}", uniq)
        expect(_row_for(page, title_ok)).to_be_visible(timeout=15000)

        # ── face 1: chunk verbatim equals the stored source text ──
        _row_for(page, title_ok).locator('[data-test="req-view-source"]').click()
        dialog = page.locator('[data-test="extract-source-dialog"]')
        expect(dialog).to_be_visible(timeout=10000)
        shown = dialog.locator('[data-test="extract-source-content"]').inner_text()
        assert shown.strip() == verbatim, (
            f"viewer text diverges from the stored chunk: {shown!r}")
        dialog.locator('[data-test="extract-source-close-btn"]').click()

        # ── face 2: evidence-only point answers through the fallback face ──
        evid_text = f"仅证据摘录 evidence-only {uniq[:8]}"
        _row_for(page, title_evid).locator('[data-test="req-view-source"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        note = dialog.locator('[data-test="extract-source-fallback-note"]')
        expect(note).to_be_visible()
        fallback = dialog.locator('[data-test="extract-source-fallback-evidence"]')
        expect(fallback).to_contain_text(evid_text)
        assert dialog.locator(
            '[data-test="extract-source-content"]').count() == 0, (
            "fallback face must not fake a chunk block when no chunk was read")
        dialog.locator('[data-test="extract-source-close-btn"]').click()

        # ── face 3: stale chunk id -> read failure stays open on the fallback ──
        stale_text = f"失效块兜底句 stale-chunk-fallback {uniq[:8]}"
        _row_for(page, title_stale).locator('[data-test="req-view-source"]').click()
        expect(dialog).to_be_visible(timeout=10000)
        expect(dialog.locator(
            '[data-test="extract-source-fallback-evidence"]')).to_contain_text(
            stale_text, timeout=10000)
        dialog.locator('[data-test="extract-source-close-btn"]').click()

        # ── face 4: recordless point -> disabled door with guidance sentence ──
        plain_row = _row_for(page, title_plain)
        expect(plain_row.locator('[data-test="req-view-source"]')).to_be_disabled()
        plain_row.locator('.req-source-host').hover()
        tip = page.locator('.el-popper').filter(
            has_text="「需求提取」页").last
        expect(tip).to_be_visible(timeout=5000)

        assert_no_errors(collectors, page)
        assert_no_browser_issues(collectors["browser_issues"], "view-original journey")
    finally:
        _cleanup(pid, doc_id, chunk_id, req_ids)


def test_view_original_id_and_fingerprint_filters(logged_in, api_client, collectors, uniq):
    """The ID column filter and the fingerprint column filter each pin the
    list to the seeded point (SE-group: every listed identity is filterable)."""
    page = logged_in
    pid = _mk_project(api_client, uniq)
    doc_id = chunk_id = None
    req_ids = []
    try:
        doc_id, chunk_id = _seed_doc_and_chunk(pid, uniq)
        title_target = f"{uniq}-filtered"
        title_noise = f"{uniq}-noise"
        target_id = _mk_requirement(
            api_client, pid, title_target,
            {"documentId": doc_id, "chunkIds": [chunk_id], "evidence": "e"})
        req_ids.append(target_id)
        noise_id = _mk_requirement(api_client, pid, title_noise, None)
        req_ids.append(noise_id)
        fingerprint = sql(
            f"SELECT fingerprint FROM tp_requirement WHERE id={target_id}")
        assert fingerprint, "generated fingerprint missing on the seeded row"

        _open_req_page(page, f"ViewOrig{uniq[:6]}", uniq)
        expect(_row_for(page, title_target)).to_be_visible(timeout=15000)

        # ID filter: only the target row survives (clear the keyword first).
        page.locator('[data-test="req-filter-keyword"]').fill("")
        page.locator('[data-test="req-filter-ids"]').fill(str(target_id))
        page.locator('[data-test="req-search"]').click()
        rows = page.locator('[data-test="req-table"] tbody tr.el-table__row')
        expect(rows.filter(has_text=title_target)).to_have_count(1)
        expect(rows.filter(has_text=title_noise)).to_have_count(0)

        # Fingerprint filter: the stable content hash pins the same row.
        page.locator('[data-test="req-filter-ids"]').fill("")
        page.locator('[data-test="req-filter-fingerprint"]').fill(fingerprint)
        page.locator('[data-test="req-search"]').click()
        expect(rows.filter(has_text=title_target)).to_have_count(1)
        expect(rows.filter(has_text=title_noise)).to_have_count(0)

        assert_no_errors(collectors, page)
        assert_no_browser_issues(collectors["browser_issues"], "view-original journey")
    finally:
        _cleanup(pid, doc_id, chunk_id, req_ids)
