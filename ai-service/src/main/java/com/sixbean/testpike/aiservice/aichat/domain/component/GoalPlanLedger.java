package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure rule layer of the dialog goal-plan ledger .
 * <p>The ReAct loop previously stopped wherever the model stopped writing:
 * a plain-text answer ended the run even with half of a published plan left
 * undone, and the max-rounds exit was a bare refusal. This piece gives the
 * runner and the "continue" turn a single source of truth for the step list
 * stored in tp_ai_chat_goal_plan.steps_json:
 * <ul>
 * <li>anti fake-done — a step may only read done when it carries an
 * evidence anchor (tool-call or citation id), matching the behaviour
 * clause "every key conclusion needs a checkable basis"; an anchor-less
 * done is normalised back to pending;</li>
 * <li>idempotent replay — sameSet compares normalised step sets so the
 * store can skip writes entirely on a replay (revision only rides real
 * changes, same posture as the empty-save guard);</li>
 * <li>resume rendering — renderSystemSection turns the open tail into the
 * ledger block a "continue" turn assembles into its system head.</li>
 * </ul>
 * <p>Deliberately I/O-free and conservative: unparsable or absent JSON
 * degrades to "no ledger" (null/0/empty) instead of throwing, so a corrupt
 * row can never break a live dialog turn.
 */
public final class GoalPlanLedger {

    /** Step states the ledger accepts; anything else normalises to pending. */
    static final String STATE_PENDING = "pending";
    static final String STATE_DONE = "done";
    static final String STATE_SKIPPED = "skipped";
    static final String STATE_BLOCKED = "blocked";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoalPlanLedger() {
    }

