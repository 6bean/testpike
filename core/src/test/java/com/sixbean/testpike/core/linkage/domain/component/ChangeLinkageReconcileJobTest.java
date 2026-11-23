package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.LinkageLedgerFixtures;
import com.sixbean.testpike.core.linkage.api.dto.LinkageReconcileSummary;
import com.sixbean.testpike.core.linkage.domain.service.ChangeLinkageEdgeService;
import com.sixbean.testpike.core.linkage.domain.service.OrphanEdgeScanService;
import com.sixbean.testpike.core.sched.domain.component.SchedulerJobCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Daily bus-edge reconciliation job contract (AN batch , segment A
 * segment 4). The job exists because a missed {@code changed:{type}} event
 * or a silently drifted schema must not live until the next human audit:
 * every night it consumes the orphan-edge scan and turns it into one
 * findings summary. Tested against the real slot database, no mocks —
 * bidirectional posture (same shape as OrphanEdgeScanServiceTest): a
 * planted orphan foreign key MUST surface in the summary, and the very
 * next run after the probe table is dropped MUST no longer report it.
 * The health-card registration (catalog description) is asserted here
 * because an unregistered job disappears from the scheduler page while
 * still running. Ledger fixtures come from the shared single source
 * {@link LinkageLedgerFixtures} (own rows and run-suffixed tables only).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class ChangeLinkageReconcileJobTest {

    @Autowired
    private ChangeLinkageReconcileJob reconcileJob;

    @Autowired
    private OrphanEdgeScanService orphanEdgeScanService;

    @Autowired
    private ChangeLinkageEdgeService changeLinkageEdgeService;

    @Autowired
    private SchedulerJobCatalog schedulerJobCatalog;

    @Autowired
    private NamedParameterJdbcTemplate named;

    /** Run-owned scratch table; dropped in teardown, never left behind. */
    private final String probeTable =
            LinkageLedgerFixtures.newProbeTableName("tp_lnk_recon_probe_");

    private final List<String> ownedEdgeKeys = new ArrayList<>();

    @AfterEach
    void removeOwnedFixtures() {
        LinkageLedgerFixtures.removeOwnedEdgeRows(named, ownedEdgeKeys);
        LinkageLedgerFixtures.dropProbeTable(named, probeTable);
    }

    private void armRequirementMember() {
        LinkageLedgerFixtures.registerRequirementEdge(changeLinkageEdgeService, ownedEdgeKeys);
    }

    private void createProbeForeignKey() {
        LinkageLedgerFixtures.createRequirementForeignKey(named, probeTable);
    }

    @Test
    void plantedOrphan_fallsIntoSummary_matchingFreshScan() {
        armRequirementMember();
        createProbeForeignKey();

        LinkageReconcileSummary summary = reconcileJob.reconcile();

        assertTrue(summary.armedMembers().contains("requirement"),
                "armed member must ride through from the scan, actual=" + summary.armedMembers());
        assertEquals(1, summary.orphans().stream()
                        .filter(o -> probeTable.equals(o.referencingTable())).count(),
                "planted probe foreign key must appear exactly once in the summary");
        assertTrue(summary.findingsTotal() >= 1,
                "one orphan alone is already a finding (findingsTotal=" + summary.findingsTotal() + ")");
        // The summary is the scan, not a second verdict source: identical findings face.
        assertEquals(orphanEdgeScanService.scan().orphans().size(), summary.orphans().size(),
                "summary orphan count must equal a fresh scan of the same round");
    }

    @Test
    void probeRemoved_nextRun_noLongerReportsIt() {
        armRequirementMember();
        createProbeForeignKey();
        assertTrue(reconcileJob.reconcile().orphans().stream()
                .anyMatch(o -> probeTable.equals(o.referencingTable())),
                "precondition: probe is orphaned on the first run");

        LinkageLedgerFixtures.dropProbeTable(named, probeTable);

        LinkageReconcileSummary second = reconcileJob.reconcile();
        assertFalse(second.orphans().stream()
                        .anyMatch(o -> probeTable.equals(o.referencingTable())),
                "dropped probe must vanish from the next run (anti-no-op bidirectional)");
    }

    @Test
    void jobName_registeredInHumanCatalog() {
        String description = schedulerJobCatalog.descriptions().get("changeLinkageReconcileDaily");
        assertFalse(description == null || description.isBlank(),
                "scheduler without a health-card description disappears from the page "
                        + "while still running; catalog entry is mandatory");
    }
}
