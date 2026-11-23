package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault decision spectrum of the failure-injection kernel (design F9, M7):
 * stub-level typed faults with their own probability, the space-level chaos
 * slider as the fallback gate, and fail-closed refusal on broken fault keys.
 * The roll sequence is scripted so every branch is deterministic.
 */
class MockFaultInjectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void noFaultKeyAndNoChaosServesNormally() {
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier(), 60000)
                .decide(null, 0, json("{}"));
        assertFalse(d.applied());
    }

    @Test
    void delayFaultRollUnderProbabilityAppliesUniformDelayInInterval() {
        // first roll gates probability (0.09 < 0.5), second places delay at
        // 10% into [100, 1100] -> 200ms
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.09, 0.1), 60000)
                .decide(json("{\"type\":\"delay\",\"probability\":50,"
                        + "\"minMs\":100,\"maxMs\":1100}"), 0, json("{}"));
        assertTrue(d.applied());
        assertEquals("delay", d.kind());
        assertEquals(200, d.delayMs());
    }

    @Test
    void delayFaultRollOverProbabilityIsSkippedNotFallenBackOnChaos() {
        // an explicit stub fault key takes the decision entirely — the chaos
        // slider must not second-guess it when the stub's own roll fails
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.9), 60000)
                .decide(json("{\"type\":\"delay\",\"probability\":10,"
                        + "\"minMs\":10,\"maxMs\":20}"), 80, json("{}"));
        assertFalse(d.applied());
    }

    @Test
    void delayIsClampedToTheConfiguredCeiling() {
        // ceiling 3000: roll 0.9 -> raw 9000 must clamp, never serve 9s
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.5, 0.9), 3000)
                .decide(json("{\"type\":\"delay\",\"probability\":60,"
                        + "\"minMs\":0,\"maxMs\":10000}"), 0, json("{}"));
        assertEquals(3000, d.delayMs());
    }

    @Test
    void disconnectFaultNeedsNoExtraParameters() {
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                .decide(json("{\"type\":\"disconnect\"}"), 0, json("{}"));
        assertTrue(d.applied());
        assertEquals("disconnect", d.kind());
    }

    @Test
    void statusFaultOverridesWithFiveXxAndDefaultsTo503() {
        MockFaultDecision set = new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                .decide(json("{\"type\":\"status\",\"statusCode\":500}"), 0, json("{}"));
        assertEquals(500, set.statusOverride());

        MockFaultDecision def = new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                .decide(json("{\"type\":\"status\"}"), 0, json("{}"));
        assertEquals(503, def.statusOverride());
    }

    @Test
    void statusFaultRefusesNonFiveXxCodeFailClosed() {
        // a 200 "fault" is a config mistake — the save chain blocks it, the
        // data plane must refuse rather than pretend
        assertThrows(ServiceException.class, () ->
                new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                        .decide(json("{\"type\":\"status\",\"statusCode\":200}"), 0, json("{}")));
    }

    @Test
    void malformedFaultAppliesAsItsOwnKind() {
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                .decide(json("{\"type\":\"malformed\"}"), 0, json("{}"));
        assertEquals("malformed", d.kind());
    }

    @Test
    void dribbleFaultCarriesChunkGeometry() {
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                .decide(json("{\"type\":\"dribble\",\"chunkBytes\":256,\"chunkDelayMs\":80}"),
                        0, json("{}"));
        assertEquals("dribble", d.kind());
        assertEquals(256, d.chunkBytes());
        assertEquals(80, d.chunkDelayMs());
    }

    @Test
    void unknownFaultTypeFailsClosedWithHumanWords() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                        .decide(json("{\"type\":\"explodes\"}"), 0, json("{}")));
        assertTrue(ex.getMessage().contains("explodes"), ex.getMessage());
    }

    @Test
    void chaosSliderInjectsFiveOthreeWhenNoStubFaultKey() {
        // space chaos 20%, roll 0.1 -> the request gets a 503 chaos hit
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.1), 60000)
                .decide(null, 20, json("{}"));
        assertTrue(d.applied());
        assertEquals("chaos", d.kind());
        assertEquals(503, d.statusOverride());
    }

    @Test
    void chaosRollOverPercentageServesNormally() {
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.5), 60000)
                .decide(null, 20, json("{}"));
        assertFalse(d.applied());
    }

    @Test
    void chaosZeroNeverInjects() {
        MockFaultDecision d = new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                .decide(null, 0, json("{}"));
        assertFalse(d.applied());
    }

    @Test
    void outOfRangeProbabilityFailsClosed() {
        assertThrows(ServiceException.class, () ->
                new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                        .decide(json("{\"type\":\"delay\",\"probability\":150}"), 0, json("{}")));
    }

    @Test
    void invertedDelayIntervalFailsClosed() {
        assertThrows(ServiceException.class, () ->
                new MockFaultInjector(new RollingSupplier().of(0.0, 0.0), 60000)
                        .decide(json("{\"type\":\"delay\",\"probability\":50,"
                                + "\"minMs\":2000,\"maxMs\":100}"), 0, json("{}")));
    }

    @Test
    void malformedFaultRefusesWhenResponseIsNotJson() {
        // a malformed-JSON fault only makes sense on a JSON body; on a plain
        // text stub the injector must fail closed instead of corrupting it
        assertThrows(ServiceException.class, () ->
                new MockFaultInjector(new RollingSupplier().of(0.0), 60000)
                        .decide(json("{\"type\":\"malformed\"}"), 0,
                                json("{\"contentType\":\"text/plain\"}")));
    }
}
