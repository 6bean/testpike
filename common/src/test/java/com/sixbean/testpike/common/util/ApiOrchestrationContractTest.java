package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Orchestration vocabulary contract tests (precondition + grouped loop).
 * <p>Each case pins one decision row of the form grammar: legal shapes pass,
 * out-of-domain enums/operators are named in plain language, and the matcher
 * sub-rows delegate to ExpectMatchContract instead of re-checking them here.
 */
class ApiOrchestrationContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- precondition ----------

    @Test
    void absentOrEmptyPreconditionIsLegal() {
        assertNull(ApiOrchestrationContract.preconditionViolation(null));
        assertNull(ApiOrchestrationContract.preconditionViolation(json("{}")));
    }

    @Test
    void statusPreconditionAcceptsEqNeIn() {
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}")));
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"ne\",\"expect\":404}")));
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"in\",\"expect\":[200,201]}")));
    }

    @Test
    void statusExpectMustBeWithinHttpRange() {
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"eq\",\"expect\":99}")));
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"eq\",\"expect\":\"200\"}")));
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"in\",\"expect\":[]}")));
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"in\",\"expect\":[200,999999]}")));
    }

    @Test
    void unknownFieldOrOpIsNamedWithTheFullDomain() {
        String fieldViolation = ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"body\",\"op\":\"eq\",\"expect\":200}"));
        assertNotNull(fieldViolation);
        assertEquals(true, fieldViolation.contains("status") && fieldViolation.contains("var"));

        String opViolation = ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"status\",\"op\":\"gt\",\"expect\":200}"));
        assertNotNull(opViolation);
        assertEquals(true, opViolation.contains("gt"));
    }

    @Test
    void varPreconditionAcceptsExistenceAndEqualityForms() {
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"token\",\"op\":\"exists\"}")));
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"order.state\",\"op\":\"eq\",\"expect\":\"PAID\"}")));
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"x\",\"op\":\"absent\"}")));
    }

    @Test
    void varPreconditionRequiresAWellFormedName() {
        // exists/absent carry no expect; eq/ne require one; missing/blank name fails.
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"op\":\"exists\"}")));
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"bad name\",\"op\":\"exists\"}")));
        assertNotNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"token\",\"op\":\"eq\"}")));
    }

    @Test
    void varMatcherDomainIsDelegatedToExpectMatchContract() {
        // matches reuses the ExpectMatchContract regex/contains/exact vocabulary —
        // an illegal matchType must be rejected verbatim, no second grammar here.
        String violation = ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"token\",\"op\":\"matches\","
                        + "\"expect\":\"abc\",\"matchType\":\"fuzzy\"}"));
        assertNotNull(violation);
        assertEquals(true, violation.contains("fuzzy"));
        assertNull(ApiOrchestrationContract.preconditionViolation(
                json("{\"field\":\"var\",\"name\":\"token\",\"op\":\"matches\","
                        + "\"expect\":\"abc\",\"matchType\":\"contains\"}")));
    }

    // ---------- loop ----------

    @Test
    void absentOrEmptyLoopIsLegal() {
        assertNull(ApiOrchestrationContract.loopViolation(null));
        assertNull(ApiOrchestrationContract.loopViolation(json("{}")));
    }

    @Test
    void countLoopNeedsAnIntegerWithinBounds() {
        assertNull(ApiOrchestrationContract.loopViolation(json("{\"mode\":\"count\",\"times\":5}")));
        assertNotNull(ApiOrchestrationContract.loopViolation(json("{\"mode\":\"count\"}")));
        assertNotNull(ApiOrchestrationContract.loopViolation(json("{\"mode\":\"count\",\"times\":0}")));
        assertNotNull(ApiOrchestrationContract.loopViolation(
                json("{\"mode\":\"count\",\"times\":" + (ApiOrchestrationContract.LOOP_TIMES_MAX + 1) + "}")));
        assertEquals(ApiOrchestrationContract.LOOP_TIMES_MAX, 100);
    }

    @Test
    void untilLoopCarriesAValidatedStopRowAndAMax() {
        assertNull(ApiOrchestrationContract.loopViolation(json("""
                {"mode":"until","maxTimes":10,
                 "until":{"field":"status","op":"eq","expect":200}}""")));
        assertNull(ApiOrchestrationContract.loopViolation(json("""
                {"mode":"until","maxTimes":10,
                 "until":{"field":"var","name":"ready","op":"exists"}}""")));
        // until row itself must be a legal precondition row
        assertNotNull(ApiOrchestrationContract.loopViolation(json("""
                {"mode":"until","maxTimes":10,
                 "until":{"field":"status","op":"gt","expect":200}}""")));
        assertNotNull(ApiOrchestrationContract.loopViolation(json("{\"mode\":\"until\"}")));
        assertNotNull(ApiOrchestrationContract.loopViolation(
                json("{\"mode\":\"until\",\"maxTimes\":101,\"until\":{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}}")));
    }

    @Test
    void unknownLoopModeIsNamedWithTheFullDomain() {
        String violation = ApiOrchestrationContract.loopViolation(json("{\"mode\":\"foreach\"}"));
        assertNotNull(violation);
        assertEquals(true, violation.contains("foreach"));
        assertEquals(true, violation.contains("count") && violation.contains("until"));
    }

    @Test
    void optionalDelayMsMustBeNonNegativeBounded() {
        assertNull(ApiOrchestrationContract.loopViolation(
                json("{\"mode\":\"count\",\"times\":5,\"delayMs\":500}")));
        assertNotNull(ApiOrchestrationContract.loopViolation(
                json("{\"mode\":\"count\",\"times\":5,\"delayMs\":-1}")));
        assertNotNull(ApiOrchestrationContract.loopViolation(
                json("{\"mode\":\"count\",\"times\":5,\"delayMs\":3600001}")));
    }
}
