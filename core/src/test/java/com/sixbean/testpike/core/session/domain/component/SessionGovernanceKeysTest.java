package com.sixbean.testpike.core.session.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-parse contract for the AV batch K8 governance keys .
 * The guards live as static pure functions on purpose: a garbage or blank
 * hot value must fall back to the factory default instead of poisoning the
 * read path, and the retention key keeps its negative pass-through so the
 * sweeper's refuse branch (never purge everything) stays the single decision
 * point. Bidirectional cases per AV design L2 (factory, legal, illegal).
 */
class SessionGovernanceKeysTest {

    @Test
    void blankAndGarbageFallBackToFactory() {
        assertEquals(10, SessionGovernanceKeys.parseInt("session.capture", null, 10, 1, 240));
        assertEquals(10, SessionGovernanceKeys.parseInt("session.capture", "  ", 10, 1, 240));
        assertEquals(10, SessionGovernanceKeys.parseInt("session.capture", "ten", 10, 1, 240));
        assertEquals(8000, SessionGovernanceKeys.parseInt("session.validate", "-3", 8000, 500, 60000));
    }

    @Test
    void outOfWindowValuesFallBackToFactory() {
        // capture window 1..240 minutes; 0 would mean "a capture session can
        // never finish", so it is refused exactly like garbage.
        assertEquals(10, SessionGovernanceKeys.parseInt("session.capture", "0", 10, 1, 240));
        assertEquals(10, SessionGovernanceKeys.parseInt("session.capture", "241", 10, 1, 240));
        assertEquals(8000, SessionGovernanceKeys.parseInt("session.validate", "100", 8000, 500, 60000));
    }

    @Test
    void legalValuesRideThrough() {
        assertEquals(30, SessionGovernanceKeys.parseInt("session.capture", "30", 10, 1, 240));
        assertEquals(15000, SessionGovernanceKeys.parseInt("session.validate", "15000", 8000, 500, 60000));
        assertEquals(0, SessionGovernanceKeys.parseInt("session.renew", "0", 15, 0, 1440));
        assertEquals(1440, SessionGovernanceKeys.parseInt("session.renew", "1440", 15, 0, 1440));
    }

    @Test
    void retentionDaysPassesNonPositiveThroughForTheRefuseBranch() {
        // Only unparsable input reaches the factory 30 here; explicit 0/-1
        // ride through so LoginStateRetentionSweeper refuses the sweep.
        assertEquals(30, SessionGovernanceKeys.parseRetentionDays("garbage"));
        assertEquals(30, SessionGovernanceKeys.parseRetentionDays(null));
        assertEquals(45, SessionGovernanceKeys.parseRetentionDays(" 45 "));
        assertEquals(0, SessionGovernanceKeys.parseRetentionDays("0"));
        assertEquals(-1, SessionGovernanceKeys.parseRetentionDays("-1"));
    }

    @Test
    void headlessShellSwitchReadsOnlyExplicitOn() {
        assertTrue(SessionGovernanceKeys.parseEnabled("1"));
        assertTrue(SessionGovernanceKeys.parseEnabled("true"));
        assertFalse(SessionGovernanceKeys.parseEnabled("0"));
        assertFalse(SessionGovernanceKeys.parseEnabled("off"));
        assertFalse(SessionGovernanceKeys.parseEnabled(null));
    }
}
