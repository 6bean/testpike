
import json
import time

import httpx
import pytest
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    inject_token,
    sql_query,
)
from conftest import (
    purge_project as _purge_project,
)
from conftest import (
    seed_project as _seed_project,
)
from playwright.sync_api import expect

pytestmark = pytest.mark.timeout(300)

CORE_BASE = "http://localhost:8083"


def test_chat_flow_guide_buttons_open_target_page_anchored_by_case(
    page, collectors, api_client, uniq
):
    """Created case -> parked guide frame -> action row -> click lands /review?caseId=."""
    pid = _seed_project(api_client, uniq, "FLOWGUIDE")
    # Live-run door: the turn must stream, so the exec gate rides the same
    # real config API the draft-card journey uses (restored in the tail).
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    created = api_client.post("/cases", json={
        "projectId": pid,
        "title": f"流转指引旅程用例{uniq}",
        "precondition": "存在有效测试账号",
        "expectedResult": "旅程断言可回查",
        "priority": 2, "confidence": 3, "endType": "web",
        "steps": [{"stepNo": 1, "description": "打开登录页", "isAssert": 0}],
    })
    assert created.status_code == 200, created.text
    case_id = created.json()["data"]["id"]
    case_title = f"流转指引旅程用例{uniq}"
    try:
        # The production dial chain, verbatim: core's own flow-guide door answers
        # for the real case (status 1, whose legal transitions the row must show).
        guide_env = httpx.get(f"{CORE_BASE}/internal/cases/flow-guide",
                              params={"caseId": case_id}, timeout=10).json()
        assert guide_env.get("code") == "0", guide_env
        assert guide_env["data"]["currentStatus"] == 1
        node1 = next(s for s in guide_env["data"]["states"] if s["code"] == 1)
        legal_events = [a["event"] for a in node1["actions"]]
        assert "edit_begin" in legal_events

        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        page.locator('[data-test="chat-dock-input"]').fill(f"流转指引按钮旅程探针{uniq}")
        page.locator('[data-test="chat-dock-input"]').press("Enter")

        # Park the guide onto the live streaming block through the same ledger
        # door the ai-service sink writes through (same posture as the
        # case-write draft-card journey).
        sid, msg_id = _wait_streaming_message(60, pid)
        landed = httpx.post(f"{CORE_BASE}/internal/ai-chat/sessions/{sid}/frames",
                            json={"messageId": msg_id, "frameType": "trace",
                                  "payload": f"用例「{case_title}」流转指引（旅程布点）",
                                  "metaJson": json.dumps({"guide": guide_env},
                                                          ensure_ascii=False)},
                            timeout=30).json()
        assert landed.get("code") == "0", landed

        row = page.locator('[data-test="chat-guide-actions"]')
        expect(row).to_be_visible(timeout=30000)
        # The label names both anchors a human needs: the case and its status.
        expect(row).to_contain_text(case_title, timeout=10000)
        expect(row).to_contain_text("待评审", timeout=10000)
        # One button per legal transition of the CURRENT status, whitelisted
        # routes only (the reducer drops any page missing from menuStructure).
        buttons = row.locator('[data-test^="chat-guide-action-"]')
        expect(buttons).to_have_count(len(legal_events), timeout=10000)
        expect(row.locator('[data-test="chat-guide-action-edit_begin"]')).to_be_visible()

        row.locator('[data-test="chat-guide-action-edit_begin"]').click()
        page.wait_for_url("**/review**", timeout=15000)
        assert f"caseId={case_id}" in page.url, page.url
        assert_no_errors(collectors, "flow-guide action buttons open anchored page")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _purge_project(api_client, pid)


# R40: the ledger poll lives in chat_spectrum_support only; this re-export
# keeps every existing call site on the single shared implementation.
from chat_spectrum_support import wait_streaming_message as _wait_streaming_message