    /**
     * Canonical form of one stored step list: every step keeps its title and
     * a whitelisted state, a done step without an evidence anchor is demoted
     * to pending, and the serialisation uses a fixed key order so a replay
     * compares equal regardless of how the producer ordered its keys.
     *
     * @return canonical JSON, or null when the input is absent or unparsable
     */
    public static String normalize(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(stepsJson);
        } catch (Exception e) {
            return null;
        }
        if (!root.isArray()) {
            return null;
        }
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode step : root) {
            String title = step.path("title").asText("").trim();
            if (title.isBlank()) {
                continue;
            }
            ObjectNode node = copyStep(step);
            String state = node.path("state").asText(STATE_PENDING);
            String evidence = node.path("evidence").asText("");
            if (!isKnownState(state)) {
                state = STATE_PENDING;
            }
            if (STATE_DONE.equals(state) && evidence.isEmpty()) {
                // Anti fake-done: a "finished" claim with no tool-call or
                // citation anchor is not a finished step.
                state = STATE_PENDING;
            }
            node.put("state", state);
            out.add(node);
        }
        return out.toString();
    }

    /**
     * Canonical step copy in a fixed key order (title, state, evidence and —
     * when non-blank — note), the single serialisation shape every writer
     * and replay comparison shares.
     */
    private static ObjectNode copyStep(JsonNode step) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("title", step.path("title").asText("").trim());
        node.put("state", step.path("state").asText(STATE_PENDING).trim().toLowerCase());
        node.put("evidence", step.path("evidence").asText("").trim());
        String note = step.path("note").asText("").trim();
        if (!note.isEmpty()) {
            node.put("note", note);
        }
        return node;
    }

    /** Ledger note minted when a run finishes on an unanchored conclusion. */
    public static final String NOTE_UNVERIFIED = "未验证：收口回答无可复核锚";

    /**
     * Ledger note minted when the round-2 attribution guard closes an open
     * ledger because the model self-reported a new goal (behaviour kernel
     * item 1): the unfinished steps end HERE with a stored trace, never
     * silently inherited into the new answer.
     */
    public static final String NOTE_NEW_GOAL_CLOSURE =
            "本轮自报开新，旧目标已显式结束（未完成步骤未继续推进，不进入新答案）";

    /**
     * Stamp {@link #NOTE_UNVERIFIED} onto every open (pending or blocked)
     * step that carries no note yet — the finish door's pass-without-anchor
     * marking. Closed steps (done/skipped) stay untouched; unparsable input
     * answers null like every other pure rule here.
     */
    public static String withUnverifiedNote(String stepsJson) {
        return rewriteOpenSteps(stepsJson, null, NOTE_UNVERIFIED);
    }

    /**
 * open step moves to the terminal skipped state carrying
 * {@link #NOTE_NEW_GOAL_CLOSURE} as its trace. Done steps (and their
 * anchors) stay untouched, the trace is restamped on replay instead of
 * duplicated, and a ledger with nothing open normalises unchanged — the
 * whole call is idempotent (R28 replay discipline). Unparsable input
 * answers null.
 */
    public static String withNewGoalClosure(String stepsJson) {
        return rewriteOpenSteps(stepsJson, STATE_SKIPPED, NOTE_NEW_GOAL_CLOSURE);
    }

    /**
     * Single-source rewrite of the ledger's open tail (R40 — the unverified
     * marking and the new-goal closure share this seam): re-serialises in
     * canonical key order, flips open steps to {@code newState} when given,
     * and stamps {@code note} onto every open step that does not carry it
     * yet. Null {@code newState} keeps the state; closed steps are copied
     * as-is.
     */
    private static String rewriteOpenSteps(String stepsJson, String newState, String note) {
        String canonical = normalize(stepsJson);
        if (canonical == null) {
            return null;
        }
        try {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode step : MAPPER.readTree(canonical)) {
                ObjectNode node = copyStep(step);
                String state = node.path("state").asText(STATE_PENDING);
                boolean open = STATE_PENDING.equals(state) || STATE_BLOCKED.equals(state);
                if (open) {
                    String had = node.path("note").asText("");
                    if (newState != null) {
                        node.put("state", newState);
                        // A terminal closure outranks an earlier soft note:
                        // the goal factually ended here, the trace must read
                        // as the closure even if the step was marked before.
                        node.put("note", note);
                    } else if (had.isEmpty()) {
                        node.put("note", note);
                    }
                }
                out.add(node);
            }
            return out.toString();
        } catch (Exception e) {
            return canonical;
        }
    }

    /**
     * Anti-deletion rule of the ledger note: a stored step that carries a
     * note keeps it through any replay that lost the field (the note is a
     * governance fact of the ledger, not render-time decoration). Matching
     * is by step title; an absent stored ledger passes the input through.
     */
    public static String preserveUnverifiedNote(String storedJson, String incomingCanonical) {
        String stored = normalize(storedJson);
        if (stored == null || incomingCanonical == null) {
            return incomingCanonical;
        }
        try {
            JsonNode storedRoot = MAPPER.readTree(stored);
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode step : MAPPER.readTree(incomingCanonical)) {
                ObjectNode node = copyStep(step);
                if (node.path("note").asText("").isEmpty()) {
                    for (JsonNode old : storedRoot) {
                        if (old.path("title").asText("").equals(node.path("title").asText(""))
                                && !old.path("note").asText("").isEmpty()) {
                            node.put("note", old.path("note").asText(""));
                            break;
                        }
                    }
                }
                out.add(node);
            }
            return out.toString();
        } catch (Exception e) {
            return incomingCanonical;
        }
    }

    /**
     * True when both sides carry the same step set — the store's zero-write
     * replay test. Set semantics (line-wise order aside, since a model
     * re-emitting the same plan may reorder it); two unparsable/absent
     * ledgers are "the same" (nothing to write), one ledger versus none is a
     * real change.
     */
    public static boolean sameSet(String leftJson, String rightJson) {
        String a = canonicalSortedLines(normalize(leftJson));
        String b = canonicalSortedLines(normalize(rightJson));
        return a == null ? b == null : a.equals(b);
    }

    /** Open steps that still owe work: pending or blocked (skipped is closed). */
    public static int pendingCount(String stepsJson) {
        return openTitles(stepsJson).size();
    }

    /**
     * Titles of the still-open steps (pending or blocked) in stored order —
     * the list both the user-facing resume frame and the model-facing resume
     * block point at, so the two can never name different steps. Absent or
     * unparsable ledgers answer "nothing open" (empty), never a throw.
     */
    public static List<String> openTitles(String stepsJson) {
        String canonical = normalize(stepsJson);
        if (canonical == null) {
            return List.of();
        }
        try {
            List<String> titles = new ArrayList<>();
            for (JsonNode step : MAPPER.readTree(canonical)) {
                String state = step.path("state").asText("");
                if (STATE_PENDING.equals(state) || STATE_BLOCKED.equals(state)) {
                    titles.add(step.path("title").asText(""));
                }
            }
            return titles;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** The single-source resume trigger word a user sends to continue an open ledger. */
    public static final String RESUME_COMMAND = "继续";

    /**
     * True when the prompt IS the resume command (whole-prompt equality after
     * trimming blanks and the quote marks a UI may round-trip). A sentence
     * that merely starts with the word is a new question, not a resume —
     * the ledger block must never hijack fresh intent.
     */
    public static boolean isResumeCommand(String prompt) {
        if (prompt == null) {
            return false;
        }
        String trimmed = prompt.trim().replaceAll("[\"'“”「」]", "").trim();
        return trimmed.equals(RESUME_COMMAND);
    }

    /**
     * The resume block for a "continue" turn: completed steps (with their
     * anchors, so the model does not redo verified work) and the open tail
     * spelled out, closed by the instruction to pick up from the first open
     * step. Empty when nothing is open — a closed ledger never re-enters the
     * prompt.
     */
    public static String renderSystemSection(String stepsJson) {
        String canonical = normalize(stepsJson);
        if (canonical == null) {
            return "";
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(canonical);
        } catch (Exception e) {
            return "";
        }
        StringBuilder done = new StringBuilder();
        StringBuilder open = new StringBuilder();
        for (JsonNode step : root) {
            String state = step.path("state").asText("");
            String title = step.path("title").asText("");
            String evidence = step.path("evidence").asText("");
            String note = step.path("note").asText("");
            String line = "- " + title + (evidence.isEmpty() ? "" : "（依据：" + evidence + "）")
                    + (note.isEmpty() ? "" : "（" + note + "）");
            if (STATE_DONE.equals(state)) {
                done.append(line).append('\n');
            } else if (STATE_PENDING.equals(state) || STATE_BLOCKED.equals(state)) {
                open.append(line).append(STATE_BLOCKED.equals(state) ? "（此前受阻）" : "").append('\n');
            }
        }
        if (open.length() == 0) {
            return "";
        }
        StringBuilder section = new StringBuilder("【目标账本】本会话目标尚未完成，已完成步骤：\n");
        section.append(done.length() == 0 ? "- （无）\n" : done);
        section.append("未完成步骤：\n").append(open);
        section.append("请从第一条未完成步骤继续推进，不要重复已完成的工作；"
                + "确无法完成的步骤改为跳过并说明原因，全部处理完再给最终回答。");
        return section.toString();
    }

    private static boolean isKnownState(String state) {
        return STATE_PENDING.equals(state) || STATE_DONE.equals(state)
                || STATE_SKIPPED.equals(state) || STATE_BLOCKED.equals(state);
    }

    /** Sorted line form of a canonical step array (order-insensitive set key). */
    private static String canonicalSortedLines(String canonicalJson) {
        if (canonicalJson == null) {
            return null;
        }
        try {
            List<String> sorted = new ArrayList<>();
            for (JsonNode step : MAPPER.readTree(canonicalJson)) {
                sorted.add(step.toString());
            }
            Collections.sort(sorted);
            return String.join("\n", sorted);
        } catch (Exception e) {
            return null;
        }
    }
}
