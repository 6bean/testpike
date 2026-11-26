package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.common.linkage.LinkagePendingImpacts;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pending-impact ledger query contract (, the read seam behind the
 * user-facing grouped-card panel): pending = never consumed; consumed rows
 * are history and must vanish from the display seam without touching the
 * ledger itself. TC-LNK-07 is asserted at the same seam — a save with zero
 * downstream intersections leaves an empty account and no draft card, silent
 * by construction rather than filtered by the caller. Run-owned edge keys
 * only; nothing here rewrites factory rows.
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageImpactQueryTest {

    private static final String UPSERT = """
            INSERT INTO tp_linkage_impact (edge_key, upstream_object_type, upstream_object_id,
                    upstream_version, downstream_object_type, downstream_id,
                    disposition_strategy, status, consumed_at)
            VALUES (:edgeKey, :upstreamType, :upstreamId, :version, :downstreamType, :downstreamId,
                    :strategy, :status, :consumedAt)
            """;

    @Autowired
    private LinkageImpactQueryService queryService;

    @Autowired
    private ChangeLinkageDispatchService dispatchService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final List<String> ownedEdgeKeys = new ArrayList<>();
    private final List<Long> ownedFixtureCaseIds = new ArrayList<>();
    private final List<Long> ownedFixtureRequirementIds = new ArrayList<>();
    private final List<Long> ownedFixtureProjectIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!ownedEdgeKeys.isEmpty()) {
            jdbc.update("DELETE FROM tp_linkage_impact WHERE edge_key IN (:keys)",
                    new MapSqlParameterSource("keys", ownedEdgeKeys));
        }
        if (!ownedFixtureCaseIds.isEmpty()) {
            jdbc.update("DELETE FROM tp_test_case WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ownedFixtureCaseIds));
        }
        if (!ownedFixtureRequirementIds.isEmpty()) {
            jdbc.update("DELETE FROM tp_requirement WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ownedFixtureRequirementIds));
        }
        if (!ownedFixtureProjectIds.isEmpty()) {
            // FK-tree sweep (single source, R40): env rows and any other
            // children the perf-domain max(id) mis-anchoring may have hung
            // under these synthetic projects go down child-first, so the
            // project delete never trips a foreign-key violation.
            SharedDbFixtureSweep.byProject(jdbc.getJdbcTemplate(),
                    ownedFixtureProjectIds.stream().mapToLong(Long::longValue).toArray());
            ownedFixtureProjectIds.clear();
        }
    }

    /** A run-owned project (synthetic high ids; factory rows are never touched). */
    private long seedProject() {
        long id = 975_000_000L + System.nanoTime() % 100_000L;
        ownedFixtureProjectIds.add(id);
        jdbc.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE "
                        + "VALUES (:id, :name, :code)",
                new MapSqlParameterSource("id", id)
                        .addValue("name", "联动归属夹具项目" + id)
                        .addValue("code", "LNKQ" + id));
        return id;
    }

    private long seedCase(long projectId) {
        long id = 976_000_000L + System.nanoTime() % 100_000L;
        ownedFixtureCaseIds.add(id);
        jdbc.update("INSERT INTO tp_test_case (id, case_no, project_id, title) "
                        + "OVERRIDING SYSTEM VALUE VALUES (:id, :no, :pid, '联动归属夹具用例')",
                new MapSqlParameterSource("id", id)
                        .addValue("no", "TC-LNKQ-" + id)
                        .addValue("pid", projectId));
        return id;
    }

    private long seedRequirement(long projectId) {
        long id = 977_000_000L + System.nanoTime() % 100_000L;
        ownedFixtureRequirementIds.add(id);
        jdbc.update("INSERT INTO tp_requirement (id, project_id, source, title) "
                        + "OVERRIDING SYSTEM VALUE VALUES (:id, :pid, 3, '联动归属夹具需求')",
                new MapSqlParameterSource("id", id).addValue("pid", projectId));
        return id;
    }

    private long insertImpact(String edgeSuffix, long upstreamId, int version,
                              String downstreamType, long downstreamId,
                              String strategy, int status, boolean consumed) {
        return insertImpact("case", edgeSuffix, upstreamId, version, downstreamType,
                downstreamId, strategy, status, consumed);
    }

    private long insertImpact(String upstreamType, String edgeSuffix, long upstreamId, int version,
                              String downstreamType, long downstreamId,
                              String strategy, int status, boolean consumed) {
        String edgeKey = "t496q." + System.nanoTime() + "." + edgeSuffix;
        ownedEdgeKeys.add(edgeKey);
        jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("edgeKey", edgeKey)
                .addValue("upstreamType", upstreamType)
                .addValue("upstreamId", upstreamId)
                .addValue("version", version)
                .addValue("downstreamType", downstreamType)
                .addValue("downstreamId", downstreamId)
                .addValue("strategy", strategy)
                .addValue("status", status)
                .addValue("consumedAt", consumed ? new java.sql.Timestamp(System.currentTimeMillis()) : null));
        return upstreamId;
    }

    @Test
    void pendingSeamReturnsUnconsumedRowsInStableOrderWithFullAnchors() {
        long upstreamId = insertImpact("p", 890_000_001L, 3, "script", 770_000_001L,
                "grouped_draft", 2, false);
        insertImpact("x", upstreamId, 3, "script", 770_000_002L,
                "disclose_only", 3, false);
        // consumed history: invisible to the display seam
        insertImpact("c", upstreamId, 3, "script", 770_000_003L,
                "mark_only", 4, true);
        // another upstream object: never leaks into this anchor's page
        insertImpact("o", 890_000_999L, 1, "script", 770_000_004L,
                "grouped_draft", 2, false);

        List<LinkageImpactInfo> pending =
                queryService.findPendingByUpstream("case", upstreamId);

        assertEquals(2, pending.size());
        LinkageImpactInfo first = pending.get(0);
        assertEquals("case", first.upstreamObjectType());
        assertEquals(upstreamId, first.upstreamObjectId());
        assertEquals(3, first.upstreamVersion());
        assertEquals("grouped_draft", first.dispositionStrategy());
        assertEquals(2, first.status());
        assertNull(first.consumedAt());
        assertTrue(first.annotatedAt() != null && !first.edgeKey().isBlank());
        // stable oldest-first order across reloads keeps the card sequence fixed
        assertTrue(first.id() < pending.get(1).id());
        assertEquals("disclose_only", pending.get(1).dispositionStrategy());
    }

    @Test
    void zeroImpactSaveAccountsSilentlyAndMintsNoCard() {
        long syntheticCaseId = 990_000_001L + new java.util.Random().nextInt(1000);
        var summary = dispatchService.detectDownstream(new ObjectChangedEventMessage(
                ObjectChangedEventType.CASE.name(), syntheticCaseId, null, null,
                1, List.of(), System.currentTimeMillis()));

        // detection ledger stays emitted, with a zero finding recorded
        assertTrue(summary.downstreamFound() == 0 && summary.impactsCreated() == 0,
                "a save touching nothing downstream must still report its pass account");
        assertTrue(queryService.findPendingByUpstream("case", syntheticCaseId).isEmpty());

        Integer cards = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_draft d "
                        + "JOIN tp_linkage_impact i "
                        + "  ON d.context_json->>'linkageImpactId' = CAST(i.id AS TEXT) "
                        + "WHERE i.upstream_object_type = 'case' AND i.upstream_object_id = :upstreamId",
                new MapSqlParameterSource("upstreamId", syntheticCaseId), Integer.class);
        assertEquals(0, cards, "zero impact must disturb the user with nothing");
    }

    // ---- ownership anchor for the admin permission bridge ----

    @Test
    void pendingViewResolvesOwningProjectThroughTheCaseAnchor() {
        long projectId = seedProject();
        long caseId = seedCase(projectId);
        insertImpact("own", caseId, 2, "script", 771_000_001L, "grouped_draft", 2, false);

        LinkagePendingImpacts view = queryService.findPendingViewByUpstream("case", caseId);

        assertEquals(projectId, view.projectId(),
                "the ledger row carries no project column — ownership must be read "
                        + "from the upstream object itself, never from the caller");
        assertEquals(1, view.impacts().size());
        assertEquals(771_000_001L, view.impacts().get(0).downstreamId());
    }

    @Test
    void pendingViewResolvesOwningProjectThroughTheRequirementAnchor() {
        long projectId = seedProject();
        long requirementId = seedRequirement(projectId);
        insertImpact("requirement", "req", requirementId, 4, "case", 771_000_002L, "mark_only", 1, false);

        LinkagePendingImpacts view = queryService.findPendingViewByUpstream("requirement", requirementId);

        assertEquals(projectId, view.projectId());
        assertEquals(1, view.impacts().size());
        assertEquals("requirement", view.impacts().get(0).upstreamObjectType());
    }

    @Test
    void pendingViewRefusesLoudlyWhenTheUpstreamObjectNoLongerNamesAProject() {
        long danglingCaseId = 978_000_001L;
        insertImpact("dang", danglingCaseId, 1, "script", 771_000_003L, "grouped_draft", 2, false);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> queryService.findPendingViewByUpstream("case", danglingCaseId));
        assertTrue(refused.getMessage().contains("case"),
                "the refusal must name the upstream kind and id, never fail silently");
        assertTrue(refused.getMessage().contains(String.valueOf(danglingCaseId)));
        assertTrue(refused.getMessage().contains("待处置的未提交改动"),
                "guidance must point at the panel the user is looking at");
        assertTrue(refused.getMessage().contains("可先忽略"),
                "an intentionally deleted upstream has a valid no-op branch");
    }

    @Test
    void pendingViewGuidesHonestlyWhenTheUpstreamKindIsNotWiredIntoLinkage() {
        long ghostId = 978_000_002L;
        insertImpact("ghost-kind", "g", ghostId, 1, "script", 771_000_004L, "grouped_draft", 2, false);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> queryService.findPendingViewByUpstream("ghost-kind", ghostId));
        assertTrue(refused.getMessage().contains("ghost-kind"),
                "the refusal must name the upstream type that lacks a linkage entry");
        assertTrue(refused.getMessage().contains("待处置的未提交改动"),
                "guidance must name the panel where the view was expected");
        assertTrue(refused.getMessage().contains("请联系管理员"),
                "wiring a new upstream type is an admin action; the message must say so");
    }
}
