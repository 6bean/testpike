package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests of the session-project stamp on the API pass-through read
 * tool: the conversation's server-side segment — never the model payload —
 * decides which a pass-through read is anchored to (read-side mirror of the
 * write door's owner re-stamp posture). Non-matching tools and missing
 * slices must ride byte-identical.
 */
class PassThroughReadAnchorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void stampsSessionProjectIntoNestedArguments() throws Exception {
        String input = "{\"endpointKey\":\"GET /internal/tags\",\"arguments\":{}}";
        String out = PassThroughReadAnchor.stamp("apiAccess.read", input, 195621L, mapper);
        JsonNode root = mapper.readTree(out);
        assertEquals(195621L, root.path("arguments").path("projectId").asLong());
        assertEquals("GET /internal/tags", root.path("endpointKey").asText());
    }

    @Test
    void overwritesModelClaimedProjectWithSessionSlice() throws Exception {
        String input = "{\"endpointKey\":\"GET /internal/tags\",\"arguments\":{\"projectId\":42}}";
        String out = PassThroughReadAnchor.stamp("apiAccess.read", input, 777L, mapper);
        JsonNode root = mapper.readTree(out);
        assertEquals(777L, root.path("arguments").path("projectId").asLong());
        assertFalse(out.contains("\"projectId\":42"), "payload claim must not survive: " + out);
    }

    @Test
    void createsArgumentsObjectWhenModelOmittedIt() throws Exception {
        String input = "{\"endpointKey\":\"GET /internal/tags\"}";
        String out = PassThroughReadAnchor.stamp("apiAccess.read", input, 777L, mapper);
        JsonNode root = mapper.readTree(out);
        assertEquals(777L, root.path("arguments").path("projectId").asLong());
    }

    @Test
    void keepsOtherToolsByteIdenticalEvenWithSlice() {
        String input = "{\"endpointKey\":\"GET /internal/tags\",\"arguments\":{}}";
        assertEquals(input, PassThroughReadAnchor.stamp("case.create", input, 777L, mapper));
        assertEquals(input, PassThroughReadAnchor.stamp("apiAccess.catalog", input, 777L, mapper));
        assertEquals(input, PassThroughReadAnchor.stamp("apiAccess.write", input, 777L, mapper));
    }

    @Test
    void keepsPayloadUntouchedWithoutSessionSlice() {
        String input = "{\"endpointKey\":\"GET /internal/tags\",\"arguments\":{\"sortBy\":\"name\"}}";
        assertEquals(input, PassThroughReadAnchor.stamp("apiAccess.read", input, null, mapper));
        assertEquals(input, PassThroughReadAnchor.stamp("apiAccess.read", input, 0L, mapper));
        assertEquals(input, PassThroughReadAnchor.stamp("apiAccess.read", input, -1L, mapper));
    }

    @Test
    void failSoftOnUnparseableOrNonObjectPayload() {
        assertEquals("not json", PassThroughReadAnchor.stamp("apiAccess.read", "not json", 777L, mapper));
        assertEquals("[1,2]", PassThroughReadAnchor.stamp("apiAccess.read", "[1,2]", 777L, mapper));
        assertEquals("", PassThroughReadAnchor.stamp("apiAccess.read", "", 777L, mapper));
    }

    @Test
    void nestedNonObjectArgumentsIsReplacedByStampedObject() throws Exception {
        // A model claiming arguments as a scalar is malformed for this tool; the
        // stamp replaces the bag with the anchored one and the core door then
        // refuses any leftover undeclared shape readably. Never throw here.
        String input = "{\"endpointKey\":\"GET /internal/tags\",\"arguments\":\"oops\"}";
        String out = PassThroughReadAnchor.stamp("apiAccess.read", input, 777L, mapper);
        JsonNode root = mapper.readTree(out);
        assertTrue(root.path("arguments").isObject());
        assertEquals(777L, root.path("arguments").path("projectId").asLong());
    }
}
