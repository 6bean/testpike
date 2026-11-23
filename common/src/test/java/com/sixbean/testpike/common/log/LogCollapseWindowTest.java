package com.sixbean.testpike.common.log;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fingerprint collapse window : the mechanism single-sourced
 * out of the forwarding guard so every designed-path log family (dead-letter,
 * LLM degradation) shares one implementation (R40). Contract under test:
 * 1. Same fingerprint inside the window emits exactly one line (the window-open
 * line); the first hit after rollover emits the live line plus the suppressed
 * count and the caller's ledger pointer note — collapsing never swallows.
 * 2. Distinct fingerprints never suppress each other.
 * 3. A non-positive resolved window disables collapsing (every line emits).
 * 4. Past the tracked-fingerprint cap a new fingerprint fails open (printed,
 * untracked) — memory pressure must never turn into suppression.
 * 5. normalize masks digits so timestamps/ids cannot defeat the fingerprint.
 */
class LogCollapseWindowTest {

    private static final String NOTE = "明细见死信台账页";
    private static final String TEST_PROPERTY = "tp.unittest-collapse-window-ms";

    private static LogCollapseWindow fixedWindow(AtomicLong clock, long windowMillis) {
        return new LogCollapseWindow(TEST_PROPERTY, clock::get, windowMillis);
    }

    @Test
    void sameFingerprintCollapsesToSingleLineWithRolloverCount() {
        AtomicLong clock = new AtomicLong(1_000L);
        LogCollapseWindow window = fixedWindow(clock, 60_000L);
        assertEquals("first line", window.admit("fp-a", "first line", NOTE));
        assertNull(window.admit("fp-a", "repeat 2", NOTE));
        assertNull(window.admit("fp-a", "repeat 3", NOTE));
        clock.set(61_001L);
        String rollover = window.admit("fp-a", "repeat 4", NOTE);
        assertNotNull(rollover);
        assertTrue(rollover.startsWith("repeat 4"), "rollover keeps the live line");
        assertTrue(rollover.contains("折叠 2 条"), "rollover carries the suppressed count");
        assertTrue(rollover.contains(NOTE), "rollover points at the authoritative ledger");
    }

    @Test
    void distinctFingerprintsNeverSuppressEachOther() {
        AtomicLong clock = new AtomicLong(0L);
        LogCollapseWindow window = fixedWindow(clock, 60_000L);
        assertNotNull(window.admit("fp-a", "line a", NOTE));
        assertNotNull(window.admit("fp-b", "line b", NOTE));
        assertNull(window.admit("fp-a", "line a2", NOTE));
        assertNull(window.admit("fp-b", "line b2", NOTE));
    }

    @Test
    void nonPositiveResolvedWindowDisablesCollapsing() {
        AtomicLong clock = new AtomicLong(0L);
        // fixed <= 0 resolves from the system property on every check (hot-tunable).
        LogCollapseWindow window = new LogCollapseWindow(TEST_PROPERTY, clock::get, 0L);
        System.setProperty(TEST_PROPERTY, "0");
        try {
            assertNotNull(window.admit("fp-a", "line 1", NOTE));
            assertNotNull(window.admit("fp-a", "line 2", NOTE));
        } finally {
            System.clearProperty(TEST_PROPERTY);
        }
    }

    @Test
    void newFingerprintFailsOpenPastTheCap() {
        AtomicLong clock = new AtomicLong(0L);
        LogCollapseWindow window = fixedWindow(clock, 60_000L);
        for (int i = 0; i < LogCollapseWindow.MAX_TRACKED_FINGERPRINTS; i++) {
            assertNotNull(window.admit("cap-fp-" + i, "line " + i, NOTE));
        }
        assertNotNull(window.admit("cap-fp-overflow", "still printed", NOTE),
                "cap overflow must print, never swallow");
    }

    @Test
    void normalizeMasksDigitsSoTimestampsCannotSplitFingerprints() {
        String one = LogCollapseWindow.normalize("第1次(07:31:02) 模型调用超时 12000ms");
        String two = LogCollapseWindow.normalize("第2次(07:44:59) 模型调用超时 500ms");
        assertEquals(one, two, "digit-masked templates must collide");
        assertTrue(one.contains("第N次"), "digits become N");
    }

    @Test
    void windowSecondsInRolloverNoteComeFromResolvedWindow() {
        AtomicLong clock = new AtomicLong(0L);
        LogCollapseWindow window = fixedWindow(clock, 30_000L);
        assertNotNull(window.admit("fp-a", "open", NOTE));
        clock.set(30_001L);
        assertTrue(window.admit("fp-a", "again", NOTE).contains("30s 窗内"),
                "rollover names the window length for operators");
    }
}
