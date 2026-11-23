package com.sixbean.testpike.core.cases.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flaky-quarantine probe outcome judgement (AO batch , closed-loop
 * operations S2: probe a quarantined case, and after N consecutive green runs
 * PROPOSE restoring it - the proposal never clears the mark by itself).
 * <p>Pure function contract under test:
 * <ul>
 * <li>green streak accumulates over newly observed terminal results and
 * resets to zero on any failure/timeout;</li>
 * <li>the proposal fires exactly once, on the turn the streak reaches the
 * threshold, and stays quiet while already proposed (no duplicate
 * cards, no duplicate notifications);</li>
 * <li>a failure arriving after a pending proposal withdraws it (the case is
 * flaky again, so the pending restore suggestion must not linger);</li>
 * <li>the threshold is an argument (hot-updatable key
 * ai.flaky.probe.green-count), never a constant baked into the rule;</li>
 * <li>only terminal statuses count (3 green; 4/5 red) - a caller that
 * leaks a non-terminal status must not distort the streak.</li>
 * </ul>
 */
class FlakyProbeCalculatorTest {

    private static final int GREEN = 3;
    private static final int RED = 4;
    private static final int TIMEOUT = 5;
    private static final int RUNNING = 2;

    private final FlakyProbeCalculator calculator = new FlakyProbeCalculator();

    @Test
    void proposesRestoreWhenStreakReachesThreshold() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(GREEN), 2, false, 3);

        assertEquals(3, decision.greenStreak());
        assertTrue(decision.proposeRestore(),
                "third consecutive green run reaches the threshold of 3");
        assertFalse(decision.withdrewProposal());
        assertEquals(1, decision.terminalCount());
    }

    @Test
    void failureResetsTheStreakAndProposesNothing() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(RED, GREEN), 4, false, 5);

        assertEquals(1, decision.greenStreak(), "the failure in between restarts the streak");
        assertFalse(decision.proposeRestore());
        assertEquals(2, decision.terminalCount());
    }

    @Test
    void timeoutCountsAsFailureSide() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(GREEN, TIMEOUT, GREEN), 3, false, 3);

        assertEquals(1, decision.greenStreak());
        assertFalse(decision.proposeRestore());
    }

    @Test
    void alreadyProposedSessionStaysQuietOnFurtherGreenRuns() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(GREEN), 3, true, 3);

        assertEquals(4, decision.greenStreak());
        assertFalse(decision.proposeRestore(),
                "one proposal per threshold crossing - no duplicate confirmation card");
        assertFalse(decision.withdrewProposal());
    }

    @Test
    void failureAfterPendingProposalWithdrawsIt() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(RED), 5, true, 3);

        assertEquals(0, decision.greenStreak());
        assertFalse(decision.proposeRestore());
        assertTrue(decision.withdrewProposal(),
                "the case proved unstable again, so the pending restore proposal must not linger");
    }

    @Test
    void thresholdIsAnArgumentNotAConstant() {
        // Same held streak (1) and same new outcome (one green) => final streak 2.
        FlakyProbeDecision strict = calculator.evaluate(List.of(GREEN), 1, false, 3);
        FlakyProbeDecision relaxed = calculator.evaluate(List.of(GREEN), 1, false, 2);

        assertFalse(strict.proposeRestore(), "streak 2 has not reached the configured threshold 3");
        assertTrue(relaxed.proposeRestore(), "the very same outcome proposes when the key allows 2");
    }

    @Test
    void nonTerminalStatusesAreIgnored() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(GREEN, RUNNING, GREEN), 0, false, 2);

        assertEquals(2, decision.greenStreak());
        assertEquals(2, decision.terminalCount(), "the running task is not an outcome yet");
        assertTrue(decision.proposeRestore());
    }

    @Test
    void emptyWindowChangesNothing() {
        FlakyProbeDecision decision = calculator.evaluate(List.of(), 2, false, 3);

        assertEquals(2, decision.greenStreak());
        assertEquals(0, decision.terminalCount());
        assertFalse(decision.proposeRestore());
        assertFalse(decision.withdrewProposal());
    }
}
