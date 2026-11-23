"""Shared wiring for the ai-chat live-spectrum E2E family (plan card, goal
progress card, rehearsal risk card).

Every member of that family drives one or more real streamed turns on the
shared LLM loop inside a single-worker xdist group, then folds session data
and reloads the chat page. The bootstrap surface (site URL, token injection,
DB read helper, error gate, scratch-project seed/purge) is identical, so it
lives exactly here -- spec modules import this single source instead of
re-typing the same conftest wiring (R40).

The scratch project created through ``seed_project`` must always be dropped
in a ``finally`` block via ``purge_project`` so the demo projects stay
untouched.
"""
import json
import time

import httpx
from conftest import (
    BASE_URL,
    _set_sys_config_value,
    assert_no_errors,
    core_base_url,
    inject_token,
    purge_project,
    seed_project,
    sql_query,
)

# Dock selectors shared by the live-spectrum specs (R40 single source): the
# dock shell and its input live on every chat-dock surface, so one renamed
# data-test attribute must never split the family into passing siblings and
# silently-blind ones.
DOCK = '[data-test="ai-chat-dock"]'
INPUT = '[data-test="chat-dock-input"]'


def rig_page(p, cdp_port=None):
    """One shared vision-rig browser posture: new headless (`--headless=new`
    is the Audits/CDP requirement — old headless silently reports zero issues),
    fixed 1920x1080 viewport for face comparability, generous navigation and
    default timeouts, and the login token injected before any goto. Returns
    ``(browser, page)``; the caller owns the close. Every vision_shots_* rig
    launches through here so the posture can never drift between feeds (R40).
    """
    args = ["--headless=new"]
    if cdp_port:
        args.append(f"--remote-debugging-port={cdp_port}")
    browser = p.chromium.launch(headless=True, args=args)
    ctx = browser.new_context(viewport={"width": 1920, "height": 1080})
    page = ctx.new_page()
    page.set_default_navigation_timeout(120000)
    page.set_default_timeout(60000)
    inject_token(page)
    return browser, page


def wait_theme_settled(page, timeout_ms=4000):
    """Block until the theme swap visible on <body> has finished animating.

    theme.scss puts a 0.3s transition on the body background colour, so a
    facts read or screenshot taken right after a dark->light toggle can
    capture a blended mid-transition value (observed: rgb(215,218,224)
    instead of the light palette) and the scoring feed then refuses the face
    as a mislabelled capture. Waiting for the element's running CSS
    animations to drain is exact and cheaper than any fixed sleep. Every
    vision_shots_* rig that pins bodyBackground reads through here (R40).
    """
    page.wait_for_function(
        "() => document.body.getAnimations().length === 0",
        timeout=timeout_ms)


def send_unambiguous(page, text):
    """Type one question and hand it to the regular send door (Enter).

 Specs keep their prompts free of ambiguity on purpose: a live model that
 answers with a clarify card would end the turn without an assistant block,
 and a parked proposal would land on the wrong message .
 """
    inp = page.locator('[data-test="chat-dock-input"]')
    inp.fill(text)
    inp.press("Enter")


def wait_streaming_message(api_deadline_s, pid):
    """Poll the ledger for the live run's streaming assistant block."""
    deadline = time.time() + api_deadline_s
    while time.time() < deadline:
        sid = sql_query(
            "SELECT id FROM tp_ai_chat_session WHERE project_id="
            f"{pid} ORDER BY id DESC LIMIT 1")
        msg_id = sql_query("SELECT id FROM tp_ai_chat_message WHERE session_id="
                           f"{sid} AND role='assistant' AND status='streaming' "
                           "ORDER BY id DESC LIMIT 1") if sid else ""
        if msg_id:
            return int(sid), int(msg_id)
        time.sleep(0.5)
    raise AssertionError("no streaming assistant block appeared for the live run")


def wait_session_opened(api_deadline_s, pid):
    """Poll the ledger for the live run's session row (the dock creates it on
    the first turn, so a spectrum that journals against a session needs it)."""
    deadline = time.time() + api_deadline_s
    while time.time() < deadline:
        sid = sql_query("SELECT id FROM tp_ai_chat_session WHERE project_id="
                        f"{pid} ORDER BY id DESC LIMIT 1")
        if sid:
            return int(sid)
        time.sleep(0.5)
    raise AssertionError("the live run never opened a session row")


def wait_assistant_sealed(api_deadline_s, sid):
    """Block until no assistant block of the session is still streaming."""
    deadline = time.time() + api_deadline_s
    while time.time() < deadline:
        if int(sql_query("SELECT count(*) FROM tp_ai_chat_message WHERE session_id="
                         f"{sid} AND status='streaming'")) == 0:
            return
        time.sleep(1.0)
    raise AssertionError(f"the streamed turn of session {sid} never sealed in time")


