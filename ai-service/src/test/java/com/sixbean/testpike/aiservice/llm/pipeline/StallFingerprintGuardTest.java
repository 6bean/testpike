package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the stall guard : the same failure
 * fingerprint (tool x normalized error form) may burn at most
 * {@code blockAfter} retries — every further SAME call (same tool, same
 * arguments) is refused up-front and the caller receives one guidance frame
 * naming the three sanctioned strategies (variant query / clarify / honest
 * residual answer). A different argument shape is NOT the same retry and must
 * stay open, an intervening success clears the streak, and a different error
 * form restarts counting from one.
 */
class StallFingerprintGuardTest {

    private static final String TOOL = "query_cases";
    private static final String ARGS = "{\"keyword\":\"登录失败\"}";

    @Test
    void freshToolIsNeverBlocked() {
        StallFingerprintGuard guard = new StallFingerprintGuard(2);
        StallVerdict verdict = guard.checkBeforeCall(TOOL, ARGS);
        assertFalse(verdict.blocked());
        assertEquals(0, verdict.failureStreak());
        assertNull(verdict.guidance());
    }

    @Test
    void twoSameKindFailuresBlockTheThirdCall() {
        StallFingerprintGuard guard = new StallFingerprintGuard(2);
        guard.recordFailure(TOOL, ARGS, "read timed out after 30 seconds");
        guard.recordFailure(TOOL, ARGS, "read timed out after 5 seconds");
        StallVerdict verdict = guard.checkBeforeCall(TOOL, ARGS);
        assertTrue(verdict.blocked());
        assertEquals(2, verdict.failureStreak());
        assertEquals(FailureTriage.ENVIRONMENT, verdict.errorKind());
    }

    @Test
    void guidanceFrameNamesToolKindAndAllThreeStrategies() {
        StallFingerprintGuard guard = new StallFingerprintGuard(2);
        guard.recordFailure(TOOL, ARGS, "HTTP 400: priority must be one of P0..P3");
        guard.recordFailure(TOOL, ARGS, "HTTP 400: priority must be one of P1..P2");
        StallVerdict verdict = guard.checkBeforeCall(TOOL, ARGS);
        assertTrue(verdict.blocked());
        String frame = verdict.guidance();
        assertNotNull(frame);
        assertTrue(frame.contains(TOOL), "frame names the offending tool");
        assertTrue(frame.contains("换一种"), "frame offers the variant-query exit");
        assertTrue(frame.contains("追问"), "frame offers the clarify exit");
        assertTrue(frame.contains("如实"), "frame offers the honest-residual exit");
    }

    @Test
    void differentErrorFormRestartsTheStreakFromOne() {
        StallFingerprintGuard guard = new StallFingerprintGuard(2);
        guard.recordFailure(TOOL, ARGS, "read timed out after 30 seconds");
        guard.recordFailure(TOOL, ARGS, "HTTP 400: field priority is required");
        assertFalse(guard.checkBeforeCall(TOOL, ARGS).blocked(),
                "two different fault forms are not the same fingerprint twice");
    }

    @Test
    void successClearsTheStreakForThatCallShape() {
        StallFingerprintGuard guard = new StallFingerprintGuard(2);
        guard.recordFailure(TOOL, ARGS, "read timed out after 30 seconds");
        guard.recordFailure(TOOL, ARGS, "read timed out after 5 seconds");
        assertTrue(guard.checkBeforeCall(TOOL, ARGS).blocked());
        guard.recordSuccess(TOOL, ARGS);
        assertFalse(guard.checkBeforeCall(TOOL, ARGS).blocked());
    }

    @Test
    void variantArgumentsAreNotTheSameRetryAndStayOpen() {
        StallFingerprintGuard guard = new StallFingerprintGuard(2);
        guard.recordFailure(TOOL, ARGS, "read timed out");
        guard.recordFailure(TOOL, ARGS, "read timed out");
        assertTrue(guard.checkBeforeCall(TOOL, ARGS).blocked());
        assertFalse(guard.checkBeforeCall(TOOL, "{\"keyword\":\"登录超时\"}").blocked(),
                "the sanctioned variant exit must not be locked out");
    }

    @Test
    void thresholdIsConfigurableSoWiringCanHotUpdateIt() {
        StallFingerprintGuard guard = new StallFingerprintGuard(1);
        guard.recordFailure(TOOL, ARGS, "read timed out");
        assertTrue(guard.checkBeforeCall(TOOL, ARGS).blocked(),
                "blockAfter=1 refuses the second identical attempt");
    }
}
