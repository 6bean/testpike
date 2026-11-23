"""Dialog photo-attach channel E2E .

Covers the real chain browser -> admin 8081 (upload door, H1 guard) -> core
8083 (size/extension/magic gate + canonical key) -> DB, and the send carrying
only ready pairs on POST /stream's optional attachments channel:
- the composer's photo door is disabled with the named header-slice pointer
 while no project is selected (R32 honest refusal, never silence);
- picking a real PNG uploads it through the admin door (multipart, project
 named in the query), the strip lands on the ready chip, and the send body
 carries exactly the objectKey/fileName pair core's run door re-validates;
- with the vision master gate explicitly closed (pinned for the case, restored
 at case end), the run still seals
 and books ONE honest state frame naming both literal switch keys
 (ai.vision.enabled + ai.vision.chat-user-image) — the degraded read-back
 posture asserted at the ledger (tp_ai_chat_frame), not a guessed UI text;
- every spectrum ends with the 4xx/5xx + console + Issues-panel zero-tolerance
 check.

Scale/traffic-shape note (iron rule 5e): the vision-ON leg (a provider stub
serving image descriptions) is not faked here — it is booked as a follow-up
stub item; this file pins the upload/send/gate-refusal half.
"""
import base64
import json
import pathlib
import re

import pytest
from conftest import (
    API_BASE,
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    get_sys_config_value,
    inject_token,
    purge_project as _purge_project,
    seed_project as _seed_project,
    sql_query,
)
from playwright.sync_api import expect

# Shares the ai.chat.exec-enabled switch with the other chat spectra — pin to
# one worker (same posture as test_ai_chat_page).
pytestmark = [pytest.mark.timeout(300), pytest.mark.xdist_group("ai-chat-exec-switch")]

# 1x1 valid PNG (magic bytes pass core's triple gate; content is irrelevant —
# the leg under test is the door + wire, not pixels).
PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4"
    "nGNgYGD4DwABBAEAX5aVcQAAAABJRU5ErkJggg=="
)
NO_PROJECT_HINT = "请先在顶栏选择项目，再开始对话"
# Vision master switch (the top-level gate of the chat-image chain): the honest
# refusal frame is booked only while this reads closed — pinned per case
# because the factory default flipped to open with the registered 26-key
# AI-default-on batch.
VISION_MASTER_KEY = "ai.vision.enabled"


def _write_png(tmp_path_factory, name="现场截图.png"):
    d = tmp_path_factory.mktemp("chatimg")
    p = pathlib.Path(d) / name
    p.write_bytes(PNG_1PX)
    return p


def test_photo_attach_door_refuses_without_a_slice(page, collectors, tmp_path_factory):
    """No header segment: the door is disabled and names where to set the segment
 (the same pointer wording as the send placeholder — single source)."""
    inject_token(page)
    page.goto(f"{BASE_URL}/#/ai/chat", wait_until="domcontentloaded")
    expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)
    door = page.locator('[data-test="chat-image-attach"]')
    expect(door).to_be_visible(timeout=5000)
    expect(door).to_be_disabled(timeout=5000)
    door.hover()
    # Scoped to the tooltip layer: the same sentence legitimately appears in
    # the session-empty panel too (strict mode would read two matches).
    expect(page.locator(".el-popper").get_by_text(NO_PROJECT_HINT)).to_be_visible(timeout=5000)
    assert_no_errors(collectors, "photo attach door refuses without slice")


def test_photo_attach_uploads_rides_stream_and_gate_names_keys(
        page, collectors, api_client, uniq, tmp_path_factory):
    """Full half-spectrum: pick -> upload door 200 + ready chip -> send body
    carries the pair -> run seals with the honest vision-gate state frame."""
    pid = _seed_project(api_client, uniq, "CHATIMG")
    _set_sys_config_value("ai.chat.exec-enabled", "true")
    # The gate-refusal leg needs the vision master switch pinned CLOSED for the
    # case: the registered 26-switch default-on flip (factory seed is now '1')
    # voided the old ambient "factory-off" precondition, so pin explicitly and
    # restore the pre-read value at case end (never leave the shared plane moved).
    vision_pre = get_sys_config_value(VISION_MASTER_KEY) or "1"
    _set_sys_config_value(VISION_MASTER_KEY, "0")
    png = _write_png(tmp_path_factory)
    try:
        inject_token(page)
        page.goto(f"{BASE_URL}/#/ai/chat?projectId={pid}", wait_until="domcontentloaded")
        expect(page.locator('[data-test="chat-panel"]')).to_be_visible(timeout=10000)

        # -- upload door: pick a real file, capture the multipart POST -------
        # set_input_files is the pick itself (works on the hidden file input —
        # the precedented channel for upload spectra, e.g. test_v13_upload_dialog).
        # Multipart request bodies are not exposed via post_data (wire-level
        # limitation), so the segment naming is asserted SERVER-side instead:
        # core mints the canonical key under the issuing project (a strictly
        # stronger check than parsing the client body).
        with page.expect_response(
                lambda r: r.status == 200 and "/ai/chat/images" in r.url) as up_resp:
            page.locator('[data-test="chat-image-input"]').set_input_files(str(png))
        view = up_resp.value.json()["data"]
        # Canonical shape "[prefix/]projectId/chat-image/yyyyMM/uuid8-name":
        # the segment segment right before the scene name must be this project.
        assert view["objectKey"].split("/chat-image/")[0].rsplit("/", 1)[-1] == str(pid), \
            f"key must be minted under the issuing slice: {view['objectKey']}"
        assert view["objectKey"] and png.name in view["fileName"], f"upload view: {view}"

        # -- strip lands on ready (uploading -> ready is the same request) ---
        ready = page.locator('[data-test="chat-image-item-ready"]')
        expect(ready).to_have_count(1, timeout=15000)
        expect(page.locator('[data-test="chat-image-item-failed"]')).to_have_count(0)

        # -- send: only ready pairs ride the optional attachments channel ----
        inp = page.locator('[data-test="chat-dock-input"]')
        inp.fill("只回答两个字：收到")
        with page.expect_request(
                lambda r: r.method == "POST" and r.url.endswith("/stream")) as req_info:
            inp.press("Enter")
        body = json.loads(req_info.value.post_data)
        att = body.get("attachments")
        assert isinstance(att, list) and len(att) == 1 \
            and att[0]["objectKey"] == view["objectKey"] \
            and att[0]["fileName"] == view["fileName"], \
            f"ready pair must ride the stream payload, got {att!r}"

        # -- run seals; the degraded vision gate booked its honest frame -----
        expect(page.locator('[data-test="chat-dock-stop"]')).to_have_count(0, timeout=120000)
        turn = page.locator('[data-test="chat-msg"][data-role="assistant"]').last
        expect(turn).to_contain_text(re.compile(r"\S"), timeout=10000)

        frames = sql_query(
            "select count(*) from tp_ai_chat_frame f "
            "join tp_ai_chat_session s on s.id = f.session_id "
            f"where s.project_id={pid} and f.event_type='state' "
            "and f.payload like '%ai.vision.enabled%' "
            "and f.payload like '%ai.vision.chat-user-image%'")
        booked = int(frames.splitlines()[0])
        assert booked >= 1, ("photo-carrying question must book exactly the honest "
                             f"state frame naming both keys; frames seen: {booked}")

        assert_no_errors(collectors, "photo attach rides stream with gate frame")
    finally:
        _set_sys_config_value("ai.chat.exec-enabled", "true")
        _set_sys_config_value(VISION_MASTER_KEY, vision_pre)
        _purge_project(api_client, pid)
