package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageOrphanEdge;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.api.dto.OrphanEdgeScanReport;
import com.sixbean.testpike.core.linkage.domain.component.LinkageObjectTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orphan-edge scan contract test against the real slot database, no mocks
 * . The scan answers one question with
 * data: for every foreign key that references a mutable object table whose
 * member is armed in the ledger, is that referencing table covered by a
 * registered edge (active or exempt)? Bidirectional self-test is the point:
 * a planted foreign key without an edge MUST surface, and the same foreign
 * key MUST disappear once a covering edge is registered and return when that
 * edge is disabled — a gate that never turns either way is a silent no-op.
 * Fixtures are own rows and own tables only (run-suffixed probe table
 * dropped in teardown, edge rows deleted by their unique keys; never a
 * whole-table wipe).
 */
@SpringBootTest(classes = CoreApplication.class)
class OrphanEdgeScanServiceTest {

    @Autowired
    private OrphanEdgeScanService orphanEdgeScanService;

    @Autowired
    private ChangeLinkageEdgeService changeLinkageEdgeService;

    @Autowired
    private NamedParameterJdbcTemplate named;

    /** Run-owned scratch table; dropped in teardown, never left behind. */
    private final String probeTable =
            com.sixbean.testpike.core.linkage.LinkageLedgerFixtures.newProbeTableName("tp_orphan_fk_probe_");

    private final List<String> ownedEdgeKeys = new ArrayList<>();

    @AfterEach
    void removeOwnedFixtures() {
        com.sixbean.testpike.core.linkage.LinkageLedgerFixtures
                .removeOwnedEdgeRows(named, ownedEdgeKeys);
        com.sixbean.testpike.core.linkage.LinkageLedgerFixtures.dropProbeTable(named, probeTable);
    }

    private String edgeKey(String upstream, String downstream) {
        String key = upstream + "->" + downstream + ":t" + System.nanoTime();
        ownedEdgeKeys.add(key);
        return key;
    }

    private String probeDownstreamToken() {
        return probeTable.substring("tp_".length());
    }

    private void armRequirementMember() {
        com.sixbean.testpike.core.linkage.LinkageLedgerFixtures
                .registerRequirementEdge(changeLinkageEdgeService, ownedEdgeKeys);
    }

    private void createProbeForeignKey() {
        com.sixbean.testpike.core.linkage.LinkageLedgerFixtures
                .createRequirementForeignKey(named, probeTable);
    }

    private List<LinkageOrphanEdge> orphansForProbe(OrphanEdgeScanReport report) {
        return report.orphans().stream()
                .filter(o -> probeTable.equals(o.referencingTable()))
                .toList();
    }

    @Test
    void everyMappedTableResolvesToALiveTable() {
        for (String table : LinkageObjectTables.tableNames()) {
            Integer found = named.queryForObject(
                    "SELECT count(*) FROM pg_class WHERE relname = :t AND relkind = 'r'",
                    new MapSqlParameterSource("t", table), Integer.class);
            assertTrue(found != null && found > 0,
                    "linkage table map names a table that is not in the live database: " + table);
        }
    }

    @Test
    void plantedForeignKeyWithoutEdgeIsReportedOrphan() {
        armRequirementMember();
        createProbeForeignKey();

        List<LinkageOrphanEdge> orphans = orphansForProbe(orphanEdgeScanService.scan());

        assertEquals(1, orphans.size(),
                "a new foreign key onto an armed member must surface as an orphan edge");
        LinkageOrphanEdge orphan = orphans.get(0);
        assertEquals("requirement", orphan.upstreamObjectType());
        assertEquals("tp_requirement", orphan.referencedTable());
        assertTrue(orphan.fkColumn() != null && !orphan.fkColumn().isBlank());
    }

    @Test
    void registeredEdgeCoveringTheForeignKeyClearsTheOrphan() {
        armRequirementMember();
        createProbeForeignKey();
        changeLinkageEdgeService.register(new ChangeLinkageEdgeInput(
                edgeKey("requirement", probeDownstreamToken()), "requirement",
                probeDownstreamToken(), 2,
                "SELECT id FROM " + probeTable + " WHERE req_id = :objectId", null,
                "mark_only", 1, 0, null, "probe covering edge"));

        assertTrue(orphansForProbe(orphanEdgeScanService.scan()).isEmpty(),
                "a registered covering edge must clear the probe foreign key");
    }

