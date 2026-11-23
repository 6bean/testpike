"""Shared four-state fixture for the project-memory ledger.

The four-state confirmation-gate scenarios (E2E spec, vision capture, UX gate)
all need the same worst-scale ledger: a throwaway project holding 30 active
fillers + 1 disabled + 2 pending + 1 rejected row (34 total, server-paged)
plus one open confirmation draft anchored to the first pending row. Pending
and rejected states are minted by direct SQL because the distillation
channel — not the ledger API — owns those states.

Single-source rule (R40): keep the seeding/cleanup shape aligned with the
E2E spec `test_project_memory_tab.py::test_project_memory_four_state_confirmation_gate`
— changing one consumer means changing this file, never copy-pasting it.
"""
import uuid

from conftest import sql_query

STATUS_PENDING = 2
STATUS_REJECTED = 3


def api_ok(r):
    """Assert the envelope and return its data payload (real admin->core chain)."""
    assert r.status_code == 200 and str(r.json().get("code")) == "0", f"API failure: {r.text[:300]}"
    return r.json()["data"]


def _sql_first_int(q: str) -> int:
    # psql -tAc appends the command tag ("INSERT 0 1") after the RETURNING
    # row — the id is always the first line.
    return int(sql_query(q).splitlines()[0])


def seed(client, uniq: str | None = None) -> dict:
    """Create the throwaway project and the 34-row four-state ledger."""
    uniq = uniq or uuid.uuid4().hex[:8]
    oid = api_ok(client.post(
        "/projects", json={"name": f"PMem4{uniq}", "code": f"P4{uniq[:6]}"}))
    tok_card = f"MEM4CARD{uniq}"
    tok_nocard = f"MEM4NOCARD{uniq}"
    tok_rejected = f"MEM4REJ{uniq}"
    tok_disabled = f"MEM4DIS{uniq}"
    content_card = f"{tok_card} 蒸馏约定：接口断言须覆盖错误码分支"
    content_nocard = f"{tok_nocard} 蒸馏约定：执行报告归档保留最近九十天"
    content_rejected = f"{tok_rejected} 已驳回样例条目"
    content_disabled = f"{tok_disabled} 停用样例条目"

    def _sql_memory(content, inject_status):
        return _sql_first_int(
            "INSERT INTO tp_ai_ctx_memory (project_id, content, source_ref, inject_status)"
            f" VALUES ({oid}, '{content}', '[]'::jsonb, {inject_status}) RETURNING id")

    seeded = []
    for i in range(30):
        seeded.append(api_ok(client.post(
            "/project-memory",
            json={"projectId": oid,
                  "content": f"四态最坏规模填充第{i:02d}条 {uniq}"}))["id"])
    dis_id = api_ok(client.post(
        "/project-memory", json={"projectId": oid, "content": content_disabled}))["id"]
    api_ok(client.put(f"/project-memory/{dis_id}/status?projectId={oid}&status=0"))
    seeded.append(dis_id)
    pending_card = _sql_memory(content_card, STATUS_PENDING)
    pending_nocard = _sql_memory(content_nocard, STATUS_PENDING)
    seeded.append(pending_card)
    seeded.append(pending_nocard)
    seeded.append(_sql_memory(content_rejected, STATUS_REJECTED))
    # open confirmation card anchored to the first pending row
    # (payload contract = {content}, same shape the distill channel writes)
    draft_ids = [_sql_first_int(
        "INSERT INTO tp_ai_chat_draft (object_type, target_id, project_id,"
        " payload_json, status, summary)"
        " SELECT 'ctx_memory', m.id, m.project_id,"
        "        json_build_object('content', m.content)::text, 1,"
        "        'distilled confirmation card fixture'"
        " FROM tp_ai_ctx_memory m WHERE m.content = %s RETURNING id"
        % f"'{content_card}'")]
    return {
        "uniq": uniq, "project": f"PMem4{uniq}", "oid": oid,
        "seeded": seeded, "draft_ids": draft_ids,
        "tokens": {"card": tok_card, "nocard": tok_nocard,
                   "rejected": tok_rejected, "disabled": tok_disabled},
        "pending_card_id": pending_card, "pending_nocard_id": pending_nocard,
    }


def cleanup(client, fx: dict) -> None:
    """Remove draft rows and every seeded ledger row; assert zero residue.

    Fixture rows are cleaned by id — factory tables stay untouched (R37).
    """
    for did in fx["draft_ids"]:
        sql_query(f"DELETE FROM tp_ai_chat_draft WHERE id={did}")
    for rid in fx["seeded"]:
        client.delete(f"/project-memory/{rid}?projectId={fx['oid']}")
    active = api_ok(client.get(f"/project-memory?projectId={fx['oid']}&page=1&size=20"))
    assert active["total"] == 0, f"fixture residue left in the ledger: {active}"
