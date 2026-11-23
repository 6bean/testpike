package com.sixbean.testpike.common.config;

import com.sixbean.testpike.common.log.LogCollapseWindow;

import java.util.function.LongSupplier;

/**
 * Collapses repeated downstream-forwarding failure log lines .
 *
 * <p>Root cause on record: while core was half-dead, the executor protocol relay on api
 * (register/claim/heartbeat every ~2s) produced 309 identical full-stack ERROR dumps in a
 * single log window — the stack repeated per request and buried every other signal.
 *
 * <p>This class keeps the forwarding vocabulary and its hot-tunable property key; the
 * window mechanism itself is single-sourced in {@link LogCollapseWindow} (R40,
 * because the dead-letter and LLM degradation families are the same shape of
 * storm. Behaviour is unchanged: same fingerprint inside the window emits exactly one
 * line (the window-open line); the first hit after the window elapses emits a rollover
 * line carrying the count of suppressed repeats, so nothing is silently swallowed.
 * Distinct downstream targets are distinct fingerprints and never suppress each other.
 *
 * <p>Window resolution order: constructor-fixed value (tests), else system property
 * {@code tp.log.forwarding-collapse-window-ms} re-read on every check (hot-tunable, default
 * 60000). A non-positive resolved window disables collapsing entirely (every line emits).
 */
public class DownstreamForwardingGuard {

    /** System property key for the collapse window (hot-tunable, default 60s). */
    public static final String WINDOW_PROPERTY = "tp.log.forwarding-collapse-window-ms";

    /** Rollover wording, unchanged since (byte-identical log line contract). */
    private static final String ROLLOVER_NOTE = "后续同指纹继续折叠";

    private final LogCollapseWindow window;

    /** Production constructor: wall clock, window from the hot-tunable property. */
    public DownstreamForwardingGuard() {
        this(System::currentTimeMillis, 0L);
    }

    /**
     * @param nowMillis          clock source (injectable for tests)
     * @param fixedWindowMillis  fixed window in ms; {@code <= 0} resolves from
     *                           {@link #WINDOW_PROPERTY} at each check
     */
    public DownstreamForwardingGuard(LongSupplier nowMillis, long fixedWindowMillis) {
        this.window = new LogCollapseWindow(WINDOW_PROPERTY, nowMillis, fixedWindowMillis);
    }

    /**
     * Decide whether this fingerprint occurrence should be printed now.
     *
     * @param fingerprint exception class + target + root-cause class
     * @param line        the classified single-line message
     * @return line to log — window-open line, rollover line with the suppressed count,
     *         or {@code null} when collapsed into the currently open window
     */
    public String admit(String fingerprint, String line) {
        return window.admit(fingerprint, line, ROLLOVER_NOTE);
    }
}
