package com.sixbean.testpike.core.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract for the aggregated census summary (R70 noise repair): class-end
 * degradations are counted per reason and reported once at shutdown instead
 * of one stack-frame WARN per class. Two directions are asserted so the
 * aggregation cannot spin empty — recorded skips must surface with exact
 * counts and bounded samples, and an empty session must still print the
 * ran-anchor line with zero (a silent summary would hide a broken guard).
 */
class ExecClaimWindowGuardCensusTest {

    @AfterEach
    void resetState() {
        ExecClaimWindowGuard.resetAggregatesForTests();
    }

    @Test
    void repeatedSkipsAggregateIntoOneSummaryLinePerReason() {
        // The autodetected guard also runs its own beforeAll against this very
        // class (Spring-test boot path may record a skip once), so assertions
        // are delta-based on top of the count observed at method entry.
        long jdbcBefore = ExecClaimWindowGuard.skipCount(ExecClaimWindowGuard.REASON_NO_JDBC_BEAN);
        long springBefore = ExecClaimWindowGuard.skipCount(ExecClaimWindowGuard.REASON_NO_SPRING_CONTEXT);
        for (int i = 0; i < 7; i++) {
            ExecClaimWindowGuard.recordSkip(
                    ExecClaimWindowGuard.REASON_NO_JDBC_BEAN, "LeakySliceTest" + i,
                    "org.springframework.beans.factory.NoSuchBeanDefinitionException: No qualifying bean");
        }
        ExecClaimWindowGuard.recordSkip(
                ExecClaimWindowGuard.REASON_NO_SPRING_CONTEXT, "OrphanTest", "java.lang.IllegalState");

        assertEquals(jdbcBefore + 7L, ExecClaimWindowGuard.skipCount(ExecClaimWindowGuard.REASON_NO_JDBC_BEAN));
        assertEquals(springBefore + 1L, ExecClaimWindowGuard.skipCount(ExecClaimWindowGuard.REASON_NO_SPRING_CONTEXT));
        assertTrue(ExecClaimWindowGuard.sampleOf(ExecClaimWindowGuard.REASON_NO_JDBC_BEAN).size() <= 5,
                "sample stays bounded");

        String out = captureSummary();
        long jdbcLines = out.lines()
                .filter(line -> line.contains("reason=" + ExecClaimWindowGuard.REASON_NO_JDBC_BEAN))
                .count();
        assertEquals(1L, jdbcLines, "one aggregated line per reason, not one per class");
        assertTrue(out.contains("count=" + (jdbcBefore + 7)), "exact count visible: " + out);
    }

    @Test
    void emptySessionStillPrintsZeroAnchor() {
        long recordedElsewhere = ExecClaimWindowGuard.skipCount(ExecClaimWindowGuard.REASON_NO_JDBC_BEAN)
                + ExecClaimWindowGuard.skipCount(ExecClaimWindowGuard.REASON_NO_SPRING_CONTEXT);
        String out = captureSummary();
        assertTrue(out.contains("[exec-claim-census] summary"), "anchor line always present: " + out);
        assertTrue(out.contains("skippedClasses=" + recordedElsewhere),
                "totals are stated, never hidden: " + out);
    }

    private static String captureSummary() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ExecClaimWindowGuard.printSummary();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
