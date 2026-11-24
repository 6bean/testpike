package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.time.Duration;

/**
 * F2 extraction task-level time budget .
 * <p>Why this exists: a worst-case extraction chain (multi-round summarization +
 * provider fallback retries) can silently run for hours when a provider stalls,
 * burning tokens with no result back. The run anchors one deadline at entry
 * (K8 key {@value #CONFIG_KEY}, shipped 7200s) and every LLM call through the
 * fallback chain clamps its per-call timeout to the remaining window; once the
 * window is spent the call fails fast with AI_TIMEOUT so the normal
 * write-back path recovers the document into a plain retry.
 * <p>Fallback discipline: a missing/garbage/non-positive config value keeps the
 * seeded default instead of disabling the guard — the watchdog is never
 * silently off.
 */
public final class ExtractDeadline {

    /** K8 hot-update key (migration V294 ships 7200; core save page edits take effect next run). */
    public static final String CONFIG_KEY = "ai.doc.extract-deadline-seconds";

    /** Seeded default window; also the reader fallback for garbage values. */
    static final int DEFAULT_SECONDS = 7200;

    private final long deadlineEpochMillis;

    /** Time source seam: production = wall clock, suites inject a fake clock (no real sleeps). */
    private final java.util.function.LongSupplier nowMillis;

    public ExtractDeadline(long deadlineEpochMillis) {
        this(deadlineEpochMillis, System::currentTimeMillis);
    }

    /** Same anchor with an injected clock source (DD batch: deterministic hard-stop assertions). */
    public ExtractDeadline(long deadlineEpochMillis, java.util.function.LongSupplier nowMillis) {
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.nowMillis = nowMillis != null ? nowMillis : System::currentTimeMillis;
    }

    /** Same factory on an injected time source (D2 recovery seam: suites drive a
     *  fake clock through the whole run, zero sleeps; the returned instance keeps
     *  the supplier so spent()/clamp()/reanchored() all read one clock). */
    public static ExtractDeadline fromConfig(String rawValue, java.util.function.LongSupplier nowMillis) {
        long seconds = DEFAULT_SECONDS;
        if (rawValue != null && !rawValue.isBlank()) {
            try {
                int parsed = Integer.parseInt(rawValue.trim());
                if (parsed > 0) {
                    seconds = parsed;
                }
            } catch (NumberFormatException ignore) {
                // garbage value falls back to the seeded default, never off
            }
        }
        java.util.function.LongSupplier clock =
                nowMillis != null ? nowMillis : System::currentTimeMillis;
        return new ExtractDeadline(clock.getAsLong() + seconds * 1000L, nowMillis);
    }

    /** Build the deadline from the raw K8 value at {@code nowEpochMillis} (null/blank/garbage/non-positive → shipped default 7200s). */
    public static ExtractDeadline fromConfig(String rawValue, long nowEpochMillis) {
        long seconds = DEFAULT_SECONDS;
        if (rawValue != null && !rawValue.isBlank()) {
            try {
                int parsed = Integer.parseInt(rawValue.trim());
                if (parsed > 0) {
                    seconds = parsed;
                }
            } catch (NumberFormatException ignore) {
                // garbage value falls back to the seeded default, never off
            }
        }
        return new ExtractDeadline(nowEpochMillis + seconds * 1000L);
    }

    /** Whether the run's window is already spent. Budget-classification seam:
 * a spent window turns every further clamp into an instant AI_TIMEOUT, so
 * callers use this to tell "the task ran out of time" apart from "this
 * attempt produced a bad answer", and to stop retry faces that could only
 * spin . */
    public boolean spent() {
        return deadlineEpochMillis - nowMillis.getAsLong() <= 0;
    }

    public long deadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    /**
     * D2 recovery seam: re-anchor the run window to a fresh {@code windowMillis}
     * from "now" on the SAME time source. The reconcile loop hands the returned
     * instance to its provider calls only — the entry-anchored instance stays
     * untouched, so terminal budget attribution and every other deadline
     * consumer keep reading the original window (defect-B honest-attribution
     * semantics: zero drift).
     */
    public ExtractDeadline reanchored(long windowMillis) {
        return new ExtractDeadline(
                nowMillis.getAsLong() + Math.max(windowMillis, 1000L), nowMillis);
    }

    /**
     * Clamp a per-call timeout into the remaining window: window still covers the
     * call → unchanged; shorter remaining → clamp to remaining with a 1s floor
     * (never hand out a zero timeout); window spent → fail fast with a
     * human-readable AI_TIMEOUT so the caller writes the retry hint back.
     */
    public Duration clamp(Duration perCallTimeout) {
        long remainingMillis = deadlineEpochMillis - nowMillis.getAsLong();
        if (remainingMillis <= 0) {
            throw new ServiceException(
                    "需求点提取超出任务时长上限（默认 " + DEFAULT_SECONDS
                            + " 秒，可在系统设置调整），本次调用已中止；再次发起提取即可重试",
                    BizErrorCode.AI_TIMEOUT);
        }
        if (remainingMillis >= perCallTimeout.toMillis()) {
            return perCallTimeout;
        }
        return Duration.ofMillis(Math.max(remainingMillis, 1000L));
    }
}
