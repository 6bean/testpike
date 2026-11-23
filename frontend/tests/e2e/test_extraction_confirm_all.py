"""Full-store confirm door spectrum: a paged preview lands whole in one click.

The per-item ticking path could only ever store what the reviewer selected on
the current page (the original report: 35 preview rows shown as 20+15, ticking
walked page by page). The full-store door enumerates the whole preview
server-side and lands every item in one transaction, then rides the existing
deep link into the batch generation page. What this spectrum pins, live and
without mocks (admin -> core -> DB):

  1. a 35-row preview paginated 20+15 stores 35 from ONE click -- the created
     count comes from the real response body, never from the visible page;
  2. the rows are really landed (SQL count on tp_requirement by the fixture's
     unique title suffix = 35) and the hand-off link carries all 35 ids into
     the batch page (generation coverage of every stored point);
  3. a repeated click is idempotent (created=0, the count stays 35, no second
     navigation) -- the door must never double-land behind a reload;
  4. the legacy per-item ticking path stays reachable (the button pair coexist
     on state 4; the ticking spectrum itself keeps its own file).

Fixtures are project-scoped scratch data purged in finally with a
zero-residue check (R37: shipped tables are never truncated here).
"""

import json
import re

import httpx
from conftest import ADMIN_PASSWORD, API_BASE, api_login, assert_no_errors
from issues_monitor import assert_no_browser_issues
from playwright.sync_api import expect
from test_extraction_preview_review_gap import (
    _ensure_chunks,
    _live_threshold,
    _wait_parsed,
)
from test_f2_extraction import (
    doc_page,
    make_project,
    open_extract_dialog,
    sql,
    upload_doc,
)

TOTAL_ITEMS = 35   # 20 + 15 across two preview pages (the reported shape)


def _build_full_items(thr, chunk_ids, uniq, count=TOTAL_ITEMS):
    """Confidence above the live threshold so every row is a store candidate;
    anchors alternate between the two chunks like the real extractor emits."""
    return [{
        "id": i,
        "title": f"全量入库条目{i}-{uniq}",
        "acceptanceCriteria": f"验收标准{i}",
        "chapter": f"第{i % 5 + 1}章",
        "confidence": round(min(thr + 0.02 * (i % 12 + 1), 0.99), 3),
        "selected": False,
        "requirementId": None,
        "evidence": f"原文摘录{i}",
        "chunkId": chunk_ids[i % len(chunk_ids)],
    } for i in range(1, count + 1)]


def _confirm_all(page, dialog):
    """Click the full-store button and return the parsed response body of the
    real POST (no conditional wrapping: a missing door must turn the run red)."""
    with page.expect_response(
            lambda r: "/confirm-all-extraction" in r.url and r.request.method == "POST") as got:
        dialog.locator('[data-test="extract-confirm-all-btn"]').click()
    resp = got.value
    assert resp.status == 200, f"confirm-all door answered {resp.status}"
    return resp.json()


def test_extract_confirm_all_lands_paged_preview_whole(page, collectors, api_client, uniq):
    thr = _live_threshold()
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "CA")
        content = "# 全量入库需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 全量验证节{k} {uniq}\n\n全量验证节{k} 关键词{uniq} 覆盖两页预览形态。\n\n"
            for k in range(1, 15))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "all", content=content)
        _wait_parsed(doc_id)
        chunks = _ensure_chunks(doc_id, uniq, 2)
        items = _build_full_items(thr, chunks, uniq)
        sql("UPDATE tp_document SET status=4, extract_token="
            f"'CA{uniq[:40]}', extraction_json='{json.dumps(items, ensure_ascii=False)}'::jsonb"
            f" WHERE id={doc_id}")

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)

        # --- 1. the worst-scale shape itself: 20 rows on page one, 15 on page two,
        #         each page a hard count (5d: absent rows must turn this red) ---
        table = dialog.locator('[data-test="extract-table"]')
        expect(table).to_be_visible(timeout=15000)
        expect(table.locator("tbody tr")).to_have_count(20, timeout=10000)
        dialog.locator(".el-pager li").nth(1).click()
        expect(table.locator("tbody tr")).to_have_count(15, timeout=10000)

        # --- 2. one click stores everything the pages hold, server-side ---
        body = _confirm_all(page, dialog)
        data = body.get("data") or {}
        assert data.get("createdCount") == TOTAL_ITEMS, \
            f"full store landed {data.get('createdCount')} of {TOTAL_ITEMS}: {body}"
        landed = sql("SELECT count(*) FROM tp_requirement WHERE project_id="
                     f"{pid} AND title LIKE '%-{uniq}' AND deleted=0")
        assert int(landed) == TOTAL_ITEMS, f"SQL shows {landed} landed rows, want {TOTAL_ITEMS}"

        # --- 3. generation coverage: the deep link carries all freshly stored ids ---
        # Web-first wait, generous window: the hand-off lands on the heavy
        # generation view whose dev chunk is lazily compiled on first hit while
        # other pytest workers hit the same dev server (cold-compile window =
        # tooling noise, see the frame-sampling warm-up precedent; the shipped
        # build is pre-bundled and never pays it).
        expect(page).to_have_url(re.compile(r"ai/generate"), timeout=30000)
        qs = page.url.split("requirementIds=", 1)
        assert len(qs) == 2, f"hand-off link lost the id allow-list: {page.url}"
        ids = [s for s in re.split(r"[,&]", qs[1]) if s.isdigit()]
        assert len(ids) == TOTAL_ITEMS, \
            f"batch page received {len(ids)} ids, want {TOTAL_ITEMS}: {page.url}"
        assert len(set(ids)) == TOTAL_ITEMS, "id allow-list carries duplicates"

        # --- 4. idempotent replay: second door lands nothing new and stays put ---
        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)
        expect(dialog.locator('[data-test="extract-confirm-all-btn"]')).to_be_visible(timeout=10000)
        body2 = _confirm_all(page, dialog)
        data2 = body2.get("data") or {}
        assert data2.get("createdCount") == 0, f"replay re-created rows: {body2}"
        assert int(landed) == int(sql("SELECT count(*) FROM tp_requirement WHERE project_id="
                                      f"{pid} AND title LIKE '%-{uniq}' AND deleted=0")), \
            "replay drifted the landed count"
        expect(page).not_to_have_url(re.compile(r"ai/generate"), timeout=3000)

        # --- 5. the legacy ticking door is still in place next to the new one ---
        expect(dialog.locator('[data-test="extract-confirm-btn"]')).to_be_visible(timeout=5000)

        assert_no_errors(collectors, "extract confirm-all full store")
        assert_no_browser_issues(collectors["browser_issues"], "extract confirm-all full store")
    finally:
        if doc_id is not None:
            token = api_login("admin", ADMIN_PASSWORD)
            httpx.delete(f"{API_BASE}/ai/documents/{doc_id}",
                         headers={"Authorization": f"Bearer {token}"}, timeout=30)
        if pid is not None:
            api_client.delete(f"/projects/{pid}/purge?force=true")
        if doc_id is not None:
            assert sql(f"SELECT count(*) FROM tp_document_chunk WHERE document_id={doc_id}") == "0"
            assert sql(f"SELECT count(*) FROM tp_document WHERE id={doc_id}") == "0"
