package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests of the server-stamped conversation anchors on the two
 * dialog OCR read tools : the running conversation —
 * never the model payload — decides {@code chatSessionId}/{@code projectId}/
 * {@code operatorId}. Unlike {@link PassThroughReadAnchor} (fail-soft, additive
 * only) this stamp is UNCONDITIONAL on its two tool names: a missing server
 * anchor strips the model's claim instead of letting it ride on, so an
 * unanchored link (async generation) can never smuggle a forged session id
 * through to the doors, which then refuse fail-closed on the missing anchor.
 */
class DialogOcrReadAnchorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stampsThreeServerAnchorsIntoFlatArgumentsBag() throws Exception {
        String input = "{\"objectKey\":\"chat/2026/11/a.png\"}";
        String out = DialogOcrReadAnchor.stamp("ocr.text_extract", input, 88L, 7L, 9L, mapper);
        JsonNode root = mapper.readTree(out);
        assertEquals(88L, root.path("chatSessionId").asLong());
        assertEquals(7L, root.path("projectId").asLong());
        assertEquals(9L, root.path("operatorId").asLong());
        assertEquals("chat/2026/11/a.png", root.path("objectKey").asText());
    }

    @Test
    void gridPostIsStampedToo() throws Exception {
        String out = DialogOcrReadAnchor.stamp("ocr.grid_read", "{}", 88L, 7L, 9L, mapper);
        assertTrue(out.contains("\"chatSessionId\":88"), out);
    }

    @Test
    void overwritesEveryModelClaimedAnchor() throws Exception {
        String input = "{\"objectKey\":\"k\",\"chatSessionId\":1,\"projectId\":42,"
                + "\"operatorId\":999}";
        String out = DialogOcrReadAnchor.stamp("ocr.text_extract", input, 88L, 7L, 9L, mapper);
        assertFalse(out.contains("\"projectId\":42"), "payload claim must not survive: " + out);
        assertFalse(out.contains("\"operatorId\":999"), "payload claim must not survive: " + out);
        assertFalse(out.contains("\"chatSessionId\":1,"), "payload claim must not survive: " + out);
        JsonNode root = mapper.readTree(out);
        assertEquals(88L, root.path("chatSessionId").asLong());
        assertEquals(7L, root.path("projectId").asLong());
        assertEquals(9L, root.path("operatorId").asLong());
    }

    @Test
    void missingServerAnchorStripsModelForgedValue() throws Exception {
        String input = "{\"objectKey\":\"k\",\"chatSessionId\":123,\"projectId\":456,"
                + "\"operatorId\":789}";
        String out = DialogOcrReadAnchor.stamp("ocr.text_extract", input, null, 0L, 9L, mapper);
        JsonNode root = mapper.readTree(out);
        assertFalse(root.has("chatSessionId"), "forged session must be stripped: " + out);
        assertFalse(root.has("projectId"), "forged project must be stripped: " + out);
        assertEquals(9L, root.path("operatorId").asLong());
    }

    @Test
    void keepsOtherToolsByteIdenticalEvenWithAnchors() {
        String input = "{\"objectKey\":\"k\",\"chatSessionId\":1}";
        assertEquals(input, DialogOcrReadAnchor.stamp("apiAccess.read", input, 88L, 7L, 9L, mapper));
        assertEquals(input, DialogOcrReadAnchor.stamp("case.create", input, 88L, 7L, 9L, mapper));
        // Near-miss names must not route: prefix sharing is not tool identity.
        assertEquals(input, DialogOcrReadAnchor.stamp("ocr.text_extract_v2", input, 88L, 7L, 9L, mapper));
    }

    @Test
    void keepsBlankAndNonObjectPayloadsUntouched() {
        assertEquals("", DialogOcrReadAnchor.stamp("ocr.text_extract", "", 88L, 7L, 9L, mapper));
        assertEquals(null, DialogOcrReadAnchor.stamp("ocr.text_extract", null, 88L, 7L, 9L, mapper));
        String array = "[1,2]";
        assertEquals(array, DialogOcrReadAnchor.stamp("ocr.grid_read", array, 88L, 7L, 9L, mapper));
    }
}
