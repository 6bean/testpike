package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure folding of one question's page anchor into the session context
 * . The whitelist check happens upstream at the ONE
 * validation door (ChatSessionService.normalizeContextWhitelist) — this
 * component only applies the merge rules:
 * <ul>
 * <li>the anchor keys the question carries (route / projectId / objectId /
 * executionId / analysisId / documentId) overwrite the session's
 * current values; keys the question omits stay;</li>
 * <li>origin_route names where the conversation started and is written on
 * the FIRST fold only — later questions move {@code route}, never the
 * origin;</li>
 * <li>an identical replay yields {@code changed=false} so the service door
 * lands no UPDATE (R28).</li>
 * </ul>
 * A malformed legacy session value is folded onto an empty base instead of
 * refusing: the question was already validated at the door, and a broken
 * old row must not block the refresh (same posture as the run-door pointer
 * reader).
 */
public final class ChatSessionContextMerge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatSessionContextMerge() {
    }

    /**
     * @param sessionContextJson   the session row's current context (nullable)
     * @param questionContextJson  the question's validated context (nullable)
     */
    public static ChatSessionContextMergeOutcome merge(String sessionContextJson,
                                                       String questionContextJson) {
        if (questionContextJson == null || questionContextJson.isBlank()) {
            return ChatSessionContextMergeOutcome.noChange();
        }
        JsonNode question = readObject(questionContextJson);
        if (question == null) {
            return ChatSessionContextMergeOutcome.noChange();
        }
        JsonNode current = readObject(sessionContextJson);
        ObjectNode merged = (current != null && current.isObject())
                ? ((ObjectNode) current).deepCopy()
                : MAPPER.createObjectNode();

        for (String key : ChatSessionContextMergeOutcome.OVERRIDE_KEYS) {
            JsonNode value = question.get(key);
            if (value != null && !value.isNull()) {
                merged.set(key, value);
            }
        }
        if (!merged.hasNonNull("origin_route")) {
            JsonNode origin = firstNonBlank(current, question, "route");
            if (origin != null) {
                merged.put("origin_route", origin.asText());
            }
        }
        if (current != null && current.equals(merged)) {
            return ChatSessionContextMergeOutcome.noChange();
        }
        return new ChatSessionContextMergeOutcome(true, merged.toString());
    }

    /** Origin precedence: the session's pre-existing route wins over the question's. */
    private static JsonNode firstNonBlank(JsonNode session, JsonNode question, String key) {
        JsonNode fromSession = session == null ? null : session.get(key);
        if (fromSession != null && !fromSession.isNull() && !fromSession.asText().isBlank()) {
            return fromSession;
        }
        JsonNode fromQuestion = question.get(key);
        return fromQuestion != null && !fromQuestion.isNull() && !fromQuestion.asText().isBlank()
                ? fromQuestion : null;
    }

    private static JsonNode readObject(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.isObject() ? node : null;
        } catch (Exception malformed) {
            return null;
        }
    }
}
