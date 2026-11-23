
import json
import re

import httpx
import pytest
from playwright.sync_api import expect

from chat_spectrum_support import (
    BASE_URL,
    assert_no_errors,
    core_base_url,
    inject_token,
    park_suggest_done,
    purge_project,
    seed_project,
    send_unambiguous,
    sql_query,
    wait_streaming_message,
)
from conftest import s3_bucket, s3_client
from issues_monitor import assert_no_browser_issues
from test_extraction_preview_review_gap import _wait_parsed
from test_f2_extraction import (
    doc_page,
    make_project,
    open_extract_dialog,
    sql,
    upload_doc,
)
from test_t310_queue_triptych_rerun import (
    PNG_1PX,
    _mk_app_case,
    _mk_task,
    _open_task_detail,
)


def _seed_kb_doc(pid, uniq):
    """Seed one knowledge ledger doc + 2 chunks (zero-vector posture of the
    v02 precedent); returns (doc_id, chunk_uuid, title)."""
    title = f"CITEANCHOR_{uniq}"
    doc_id = int(sql_query(
        "INSERT INTO tp_knowledge_doc (project_id,pack_id,source_type,source_ref,title,content,status)"
        f" VALUES ({pid},0,'TERM',NULL,'{title}','锚定引用全文：{uniq}'"
        ", 'INACTIVE') RETURNING id").strip().splitlines()[0])
    first_chunk = None
    for idx in (0, 1):
        cid = sql_query(
            "INSERT INTO knowledge.tp_knowledge_chunk (id,content,metadata,embedding)"
            f" VALUES (gen_random_uuid(),'锚定分块{idx}-{uniq}',"
            f"'{{\"doc_id\":\"{doc_id}\",\"source_type\":\"TERM\",\"project_id\":\"{pid}\","
            f"\"title\":\"{title}\",\"chunk_index\":\"{idx}\",\"lang\":\"java\","
            f"\"status\":\"ACTIVE\",\"pack_id\":\"0\",\"excellent\":false}}'::jsonb,"
            "('[0' || repeat(',0',1535) || ']')::vector) RETURNING id"
        ).strip().splitlines()[0]
        if idx == 0:
            first_chunk = cid
    return doc_id, first_chunk, title


def _park_cite(sid, msg_id, doc_id, chunk_id, title):
    """Journal one cite frame through the ledger door; meta shape is the
    parseCitation contract (index/kind/refId/title/excerpt/score/chunkId)."""
    meta = json.dumps({"index": 1, "kind": "knowledge", "refId": doc_id,
                       "title": title, "excerpt": f"锚定分块0-{title}",
                       "score": 0.87, "chunkId": chunk_id}, ensure_ascii=False)
    landed = httpx.post(f"{core_base_url()}/internal/ai-chat/sessions/{sid}/frames",
                        json={"messageId": msg_id, "frameType": "cite",
                              "payload": f"引用了《{title}》第 1 段。", "metaJson": meta},
                        timeout=30).json()
    assert landed.get("code") == "0", landed


@pytest.mark.xdist_group("ai-chat-exec-switch")
@pytest.mark.timeout(420)
def test_citation_anchor_one_hop_reveal(page, collectors, api_client, uniq):
    """Dock chip -> /knowledge deep link -> the cited chunk flashes."""
    pid = seed_project(api_client, uniq, "CITEANCHOR")
    doc_id = None
    try:
        doc_id, chunk_id, title = _seed_kb_doc(pid, uniq)
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        send_unambiguous(page, f"锚定探针（编号{uniq}）：请用一两句话说明知识库引用如何跳转，"
                              "不要向我提问，也不要调用任何工具")
        sid, msg_id = wait_streaming_message(90, pid)
        _park_cite(sid, msg_id, doc_id, chunk_id, title)
        park_suggest_done(sid, msg_id, [])

        chip = page.locator('[data-test="chat-cite-1"]')
        expect(chip).to_be_visible(timeout=15000)
        assert title in chip.inner_text(), f"chip must carry the cited title: {chip.inner_text()}"
        chip.click()

        expect(page).to_have_url(
            re.compile(r"#/knowledge\?docId=" + str(doc_id) + r"&chunkId=" + str(chunk_id)),
            timeout=15000)
        revealed = page.locator(f'[data-test="kb-chunk-reveal-{chunk_id}"]')
        expect(revealed).to_be_visible(timeout=15000)
        expect(revealed).to_have_class(re.compile("kb-chunk-flash"), timeout=8000)
        assert_no_errors(collectors, "citation anchor reveal")
        assert_no_browser_issues(collectors["browser_issues"], "citation anchor reveal")
    finally:
        if doc_id is not None:
            sql_query(f"DELETE FROM knowledge.tp_knowledge_chunk WHERE metadata->>'doc_id'='{doc_id}'")
            sql_query(f"DELETE FROM tp_knowledge_doc WHERE id={doc_id}")
        purge_project(api_client, pid)


