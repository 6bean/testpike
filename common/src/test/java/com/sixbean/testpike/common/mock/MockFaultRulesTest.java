package com.sixbean.testpike.common.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault parameter-domain legality single source . The save chain
 * (core MockRuleValidator) and the data plane (MockFaultInjector) must consume
 * this one spectrum — a half-configured fault must be blocked before rows land,
 * with plain-word violations naming the offending value.
 */
class MockFaultRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String violation(String faultJson) {
        JsonNode root = faultJson == null ? json("{}") : json(faultJson);
        return MockFaultRules.faultViolation(root.get("fault"), root);
    }

    @Test
    void missingFaultKeyIsLegal() {
        assertNull(violation(null));
    }

    @Test
    void explicitNullFaultKeyIsLegal() {
        assertNull(MockFaultRules.faultViolation(json("{\"fault\":null}").get("fault"),
                json("{\"fault\":null}")));
    }

    @Test
    void eachFaultTypeWithSaneParametersPasses() {
        assertNull(violation("{\"fault\":{\"type\":\"delay\",\"minMs\":100,\"maxMs\":2000}}"));
        assertNull(violation("{\"fault\":{\"type\":\"disconnect\"}}"));
        assertNull(violation("{\"fault\":{\"type\":\"status\",\"statusCode\":500}}"));
        assertNull(violation("{\"fault\":{\"type\":\"malformed\"}}"));
        assertNull(violation("{\"fault\":{\"type\":\"dribble\",\"chunkBytes\":64,\"chunkDelayMs\":50}}"));
    }

    @Test
    void omittedOptionalParametersFallOnLegalDefaults() {
        // delay without interval, status without code, dribble without geometry:
        // the defaults the injector serves with must validate as legal
        assertNull(violation("{\"fault\":{\"type\":\"delay\"}}"));
        assertNull(violation("{\"fault\":{\"type\":\"status\"}}"));
        assertNull(violation("{\"fault\":{\"type\":\"dribble\"}}"));
    }

    @Test
    void faultMustBeAnObject() {
        assertNotNull(violation("{\"fault\":\"delay\"}"));
        assertNotNull(violation("{\"fault\":[1,2]}"));
    }

    @Test
    void unknownTypeIsNamedInTheViolation() {
        String v = violation("{\"fault\":{\"type\":\"explodes\"}}");
        assertNotNull(v);
        assertTrue(v.contains("explodes"), v);
        MockFaultRules.FAULT_TYPES.forEach(t -> assertTrue(v.contains(t), v));
    }

    @Test
    void missingTypeIsRefused() {
        assertNotNull(violation("{\"fault\":{\"minMs\":10}}"));
    }

    @Test
    void probabilityOutsideZeroToOneHundredIsRefused() {
        assertNotNull(violation("{\"fault\":{\"type\":\"delay\",\"probability\":150}}"));
        assertNotNull(violation("{\"fault\":{\"type\":\"delay\",\"probability\":-1}}"));
        assertNull(violation("{\"fault\":{\"type\":\"delay\",\"probability\":0}}"));
        assertNull(violation("{\"fault\":{\"type\":\"delay\",\"probability\":100}}"));
    }

    @Test
    void invertedOrNegativeDelayIntervalIsRefused() {
        assertNotNull(violation("{\"fault\":{\"type\":\"delay\",\"minMs\":2000,\"maxMs\":100}}"));
        assertNotNull(violation("{\"fault\":{\"type\":\"delay\",\"minMs\":-5}}"));
    }

    @Test
    void statusFaultOutsideFiveXxIsRefused() {
        // forging a business 200/404 is not fault injection — same bound the
        // data plane refuses, one spectrum
        assertNotNull(violation("{\"fault\":{\"type\":\"status\",\"statusCode\":200}}"));
        assertNotNull(violation("{\"fault\":{\"type\":\"status\",\"statusCode\":404}}"));
        assertNotNull(violation("{\"fault\":{\"type\":\"status\",\"statusCode\":600}}"));
        assertNull(violation("{\"fault\":{\"type\":\"status\",\"statusCode\":599}}"));
    }

    @Test
    void malformedFaultOnNonJsonBodyIsRefused() {
        assertNotNull(violation("{\"contentType\":\"text/plain\",\"fault\":{\"type\":\"malformed\"}}"));
        assertNull(violation("{\"contentType\":\"application/json\",\"fault\":{\"type\":\"malformed\"}}"));
        // no contentType declared = the builder's JSON happy path
        assertNull(violation("{\"fault\":{\"type\":\"malformed\"}}"));
    }

    @Test
    void dribbleWithNonPositiveChunkBytesOrNegativeDelayIsRefused() {
        assertNotNull(violation("{\"fault\":{\"type\":\"dribble\",\"chunkBytes\":0}}"));
        assertNotNull(violation("{\"fault\":{\"type\":\"dribble\",\"chunkBytes\":-8}}"));
        assertNotNull(violation("{\"fault\":{\"type\":\"dribble\",\"chunkDelayMs\":-1}}"));
    }

    @Test
    void chaosLabelIsNotAStubFaultType() {
        // "chaos" belongs to the space slider layer, never to a stub fault key
        assertNotNull(violation("{\"fault\":{\"type\":\"chaos\"}}"));
        assertEquals(5, MockFaultRules.FAULT_TYPES.size());
    }
}
