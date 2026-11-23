"""/ close spectrum: provenance badges land on the review queue.

Two write channels, one review page, the same two server-computed fields
(sourceFacet / evidenceGrade, single source frontend/src/api/case.ts):

 Spectrum 1 (off-site attested): a real /mcp JSON-RPC session over the api
 service runs create_case -> script.writeback with attested evidence ->
 in-session mcp.review.confirm accept (no re-login door, ruling 2026-11-16).
 After the case enters the reviewer's segment, a fresh review-queue load
 renders the off-site facet badge and an evidence badge whose label is
 either off-site attested or platform re-proof (the platform may promote
 the grade by its own re-proof; both grades prove the badge rides the
 landed evidence, not a default).

 Spectrum 2 (in-site chat): the reviewer sits on the open review page; one
 real streamed turn parks a CASE_WRITE create through the frame door, the
 single visible draft card is accepted, and the queue converges silently:
 the unassigned-pending hint appears with zero reload and zero toast
 (useObjectSync single source, BG iron rule 2). After the row joins the
 reviewer's segment the facet badge reads chat — same fields, other source.

Silence probes and the draft-parking helpers are imported from the proven
BG single-source spectra (R40: no second implementation in this file).
"""
import json
import os
import time

import httpx
import pytest
from conftest import (API_BASE, BASE_URL, TESTER_PASSWORD,
                      _set_sys_config_value, add_member, assert_no_errors,
                      core_base_url, inject_token, inject_user_token,
                      mk_tester, pick_project_in_header, sql_query)
from conftest import purge_project as _purge_project
from conftest import seed_project as _seed_project
from playwright.sync_api import expect
from test_chat_group_door_one_click_batch_accept import _create_body
from test_chat_sync_live_update_spectrum import (_accept_single_door,
                                                 _arm_probes, _assert_quiet,
                                                 _mark, _park, _sweep,
                                                 _stream_turn)

pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

MCP_BASE = (os.environ.get("TP_E2E_MCP_BASE") or os.environ.get("TP_MCP_BASE")
            or ("http://localhost:%s" % (os.environ.get("API_PORT") or "8082")))
CORE_BASE = core_base_url()
MCP_ACCEPT = {"Accept": "application/json, text/event-stream"}
CALL_TIMEOUT_S = int(os.environ.get("TP_E2E_MCP_CALL_TIMEOUT_S", "120"))

_ZH_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "..", "..", "src", "utils", "locales", "zh-CN.ts")


def _zh_section_labels(section, keys):
    """Pull label values out of the dict.case.<section> block of the zh locale.

    The badge copy is single-sourced in the locale file; reading the values
    here (instead of hardcoding them) keeps a copy refresh from silently
    turning the badge assertions into a different word than the UI renders.
    """
    with open(_ZH_PATH, encoding="utf-8") as fh:
        lines = fh.readlines()
    start = next(i for i, ln in enumerate(lines) if ln.strip().startswith(section + ": {"))
    window = "".join(lines[start:start + 12])
    out = {}
    for key in keys:
        idx = window.index(key + ":")
        quote = window.index("'", idx)
        out[key] = window[quote + 1:window.index("'", quote + 1)]
    return out


def _mcp_rpc(method, params, pat, idem=None):
    headers = dict(MCP_ACCEPT)
    headers["Authorization"] = "Bearer " + pat
    if idem:
        headers["Idempotency-Key"] = idem
    resp = httpx.post(MCP_BASE + "/mcp",
                      json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
                      headers=headers, timeout=CALL_TIMEOUT_S)
    assert resp.status_code == 200, "MCP transport failed: %s %s" % (resp.status_code, resp.text[:200])
    body = resp.text
    if "data:" in body and not body.lstrip().startswith("{"):
        # streamable-http SSE framing: the JSON-RPC reply rides a data: line.
        for line in body.splitlines():
            if line.startswith("data:"):
                return json.loads(line[5:].strip())
        raise AssertionError("SSE reply carried no data: frame: " + body[:200])
    return resp.json()


