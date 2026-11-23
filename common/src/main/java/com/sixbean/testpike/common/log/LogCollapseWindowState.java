package com.sixbean.testpike.common.log;

/**
 * Per-fingerprint collapse state owned by {@link LogCollapseWindow}.
 *
 * <p>Mutated only inside the window's {@code compute} monitor, so the fields stay
 * non-volatile by design: every read/write of expires/suppressed happens while the
 * map holds the key lock. Reopen (instead of replace) keeps suppression counters
 * cheap for hot families that roll over repeatedly.
 */
final class LogCollapseWindowState {

    private long expiresAt;
    private long suppressed;

    LogCollapseWindowState(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    long getExpiresAt() {
        return expiresAt;
    }

    long getSuppressed() {
        return suppressed;
    }

    void suppressOne() {
        suppressed++;
    }

    void reopen(long newExpiresAt) {
        this.expiresAt = newExpiresAt;
        this.suppressed = 0;
    }
}
