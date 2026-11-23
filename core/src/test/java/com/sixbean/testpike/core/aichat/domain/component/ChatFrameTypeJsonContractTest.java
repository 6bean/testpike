package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.api.dto.ChatFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the documented internal-HTTP contract: a frame type crosses the wire as
 * its SSE wire name (the same literal stored in the ledger and emitted in the
 * {@code event:} field), never as the Java enum name. This is the regression
 * gate for the hyphen-bearing name (user-inject): a raw enum serialization
 * leaks "USER_INJECT" and every consumer that maps enum names by lowercasing
 * turns it into "user_inject", hiding the frame from the live SSE pump while
 * the ledger still shows it.
 */
class ChatFrameTypeJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyFrameTypeSerializesAsItsWireName() throws Exception {
        for (ChatFrameType type : ChatFrameType.values()) {
            assertEquals("\"" + type.wireName() + "\"",
                    mapper.writeValueAsString(type),
                    "frame type must ride the internal HTTP leg as its wire name");
        }
    }

    @Test
    void frameDtoCarriesWireNameNotEnumName() throws Exception {
        String json = mapper.writeValueAsString(new ChatFrame(1L, 2L, 3L, 5L,
                ChatFrameType.USER_INJECT, "steering text", null));
        assertTrue(json.contains("\"user-inject\""),
                "ledger frame DTO must not leak the enum name: " + json);
    }

    @Test
    void wireNameDeserializesBackToTheType() throws Exception {
        ChatFrameType parsed = mapper.readValue("\"user-inject\"", ChatFrameType.class);
        assertEquals(ChatFrameType.USER_INJECT, parsed,
                "the wire name must round-trip back to the ledger type");
    }
}