def _mcp_tool(name, args, pat, idem=None):
    reply = _mcp_rpc("tools/call", {"name": name, "arguments": args}, pat, idem=idem)
    assert "error" not in reply, "%s failed: %s" % (name, reply.get("error"))
    content = (reply.get("result") or {}).get("content") or []
    if content and isinstance(content[0], dict) and "text" in content[0]:
        try:
            return json.loads(content[0]["text"])
        except (ValueError, TypeError):
            return {"text": content[0]["text"]}
    return reply.get("result") or {}


def _mint_pat(api_client, name, uniq):
    expires = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() + 86400))
    created = api_client.post("/api-tokens", json={
        "name": f"MCP落地谱令牌{name}{uniq}",
        "scopes": "mcp.case,mcp.script,mcp.review",
        "expiresAt": expires,
    })
    assert created.status_code == 200 and created.json().get("code") == "0", created.text[:200]
    data = created.json().get("data") or {}
    pat = data.get("plaintext") or data.get("token") or data.get("secret")
    assert pat, "agent token mint returned no plaintext field: %s" % str(data)[:160]
    return pat


def _mk_reviewer(api_client, pid, uniq, prefix="t"):
    """Fresh project-member tester as the assignee the queue can show.

    Two server rules shape this: the assign door only accepts a tester of
    the project (40301), and the review queue filters
    assigneeId = current user (ReviewWorkbenchView.queueQuery) — so the
    viewing identity must be the assignee, not admin.
    """
    tester_id, _nick = mk_tester(api_client, uniq, prefix)
    add_member(api_client, pid, tester_id)
    return tester_id, f"{prefix}tester_{uniq}"


def _assign(api_client, case_id, assignee_id):
    r = api_client.post(f"/cases/{case_id}/assign", json={"assigneeId": assignee_id})
    assert r.status_code == 200 and r.json().get("code") == "0", r.text[:200]


def _open_review(page, pname, as_user=None):
    if as_user is None:
        inject_token(page)
    else:
        # Real login owns the identity snapshot (hash-only navigation keeps
        # the previous user's /me cache — A2 lesson).
        inject_user_token(page, as_user, TESTER_PASSWORD)
    page.goto(f"{BASE_URL}/#/review", wait_until="domcontentloaded")
    pick_project_in_header(page, pname)
    expect(page.locator('[data-test="review-page"]')).to_be_visible(timeout=15000)


def _script_json(marker):
    return json.dumps({
        "version": "v2", "caseId": "MCPLAND-E2E", "title": marker, "endType": "web",
        "steps": [{"action": "input", "target": {"css": "#username", "chain": ["css"]},
                   "value": marker},
                  {"action": "expect", "target": {"text": "登录成功"}}],
        "env": {"ENV": "e2e-slot6"},
    }, ensure_ascii=False)