    @Test
    void disablingTheCoveringEdgeLetsTheOrphanReturn() {
        armRequirementMember();
        createProbeForeignKey();
        String coverKey = edgeKey("requirement", probeDownstreamToken());
        changeLinkageEdgeService.register(new ChangeLinkageEdgeInput(
                coverKey, "requirement", probeDownstreamToken(), 2,
                "SELECT id FROM " + probeTable + " WHERE req_id = :objectId", null,
                "mark_only", 1, 0, null, "probe covering edge"));
        assertTrue(orphansForProbe(orphanEdgeScanService.scan()).isEmpty(),
                "covering edge in place: nothing of the probe's must be reported");

        changeLinkageEdgeService.disable(coverKey);

        assertEquals(1, orphansForProbe(orphanEdgeScanService.scan()).size(),
                "disabling the covering edge must resurface the orphan (rot guard reads live state)");
    }

    @Test
    void exemptEdgeCountsAsCovered() {
        armRequirementMember();
        createProbeForeignKey();
        changeLinkageEdgeService.register(new ChangeLinkageEdgeInput(
                edgeKey("requirement", probeDownstreamToken()), "requirement",
                probeDownstreamToken(), 3, null, null, "exempt", 1, 1,
                "probe table is a structural child and does not re-check upstream edits",
                "registered exemption"));

        assertTrue(orphansForProbe(orphanEdgeScanService.scan()).isEmpty(),
                "a registered exemption must count as coverage, not red");
    }

    @Test
    void edgeOnUnknownBusMemberSurfacesAsDeadEdge() {
        String key = edgeKey("ghost_type", "plan_case");
        changeLinkageEdgeService.register(new ChangeLinkageEdgeInput(
                key, "ghost_type", "plan_case", 2, "SELECT 1", null,
                "mark_only", 1, 0, null, "probe dead edge"));

        OrphanEdgeScanReport report = orphanEdgeScanService.scan();

        assertTrue(report.deadEdges().contains(key),
                "an edge whose upstream is not a bus member can never be armed and must surface");
        assertFalse(report.armedMembers().contains("ghost_type"),
                "a dead upstream must not arm anything");
    }

    /**
     * The edge-coverage census must agree with the ledger for every bus
     * member: a token with no enabled edge naming it upstream is surfaced as
     * still needing coverage, and a token that has registered edges is absent.
     * Deriving both directions from the ledger instead of pinning one token
     * keeps the contract true as later slices register more fans (a pinned
     * token silently flips when a migration gives it edges, which is exactly
     * what happened to the original {@code case} assertion once the case fan
     * landed) while staying bidirectional against live data.
     */
    @Test
    void busMembersWithoutEdgesMatchTheLedgerCensus() {
        OrphanEdgeScanReport report = orphanEdgeScanService.scan();

        for (ObjectChangedEventType member : ObjectChangedEventType.values()) {
            String token = member.objectType();
            boolean hasEdge = Boolean.TRUE.equals(named.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM tp_change_linkage"
                            + " WHERE enabled = 1 AND upstream_object_type = :token)",
                    new MapSqlParameterSource("token", token), Boolean.class));
            if (hasEdge) {
                assertFalse(report.busMembersWithoutEdges().contains(token),
                        token + " has registered edges upstream, so it must not be"
                                + " listed as a member still needing coverage");
            } else {
                assertTrue(report.busMembersWithoutEdges().contains(token),
                        token + " has no enabled edge upstream, so it must surface"
                                + " as a member still needing coverage");
            }
        }
    }

    /**
 * Census closure gate for the second repayment segment : the
 * bus enumeration and the edge ledger must describe the same membership. A
 * token can be covered two legitimate ways — a detector edge, or written
 * exemption rows when its downstream pairs are pinned snapshots whose
 * semantics forbid propagation — but it may not be announced on the bus
 * while no row of the ledger says which way its fan is settled. Deriving
 * the expectation from the enum instead of naming a token keeps this true
 * for members later slices add.
 */
    @Test
    void everyBusMemberIsSettledByAnEdgeOrAnExemption() {
        OrphanEdgeScanReport report = orphanEdgeScanService.scan();

        assertTrue(report.busMembersWithoutEdges().isEmpty(),
                "every bus member must have at least one enabled ledger row naming it"
                        + " upstream (detector or written exemption); unsettled: "
                        + report.busMembersWithoutEdges());
    }

    @Test
    void liveCensusHasNoUncoveredForeignKeys() {
        OrphanEdgeScanReport report = orphanEdgeScanService.scan();

        assertTrue(report.orphans().isEmpty(),
                "the live schema must carry no orphan foreign key onto an armed"
                        + " member; open pairs: " + report.orphans().stream()
                        .map(o -> o.upstreamObjectType() + " -> " + o.referencingTable()
                                + " via " + o.fkName())
                        .toList());
    }
}