@pytest.mark.timeout(300)
def test_ledger_evidence_anchor_verbatim_source(page, collectors, api_client, uniq):
    """Gap banner chip -> source dialog carries the chunk verbatim body."""
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "EVANCH")
        content = "# 锚定证据需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 锚定证据节{k} {uniq}\n\n锚定证据节{k} 关键词{uniq}。\n\n" for k in range(1, 4))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "anchor", content=content)
        _wait_parsed(doc_id)
        # The real parsing pipeline already created tp_document_chunk rows for
        # this fixture's own document; replace them with deterministic bodies
        # so the gap-banner chips carry verbatim text under exact control.
        sql(f"DELETE FROM tp_document_chunk WHERE document_id={doc_id}")

        chunk_ids = []
        for seq in (1, 2):
            chunk_ids.append(int(sql(
                "INSERT INTO tp_document_chunk (document_id, chapter, seq, content, token_est)"
                f" VALUES ({doc_id}, '锚定章{seq}-{uniq}', {seq},"
                f" '锚点正文{seq}：{uniq} 这段必须整段原文出现在证据弹层。', 40)"
                " RETURNING id").strip().splitlines()[0]))
        token = f"EVAG{uniq[:40]}"
        sql("UPDATE tp_document SET status=4, extract_token="
            f"'{token}', extraction_json='[]'::jsonb WHERE id={doc_id}")
        steps = [{"title": f"块#{cid}", "state": "pending"} for cid in chunk_ids]
        sql("INSERT INTO tp_doc_extract_ledger (document_id, extract_token, steps_json)"
            f" VALUES ({doc_id}, '{token}', '{json.dumps(steps, ensure_ascii=False)}')")

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)
        expect(dialog.locator('[data-test="extract-gap-banner"]')).to_be_visible(timeout=10000)
        chips = dialog.locator('[data-test^="extract-gap-anchor-"]')
        expect(chips).to_have_count(2)

        chips.first.click()
        source = page.locator('[data-test="extract-source-dialog"]')
        expect(source).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="extract-source-chapter"]')).to_contain_text(
            f"锚定章1-{uniq}", timeout=10000)
        expect(page.locator('[data-test="extract-source-content"]')).to_contain_text(
            f"锚点正文1：{uniq}", timeout=10000)
        assert_no_errors(collectors, "ledger evidence anchor")
        assert_no_browser_issues(collectors["browser_issues"], "ledger evidence anchor")
    finally:
        if doc_id is not None:
            sql(f"DELETE FROM tp_doc_extract_ledger WHERE document_id={doc_id}")
            sql(f"DELETE FROM tp_document_chunk WHERE document_id={doc_id}")
            sql(f"DELETE FROM tp_document WHERE id={doc_id}")
        if pid is not None:
            purge_project(api_client, pid)


@pytest.mark.timeout(300)
def test_execution_anchor_scene_reveal_and_honest_missing(page, collectors, api_client, uniq):
    """Scene link reveals the failed step block; a scene-less task answers a
    readable info message (never a silent no-op)."""
    pid = api_client.post("/projects", json={
        "name": f"锚定现场_{uniq}", "code": f"ANSC{uniq.upper()}"}).json()["data"]
    keys = []
    case_id = None
    try:
        case_id = _mk_app_case(api_client, pid, f"锚定现场用例_{uniq}", uniq)
        s3 = s3_client()
        bucket = s3_bucket()
        key_shot = f"evid-anchor/{uniq}/shot.png"
        key_tree = f"evid-anchor/{uniq}/tree.json"
        key_log = f"evid-anchor/{uniq}/logtail.txt"
        keys = [key_shot, key_tree, key_log]
        s3.put_object(Bucket=bucket, Key=key_shot, Body=PNG_1PX, ContentType="image/png")
        tree_text = f'{{"device": "e2e-{uniq}", "hierarchy": {{"node": "com.demo:id/btn"}}}}'
        s3.put_object(Bucket=bucket, Key=key_tree, Body=tree_text.encode(),
                      ContentType="application/json")
        log_text = f"BEGIN logcat tail\nFATAL ANCHOR-{uniq}: tap target not found\nEND"
        s3.put_object(Bucket=bucket, Key=key_log, Body=log_text.encode(), ContentType="text/plain")

        base_steps = [{"stepNo": 1, "status": "ok", "action": "app.launch"},
                      {"stepNo": 2, "status": "fail", "action": "app.tap"}]
        tl_ev = base_steps[:1] + [dict(base_steps[1], evidence={
            "screenshot": key_shot, "view_tree": key_tree, "log_tail": key_log})]
        tid_ev = _mk_task(pid, case_id, 4, "appauto-v1", timeline=tl_ev)
        tid_no = _mk_task(pid, case_id, 4, "appauto-v1", timeline=list(base_steps))

        dlg = _open_task_detail(page, f"锚定现场_{uniq}", tid_ev)
        expect(dlg.locator('[data-test="exec-triptych"]')).to_be_visible(timeout=15000)
        dlg.locator('[data-test="exec-error-scene-link"]').click()
        expect(dlg.locator('[data-test="triptych-step-2"]')).to_have_class(
            re.compile("triptych-step-flash"), timeout=8000)

        page.keyboard.press("Escape")
        dlg2 = _open_task_detail(page, f"锚定现场_{uniq}", tid_no)
        dlg2.locator('[data-test="exec-error-scene-link"]').click()
        expect(page.locator(".el-message--info")).to_be_visible(timeout=8000)

        assert_no_errors(collectors, "execution anchor scene")
        assert_no_browser_issues(collectors["browser_issues"], "execution anchor scene")
    finally:
        # Project purge cascades cases/steps/scripts/tasks (t310 precedent); hand-rolled
        # DELETEs here raced foreign keys and were redundant.
        for k in keys:
            s3_client().delete_object(Bucket=s3_bucket(), Key=k)
        purge_project(api_client, pid)