def test_mcp_off_agent_landing_badges_on_review_page(page, collectors, api_client, uniq):
    """Spectrum 1: the real off-site MCP chain lands a case the review queue
    renders with the off-site facet badge and an attested-grade badge."""
    pid = _seed_project(api_client, uniq, "MCPLAND1")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    title = f"站外代理落地靶用例{uniq}"
    try:
        pat = _mint_pat(api_client, "谱一", uniq)
        created = _mcp_tool("create_case", {
            "project_id": int(pid), "title": title,
            "steps": "打开登录页并输入有效账号", "expected": "首页工作台可见",
            "idempotency_key": "mcpland-create-%s" % uniq,
        }, pat)
        case_id = created.get("case_id") or created.get("id") or created.get("caseId")
        assert case_id, "create_case receipt carried no case id: %s" % str(created)[:200]
        case_id = int(case_id)

        wb = _mcp_tool("script.writeback", {
            "project_id": int(pid), "case_id": case_id,
            "script": _script_json("MCPLAND-%s" % uniq),
            "evidence": {"ran_at": "2026-11-18T09:00:00Z", "env_fingerprint": "e2e-slot6",
                         "passed": 5, "failed": 0, "log_sha256": "0" * 64},
            "idempotency_key": "mcpland-wb-%s" % uniq,
        }, pat)
        ref = wb.get("confirm_ref")
        assert ref, "writeback receipt is missing the one-shot confirm_ref: %s" % str(wb)[:200]

        settled = _mcp_tool("mcp.review.confirm",
                            {"confirm_ref": ref, "decision": "accept"}, pat,
                            idem="mcpland-cfm-%s" % uniq)
        assert settled.get("landed") is True, \
            "accept did not land the writeback: %s" % str(settled)[:200]

        tester_id, tester_user = _mk_reviewer(api_client, pid, uniq)
        _assign(api_client, case_id, tester_id)

        _open_review(page, pname, as_user=tester_user)
        row = page.locator("tbody tr").filter(has_text=title).first
        expect(row).to_be_visible(timeout=15000)
        facet = row.locator('[data-test="review-facet-badge"]')
        expect(facet).to_have_text(_zh_section_labels("facet", ["offAgent"])["offAgent"],
                                   timeout=10000)
        evidence = row.locator('[data-test="review-evidence-badge"]')
        expect(evidence).to_be_visible(timeout=10000)
        grades = _zh_section_labels("evidence", ["attested", "platformReproof"])
        assert evidence.inner_text() in (grades["attested"], grades["platformReproof"]), \
            "evidence badge %r is outside the attested grades %r" % (
                evidence.inner_text(), sorted(grades.values()))
        assert_no_errors(collectors, "mcp off-agent landing spectrum")
    finally:
        _purge_project(api_client, pid)


def test_chat_landed_case_converges_review_page_silently(page, collectors, api_client, uniq):
    """Spectrum 2: a chat-landed CASE_WRITE create converges the open review
    page with zero reload and zero toast; its badge then reads chat."""
    pid = _seed_project(api_client, uniq, "MCPLAND2")
    pname = sql_query(f"SELECT name FROM tp_project WHERE id={pid}")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    _set_sys_config_value("ai.chat.case-draft.enabled", "true")
    _arm_probes(page)
    title = f"站内对话落地靶用例{uniq}"
    try:
        _open_review(page, pname)
        hint = page.locator('[data-test="review-unassigned-hint"]')
        _mark(page)

        sid, msg_id = _stream_turn(page, pid, f"评审{uniq}")
        _park({"executionId": 0, "objectType": "CASE_WRITE", "caseId": 0,
               "baseVersion": 0, "toolName": "case.create", "model": "e2e-probe-model",
               "schemaVersion": "v2", "actionJson": _create_body(title)},
              CORE_BASE, sid, msg_id, f"新建用例草案：{title}")
        _accept_single_door(page)

        expect(hint).to_be_visible(timeout=20000)
        _assert_quiet(page, "review page chat-landing convergence")

        case_id = int(sql_query("SELECT id FROM tp_test_case WHERE title='%s'" % title))
        tester_id, tester_user = _mk_reviewer(api_client, pid, uniq)
        _assign(api_client, case_id, tester_id)
        # Viewing identity switches to the assignee before the row check:
        # the queue segment is assigneeId = current user (see _mk_reviewer).
        _open_review(page, pname, as_user=tester_user)
        row = page.locator("tbody tr").filter(has_text=title).first
        expect(row).to_be_visible(timeout=15000)
        expect(row.locator('[data-test="review-facet-badge"]')).to_have_text(
            _zh_section_labels("facet", ["chat"])["chat"], timeout=10000)
        assert_no_errors(collectors, "chat landing review convergence")
    finally:
        _sweep(api_client, pid)