def park_clarify(sid, msg_id, question, options, missing_slot):
    """Journal a clarify card onto the live run's streaming block.

    The frame body and meta shape are byte-shaped like
    ChatRunOrchestrator.emitClarifyFrame (one fold point parseClarifyCard in
    chatDockReducer), and the door is the same sink the orchestrator journals
    through, so the dock renders a parked card exactly like a gated live one.
    """
    meta = {"question": question, "options": list(options),
            "missingSlot": missing_slot}
    payload = ("需要你确认一点：" + question + "\n"
               + "\n".join(f"{i + 1}. {opt}" for i, opt in enumerate(options))
               + "\n点选一个选项，或直接回复补充说明。")
    landed = httpx.post(f"{core_base_url()}/internal/ai-chat/sessions/{sid}/frames",
                        json={"messageId": msg_id, "frameType": "clarify",
                              "payload": payload,
                              "metaJson": json.dumps(meta, ensure_ascii=False)},
                        timeout=30).json()
    assert landed.get("code") == "0", landed


def goal_steps_json(uniq, pending=30, blocked=1, done=3, all_done=False):
    pass
    steps = [{"title": f"目标残步{i:02d}{uniq}",
              "state": "done" if all_done else "pending",
              "evidence": f"docs/goal-e2e-{i:02d}" if all_done else ""}
             for i in range(1, pending + 1)]
    for i in range(1, blocked + 1):
        steps.append({"title": f"目标阻步{i:02d}{uniq}",
                      "state": "done" if all_done else "blocked",
                      "evidence": f"docs/goal-e2e-blocked-{i:02d}" if all_done else ""})
    steps += [{"title": f"目标毕步{i:02d}{uniq}", "state": "done",
               "evidence": f"docs/goal-e2e-done-{i:02d}"} for i in range(1, done + 1)]
    return json.dumps(steps, ensure_ascii=False)


def journal_goal_ledger(sid, steps_json, revision=0):
    """Write the session's current ledger row through the table's own upsert."""
    sql_query("INSERT INTO tp_ai_chat_goal_plan"
              " (session_id, plan_version, steps_json, revision)"
              f" VALUES ({sid}, 1, $goaljson${steps_json}$goaljson$, {revision})"
              " ON CONFLICT (session_id, plan_version) DO UPDATE"
              " SET steps_json = EXCLUDED.steps_json,"
              "     revision = tp_ai_chat_goal_plan.revision + 1")


def db_open_count(sid):
    """The ledger is the source: pending+blocked titles counted from the row
 the reload card actually consumes — the newest ``(plan_version, revision)``
 ledger row, mirroring ChatFrameServiceImpl.foldGoalPlanTail's read door.
 Measured live (probe): a re-proposed plan bumps the revision on
 the same version, so pinning ``plan_version=1`` read a stale row while
 the card rendered the newest ledger (0/7 vs open=4 was the assertion's
 skew, not an INV5 product defect)."""
    stored = sql_query("SELECT steps_json FROM tp_ai_chat_goal_plan"
                       f" WHERE session_id={sid}"
                       " ORDER BY plan_version DESC, revision DESC LIMIT 1")
    return sum(1 for s in json.loads(stored) if s.get("state") in ("pending", "blocked"))


def park_suggest_done(sid, msg_id, chips):
    """Journal a done frame carrying the suggest member through the ledger door.

    The payload is byte-shaped like ChatRunOrchestrator.appendDoneThenClose
    (messageId/promptTokens/completionTokens/suggest); the internal frame door
    is the same sink the orchestrator journals through, so the browser folds a
    parked done exactly like a gated live one.
    """
    suggest = [{"text": t, "capability": "case.generate"} for t in chips]
    payload = json.dumps({"messageId": msg_id, "promptTokens": 12,
                          "completionTokens": 34, "suggest": suggest},
                         ensure_ascii=False)
    landed = httpx.post(f"{core_base_url()}/internal/ai-chat/sessions/{sid}/frames",
                        json={"messageId": msg_id, "frameType": "done",
                              "payload": payload, "metaJson": None},
                        timeout=30).json()
    assert landed.get("code") == "0", landed


def park_data_moment_state(sid, msg_id, data_moment_iso):
    """Journal a retrieval-shaped state frame carrying the dataMoment meta.

    The frame shape mirrors the stamp ChatRunOrchestrator puts on its
    retrieval state frame (meta {"dataMoment":"<ISO offset datetime>"} —
    folded at the ONE point in chatDockReducer's state branch), so the dock
    renders a parked stamp exactly like a gated live one. Passing None
    journals the meta-less shape an untimed pool sends: the honest no-label
    posture (no invented clock on screen).
    """
    meta = (json.dumps({"dataMoment": data_moment_iso}, ensure_ascii=False)
            if data_moment_iso else None)
    landed = httpx.post(f"{core_base_url()}/internal/ai-chat/sessions/{sid}/frames",
                        json={"messageId": msg_id, "frameType": "state",
                              "payload": "正在检索项目内的最新资料。",
                              "metaJson": meta},
                        timeout=30).json()
    assert landed.get("code") == "0", landed
