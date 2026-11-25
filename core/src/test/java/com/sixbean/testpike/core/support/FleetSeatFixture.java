package com.sixbean.testpike.core.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Per-suite executor seat window for suites whose verdicts depend on the
 * shared fleet registry (executor routing / task claim gates).
 *
 * <p>Why this exists: on a fresh install (open-source CI) no persistent
 * runner registers itself, so a suite that routes work to an engine-capable
 * executor has no seat to hit and gets rejected with the "online executors
 * exist, capable count is zero" verdict; conversely leftover online rows from
 * other suites in the same JVM flip the empty-queue pass-through semantics
 * into a rejection. Both faces make the verdict depend on database history
 * instead of this suite's own fixtures.
 *
 * <p>The fixture makes each suite self-seeding: it inserts one engine-capable
 * executor under the suite's own name prefix and then quiesces every other
 * online executor for the window, so the routing gate always sees exactly
 * this suite's seat. Teardown restores the quiesced rows first and deletes
 * only rows carrying the suite's prefix — factory seed rows and the live
 * dev runner's registrations are never touched (R37 baseline stays intact).
 *
 * <p>Single-source note: this class only orchestrates the existing
 * {@link SharedDbFixtureSweep#seedCapableExecutor} and
 * {@link FleetViewQuiescer} helpers; suites must consume this window instead
 * of duplicating the seed/quiesce/restore sequence (R40).
 */
public final class FleetSeatFixture {

    private final JdbcTemplate jdbc;
    private final String namePrefix;
    private List<Long> agedExecutorIds;
    private boolean windowOpen;

    private FleetSeatFixture(JdbcTemplate jdbc, String namePrefix) {
        this.jdbc = jdbc;
        this.namePrefix = namePrefix;
    }

    /**
     * Open the seat window: seed this suite's engine-capable executor and
     * drop every other online executor out of the online view until
     * {@link #close()}.
     *
     * @param jdbc             suite's own JdbcTemplate
     * @param namePrefix       suite-exclusive executor name prefix (used for
     *                         keep-pattern matching and teardown sweep)
     * @param engineCapabilities engine descriptors the seat must advertise
     *                         (e.g. "GATLING", "K6")
     */
    public static FleetSeatFixture open(JdbcTemplate jdbc, String namePrefix, String... engineCapabilities) {
        FleetSeatFixture fixture = new FleetSeatFixture(jdbc, namePrefix);
        SharedDbFixtureSweep.seedCapableExecutor(jdbc, namePrefix, engineCapabilities);
        fixture.agedExecutorIds = FleetViewQuiescer.quiesceOthers(jdbc, namePrefix + "%");
        fixture.windowOpen = true;
        return fixture;
    }

    /**
     * Close the window in the paired order (restore before delete) so a
     * crash between the two steps can never leave the fleet view quiesced.
     * Idempotent: safe to call from @AfterEach even when open() never ran.
     */
    public void close() {
        if (!windowOpen) {
            return;
        }
        FleetViewQuiescer.restore(jdbc, agedExecutorIds);
        agedExecutorIds = null;
        SharedDbFixtureSweep.executorsByNamePrefixes(jdbc, namePrefix);
        windowOpen = false;
    }
}
