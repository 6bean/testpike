package com.sixbean.testpike.common.log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Fingerprint collapse window: the repeat-suppression mechanism single-sourced
 * out of the forwarding guard (R40 — the dead-letter and LLM degradation
 * families are the second and third consumers, no per-service copies).
 *
 * <p>Contract (identical to the forwarding guard it now backs):
 * the same fingerprint inside the window emits exactly one line (the window-open
 * line carries full detail); the first hit after the window elapses emits the
 * live line plus the suppressed count and the caller's ledger-pointer note, so
 * collapsing never silently swallows. Distinct fingerprints never suppress each
 * other. A non-positive resolved window disables collapsing entirely. Past the
 * tracked-fingerprint cap a new fingerprint fails open (printed, untracked).
 *
 * <p>Window resolution order: constructor-fixed value (tests), else the system
 * property named by {@code windowProperty} re-read on every check (hot-tunable),
 * else the constructor default.
 */
public class LogCollapseWindow {

    /** Cap on tracked fingerprints: a buggy caller spraying unique fingerprints
     *  must not grow the map without bound — collapsing must never swallow by
     *  exhaustion, so past the cap new lines are printed and left untracked. */
    public static final int MAX_TRACKED_FINGERPRINTS = 256;

    /** Fallback window when the property is unset: 60 seconds. */
    private static final long DEFAULT_WINDOW_MILLIS = 60_000L;

    /** Fingerprint normalization cap: bounds map keys against pathological payloads. */
    private static final int NORMALIZE_MAX_CHARS = 200;

    private final String windowProperty;
    private final long defaultWindowMillis;
    private final LongSupplier nowMillis;
    private final long fixedWindowMillis;
    private final Map<String, LogCollapseWindowState> windows = new ConcurrentHashMap<>();

    /** Production constructor: wall clock, window from the hot-tunable property. */
    public LogCollapseWindow(String windowProperty) {
        this(windowProperty, DEFAULT_WINDOW_MILLIS, System::currentTimeMillis, 0L);
    }

    /** Test constructor: {@code fixedWindowMillis > 0} pins the window; otherwise it
     *  resolves from {@code windowProperty} at every check. */
    public LogCollapseWindow(String windowProperty, LongSupplier nowMillis, long fixedWindowMillis) {
        this(windowProperty, DEFAULT_WINDOW_MILLIS, nowMillis, fixedWindowMillis);
    }

    public LogCollapseWindow(String windowProperty, long defaultWindowMillis,
                             LongSupplier nowMillis, long fixedWindowMillis) {
        this.windowProperty = windowProperty;
        this.defaultWindowMillis = defaultWindowMillis;
        this.nowMillis = nowMillis;
        this.fixedWindowMillis = fixedWindowMillis;
    }

    /** Hot-tunable system property key backing this window (surfaced for tests/ops). */
    public String getWindowProperty() {
        return windowProperty;
    }

    /**
     * Decide whether this fingerprint occurrence prints now.
     *
     * @param fingerprint  caller-computed identity of the log family (see {@link #normalize})
     * @param line         the classified single-line message
     * @param rolloverNote ledger-pointer wording appended to the rollover line
     *                     (where the authoritative record of the suppressed detail lives)
     * @return the line to log — window-open line, rollover line carrying the count,
     *         or {@code null} when collapsed into the currently open window
     */
    public String admit(String fingerprint, String line, String rolloverNote) {
        long window = resolveWindowMillis();
        if (window <= 0) {
            return line;
        }
        long now = nowMillis.getAsLong();
        AtomicReference<String> toPrint = new AtomicReference<>();
        windows.compute(fingerprint, (key, state) -> {
            if (state == null) {
                if (windows.size() >= MAX_TRACKED_FINGERPRINTS) {
                    toPrint.set(line);   // fail open: print, do not track
                    return null;
                }
                toPrint.set(line);       // window opens
                return new LogCollapseWindowState(now + window);
            }
            if (now >= state.getExpiresAt()) {
                toPrint.set(line + "（" + window / 1000 + "s 窗内折叠 " + state.getSuppressed()
                        + " 条同指纹重复，" + rolloverNote + "）");
                state.reopen(now + window);
                return state;
            }
            state.suppressOne();
            toPrint.set(null);           // collapsed into the open window
            return state;
        });
        return toPrint.get();
    }

    /**
     * Fingerprint normalization: mask every run of digits to a single {@code N},
     * squeeze whitespace runs and cap the length — the same convention as
     * tools/log_noise_ledger.py signature normalization (NUM = {@code \d+}).
     * Timestamps/ids/per-attempt counters must not split one logical repeat
     * family into unique fingerprints, which would defeat collapsing.
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String masked = text.replaceAll("\\d+", "N").replaceAll("\\s+", " ").trim();
        return masked.length() <= NORMALIZE_MAX_CHARS
                ? masked
                : masked.substring(0, NORMALIZE_MAX_CHARS);
    }

    private long resolveWindowMillis() {
        if (fixedWindowMillis > 0) {
            return fixedWindowMillis;
        }
        return Long.getLong(windowProperty, defaultWindowMillis);
    }
}
