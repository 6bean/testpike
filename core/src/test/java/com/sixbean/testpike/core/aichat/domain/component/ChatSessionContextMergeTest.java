package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 spectra of the pure merge function behind the session-context refresh
 * : every accepted question folds its own page
 * anchor into the session context — route/objectId/executionId overwrite the
 * session's current values, origin_route records where the conversation
 * started and is written ONCE (first write wins, later questions never move
 * it), and an identical replay changes nothing so the service door skips the
 * UPDATE entirely (R28: empty replays must not touch rows).
 * <p>The merge is a stateless pure component: whitelist checks happen
 * upstream at the single validation door, here only folding rules apply.
 */
class ChatSessionContextMergeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void questionAnchorsOverrideTheSessionValues() throws Exception {
        ChatSessionContextMergeOutcome outcome = ChatSessionContextMerge.merge(
                "{\"route\":\"/cases\",\"objectId\":7,\"executionId\":1,\"projectId\":7}",
                "{\"route\":\"/executions\",\"objectId\":42,\"executionId\":555}");

        assertTrue(outcome.changed(), "moving the anchor is a real change");
        JsonNode merged = MAPPER.readTree(outcome.mergedJson());
        assertEquals("/executions", merged.get("route").asText());
        assertEquals(42, merged.get("objectId").asInt());
        assertEquals(555, merged.get("executionId").asInt());
        assertEquals(7, merged.get("projectId").asInt(), "keys the question does not carry stay");
        assertEquals("/cases", merged.get("origin_route").asText(),
                "the session's pre-existing route is the origin once folded");
    }

    @Test
    void originRouteIsWrittenOnceAndNeverMoved() throws Exception {
        ChatSessionContextMergeOutcome first = ChatSessionContextMerge.merge(
                null, "{\"route\":\"/cases\",\"objectId\":7}");
        assertTrue(first.changed());
        JsonNode afterFirst = MAPPER.readTree(first.mergedJson());
        assertEquals("/cases", afterFirst.get("origin_route").asText(),
                "the first question's route names where the conversation started");

        ChatSessionContextMergeOutcome second = ChatSessionContextMerge.merge(
                first.mergedJson(), "{\"route\":\"/executions\"}");
        assertTrue(second.changed());
        JsonNode afterSecond = MAPPER.readTree(second.mergedJson());
        assertEquals("/executions", afterSecond.get("route").asText());
        assertEquals("/cases", afterSecond.get("origin_route").asText(),
                "origin_route keeps the first value — later questions only move route");
    }

    @Test
    void identicalReplayProducesNoChange() {
        ChatSessionContextMergeOutcome outcome = ChatSessionContextMerge.merge(
                "{\"route\":\"/cases\",\"origin_route\":\"/cases\",\"projectId\":7}",
                "{\"route\":\"/cases\",\"projectId\":7}");

        assertFalse(outcome.changed(), "replaying the same anchors changes nothing");
        assertNull(outcome.mergedJson(), "nothing to write: the door must land no UPDATE (R28)");
    }

    @Test
    void blankQuestionContextIsANoOp() {
        ChatSessionContextMergeOutcome outcome = ChatSessionContextMerge.merge(
                "{\"route\":\"/cases\"}", null);

        assertFalse(outcome.changed());
        assertNull(outcome.mergedJson());
    }
}