@pytest.mark.timeout(300)
def test_extract_preview_chapter_label_open_and_pending_anchor(page, collectors, api_client, uniq):
    pass
    pid = None
    doc_id = None
    try:
        pid = make_project(api_client, uniq, "CHAPANCH")
        content = "# 章节锚定需求文档 " + uniq + "\n\n" + "\n".join(
            f"## 章节锚定节{k} {uniq}\n\n章节锚定节{k} 关键词{uniq}。\n\n" for k in range(1, 4))
        doc_id, name, _sha = upload_doc(api_client, pid, uniq, "chap", content=content)
        _wait_parsed(doc_id)
        sql(f"DELETE FROM tp_document_chunk WHERE document_id={doc_id}")
        anchored_chunk = int(sql(
            "INSERT INTO tp_document_chunk (document_id, chapter, seq, content, token_est)"
            f" VALUES ({doc_id}, '章节锚定节1-{uniq}', 1,"
            f" '章节锚定节1 关键词{uniq}。这段必须是原文弹层里的整段正文。', 40)"
            " RETURNING id").strip().splitlines()[0])
        # Two preview rows through the product's own carrier (extraction_json):
        # #1 server-anchored (clickable chapter), #2 anchor-less (pending flag).
        items = [
            {"id": 1, "title": f"锚定在位需求{uniq}", "acceptanceCriteria": "在位验收",
             "chapter": f"章节锚定节1-{uniq}", "confidence": 0.9, "selected": False,
             "requirementId": None, "evidence": f"章节锚定节1 关键词{uniq}。",
             "chunkId": anchored_chunk},
            {"id": 2, "title": f"待补锚需求{uniq}", "acceptanceCriteria": "缺锚验收",
             "chapter": f"章节锚定节2-{uniq}", "confidence": 0.8, "selected": False,
             "requirementId": None, "evidence": None, "chunkId": None},
        ]
        payload = json.dumps(items, ensure_ascii=False)
        sql(f"UPDATE tp_document SET status=4, extraction_json='{payload}'::jsonb WHERE id={doc_id}")

        doc_page(page, pid)
        dialog = open_extract_dialog(page, name)
        # Hard counts (iron law 5d) scoped to real body rows: Element Plus keeps a
        # hidden-columns measurement copy of each cell template (there the slot
        # scope is an empty row, and `undefined == null` makes the pending branch
        # render once more) — counting the visible tbody only is the honest face.
        body = dialog.locator('[data-test="extract-table"] tbody')
        expect(body.locator('[data-test="extract-chapter-open"]')).to_have_count(1, timeout=10000)
        expect(body.locator('[data-test="extract-anchor-pending"]')).to_have_count(1)
        expect(body.locator('[data-test="extract-source-btn-disabled"]')).to_have_count(1)

        body.locator('[data-test="extract-chapter-open"]').click()
        source = page.locator('[data-test="extract-source-dialog"]')
        expect(source).to_be_visible(timeout=10000)
        expect(page.locator('[data-test="extract-source-chapter"]')).to_contain_text(
            f"章节锚定节1-{uniq}", timeout=10000)
        expect(page.locator('[data-test="extract-source-content"]')).to_contain_text(
            f"章节锚定节1 关键词{uniq}", timeout=10000)
        page.locator('[data-test="extract-source-close-btn"]').click()
        expect(source).to_be_hidden(timeout=10000)

        # The pending-anchor guidance names the switch key literally (R32).
        body.locator('[data-test="extract-anchor-pending"]').hover()
        expect(page.locator(".el-popper:visible").filter(
            has_text="ai.doc.anchor-required.enabled")).to_be_visible(timeout=8000)

        assert_no_errors(collectors, "chapter label open + pending anchor")
        assert_no_browser_issues(collectors["browser_issues"], "chapter label open + pending anchor")
    finally:
        if doc_id is not None:
            sql(f"DELETE FROM tp_document_chunk WHERE document_id={doc_id}")
            sql(f"DELETE FROM tp_document WHERE id={doc_id}")
        if pid is not None:
            purge_project(api_client, pid)
