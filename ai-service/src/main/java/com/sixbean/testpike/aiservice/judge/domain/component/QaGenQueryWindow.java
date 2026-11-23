package com.sixbean.testpike.aiservice.judge.domain.component;

import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Query window and stall verdict of the question-generation job plane .
 * <p>Why this class exists: the job design keeps the server-side row as the
 * single authority for a run's fate — the wizard polls until the row leaves
 * "running" and never decides on its own. That holds only while the worker
 * thread lives: after an ai-service restart or a killed background thread
 * nobody writes the RUNNING row again, so the poller would spin forever on a
 * dead run. This door converges the read side: a running row whose
 * {@code updated_at} has been silent for strictly more than twice the
 * forward-read window answers "failed" instead, keeping every other face of
 * the row (produced items, token totals, model) untouched.
 * <p>The 2x factor is the slow-but-alive margin: the shipped 1800s window
 * derives from V474 measurements (a single group leg costs up to 600s and
 * retries up to 3 times = exactly one window), so the idle bound must sit
 * strictly above 2x the window to never kill a run that is merely slow.
 * <p>Read-side derivation only — the persisted row keeps its honest
 * "running" until the run's own close-out write lands (same replay-idempotent
 * posture as every other advisory read), and once the thread revives and
 * lands a group, the fresh {@code updated_at} makes the verdict lift itself.
 * The window value is the same install-tunable key as the admin forward leg
 * ({@code ai.judge.qa-gen.forward-read-timeout-seconds}); a non-positive
 * configured value falls back to the shipped 1800s so a mistyped key can
 * neither widen the blast to every live run nor disable the terminal verdict.
 */
@Component
public class QaGenQueryWindow {

    /** Shipped default of the forward-read window in seconds (V474-derived). */
    static final long DEFAULT_WINDOW_SECONDS = 1800L;

    /** A running row must stay silent for strictly more than this many
     *  window lengths before the stall verdict fires. */
    static final int STALL_WINDOW_FACTOR = 2;

    private final long windowSeconds;

    public QaGenQueryWindow(
            @Value("${ai.judge.qa-gen.forward-read-timeout-seconds:1800}")
            long configuredWindowSeconds) {
        this.windowSeconds = configuredWindowSeconds > 0
                ? configuredWindowSeconds
                : DEFAULT_WINDOW_SECONDS;
    }

    /** Window actually in effect after the fail-closed fallback. */
    long windowSeconds() {
        return windowSeconds;
    }

    /**
     * Converge one freshly read job row against its last touch time.
     *
     * @param row         the row as stored (never mutated here)
     * @param lastTouched the row's {@code updated_at}, null when unreadable
     * @return the same instance for done/failed rows, live running rows and
     *         unreadable stamps; a failed verdict row only for a running row
     *         idle past the bound
     */
    public QaGenJobView converge(QaGenJobView row, Timestamp lastTouched) {
        if (row == null || !QaGenJobView.STATUS_RUNNING.equals(row.status())
                || lastTouched == null) {
            return row;
        }
        long idleSeconds = Duration.between(lastTouched.toInstant(), Instant.now()).toSeconds();
        if (idleSeconds <= windowSeconds * STALL_WINDOW_FACTOR) {
            return row;
        }
        String cause = "造题任务疑似中断（已 " + (idleSeconds / 60)
                + " 分钟未写入进度），已按失败收口；已生成的题目保留，可在评测集页「AI 造题」重新发起";
        return new QaGenJobView(row.documentId(), row.runToken(),
                QaGenJobView.STATUS_FAILED, row.totalGroups(), row.doneGroups(),
                row.items(), row.promptTokens(), row.completionTokens(),
                row.model(), cause);
    }

}
