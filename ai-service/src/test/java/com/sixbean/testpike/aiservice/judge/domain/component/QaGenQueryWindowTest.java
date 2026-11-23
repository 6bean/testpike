package com.sixbean.testpike.aiservice.judge.domain.component;

import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stall verdict of the question-generation query window .
 * <p>The job-plane design keeps the server-side row as the single authority
 * for a run's fate (the wizard polls until the row leaves "running"). That
 * holds only while the worker thread is alive: after an ai-service restart
 * or a dead thread nobody ever writes the RUNNING row again, so the poller
 * would spin forever. These tests pin the read-side convergence: a running
 * row silent for more than twice the forward-read window answers "failed"
 * with the idle minutes named and the already-produced items kept, while
 * every other shape of row passes through untouched.
 */
class QaGenQueryWindowTest {

    private static final long DOCUMENT_ID = 8639L;

    /** Factory default window, same shipped value as the admin forward leg. */
    private static QaGenQueryWindow defaultWindow() {
        return new QaGenQueryWindow(1800);
    }

    private static QaGenJobView runningRow(List<Map<String, Object>> items) {
        return new QaGenJobView(DOCUMENT_ID, "tok-1", QaGenJobView.STATUS_RUNNING,
                3, items.isEmpty() ? 0 : 1, items, 100, 50, "glm-4-plus", null);
    }

    private static Timestamp secondsAgo(long seconds) {
        return Timestamp.from(Instant.now().minus(seconds, ChronoUnit.SECONDS));
    }

    /** A running row idle past the stall bound converges to failed: the
     *  cause names the silent minutes, produced items survive, and the
     *  guidance tells the user to re-submit. */
    @Test
    void staleRunningRow_convergesToFailedAndKeepsProducedItems() {
        List<Map<String, Object>> items = List.of(Map.of("question", "q1"));
        QaGenJobView converged = defaultWindow().converge(runningRow(items), secondsAgo(7200));

        assertNotSame(runningRow(items), converged);
        assertEquals(QaGenJobView.STATUS_FAILED, converged.status(),
                "a running row idle past the stall bound must answer failed");
        assertTrue(converged.errorMsg() != null && converged.errorMsg().contains("120"),
                "the cause must name the silent minutes, got: " + converged.errorMsg());
        assertEquals(items, converged.items(),
                "already produced questions are never dropped by the verdict");
        assertTrue(converged.errorMsg().contains("重新发起"),
                "the cause must point at the recovery entry, got: " + converged.errorMsg());
        // Non-stalled faces of the same row keep passing through untouched.
        assertEquals(items, converged.items());
        assertEquals(DOCUMENT_ID, converged.documentId());
        assertEquals("tok-1", converged.runToken());
    }

    /** A running row still inside the window is a live run: pass-through. */
    @Test
    void runningRowWithinWindow_passesThroughUntouched() {
        QaGenJobView row = runningRow(List.of());
        assertSame(row, defaultWindow().converge(row, secondsAgo(600)),
                "a fresh running row must be returned as-is, verdict never fires");
    }

    /** Terminal rows are final: done and failed never get rewritten no
     *  matter how old they are. */
    @Test
    void terminalRows_areNeverRewritten() {
        QaGenJobView done = new QaGenJobView(DOCUMENT_ID, "tok-1", QaGenJobView.STATUS_DONE,
                3, 3, List.of(Map.of("question", "q")), 300, 150, "glm-4-plus", null);
        assertSame(done, defaultWindow().converge(done, secondsAgo(86400)));

        QaGenJobView failed = new QaGenJobView(DOCUMENT_ID, "tok-1", QaGenJobView.STATUS_FAILED,
                3, 1, List.of(), 100, 50, "glm-4-plus", "真实失败原因");
        QaGenJobView kept = defaultWindow().converge(failed, secondsAgo(86400));
        assertSame(failed, kept, "an honest failure cause must never be overwritten");
        assertEquals("真实失败原因", kept.errorMsg());
    }

    /** Boundary of the 2x-window rule at the shipped 1800s window: 3599s of
     *  silence is still a slow-but-alive run, 3601s is stalled. */
    @Test
    void stallBoundary_isStrictlyGreaterThanTwiceTheWindow() {
        QaGenQueryWindow window = defaultWindow();
        assertSame(window, window, "boundary case drives the shipped 1800s window");

        QaGenJobView justUnder = window.converge(runningRow(List.of()), secondsAgo(3599));
        assertEquals(QaGenJobView.STATUS_RUNNING, justUnder.status(),
                "3599s idle stays running — the bound is strict > 2x window");

        QaGenJobView justOver = window.converge(runningRow(List.of()), secondsAgo(3601));
        assertEquals(QaGenJobView.STATUS_FAILED, justOver.status(),
                "3601s idle converges to failed");
    }

    /** A non-positive configured value falls back to the factory default
     *  1800s: a mistyped "0" must not turn every in-flight run into an
     *  instant failure (fail-closed keep-everything is the wrong shape
     *  here — the shipped floor keeps the 2x-window verdict honest). */
    @Test
    void nonPositiveWindowValue_fallsBackToFactoryDefault() {
        QaGenQueryWindow zero = new QaGenQueryWindow(0);
        assertEquals(1800, zero.windowSeconds(), "0 falls back to the shipped 1800s");

        // With the default restored, the shipped boundary still holds.
        assertEquals(QaGenJobView.STATUS_RUNNING,
                zero.converge(runningRow(List.of()), secondsAgo(600)).status());
        assertEquals(QaGenJobView.STATUS_FAILED,
                zero.converge(runningRow(List.of()), secondsAgo(3601)).status());

        QaGenQueryWindow negative = new QaGenQueryWindow(-5);
        assertEquals(1800, negative.windowSeconds());
    }
}
