package com.sixbean.testpike.common.aichat;

import java.util.List;
import java.util.Set;

/**
 * Single-source vocabulary for the dialog page-context keys .
 *
 * <p>Before this class the write door (core ChatSessionServiceImpl) and the
 * read-side renderer (ai-service SessionPinnedFacts) each carried a hand
 * copied key list; the two drifted apart (the renderer never learned about
 * analysisId / documentId / origin_route), so an anchor accepted at the door
 * read back as dropped noise at the prompt. One vocabulary now serves both
 * sides: touching the set means touching this file only.
 *
 * <ul>
 * <li>{@link #ENVELOPE_KEYS} — the keys that frame every session payload
 * (route + project). The waterline accounting subtracts them so a fresh
 * session does not read as "2 pinned facts on site" .</li>
 * <li>{@link #DOOR_KEYS} — everything a page may send through the create /
 * run context door; anything else is refused with a named reason.</li>
 * <li>{@link #PINNED_RENDER_ORDER} — the renderable vocabulary, DOOR_KEYS
 * plus origin_route. origin_route is NOT a door key: it is folded
 * server-side by ChatSessionContextMerge on the first question, so
 * only this read side ever sees it. Order is fixed so the prompt
 * prefix stays byte-stable for provider prompt caching.</li>
 * </ul>
 */
public final class ChatContextKeys {

    /** Framing keys every context payload carries (route + project segment). */
    public static final Set<String> ENVELOPE_KEYS = Set.of("route", "projectId");

    /** Navigation identifiers the context door accepts; anything else refuses. */
    public static final Set<String> DOOR_KEYS = Set.of(
            "route", "projectId", "objectId", "executionId", "analysisId", "documentId");

    /** Whitelisted render order for pinned fact lines (door keys + folded origin). */
    public static final List<String> PINNED_RENDER_ORDER = List.of(
            "route", "projectId", "objectId", "executionId",
            "analysisId", "documentId", "origin_route");

    private ChatContextKeys() {
    }
}
