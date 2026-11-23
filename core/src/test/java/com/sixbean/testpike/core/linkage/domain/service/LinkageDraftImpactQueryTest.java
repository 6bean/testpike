package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.common.linkage.LinkageDraftImpact;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draft-keyed live-anchor read : the grouped card needs
 * the ledger row behind each of its cards read afresh, so the seam answers
 * per draft — a draft with no linkage anchor is honestly absent (no
 * fabricated row), a consumed ledger row still comes back carrying its
 * consumed_at (the card discloses the settled state instead of hiding it),
 * and unknown draft ids simply yield no rows. Run-owned fixture ids only;
 * factory rows are never touched.
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageDraftImpactQueryTest {

    private static final String EDGE_PREFIX = "anchor-t26-";

    @Autowired
    private LinkageImpactQueryService queryService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private final List<Long> impactIds = new ArrayList<>();
    private final List<Long> draftIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        if (!draftIds.isEmpty()) {
            jdbc.update("DELETE FROM tp_ai_chat_draft WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", draftIds));
        }
        if (!impactIds.isEmpty()) {
            jdbc.update("DELETE FROM tp_linkage_impact WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", impactIds));
        }
    }

    private long seedProject(String tag) {
        long id = 976_000_000L + System.nanoTime() % 100_000L;
        jdbc.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE "
                        + "VALUES (:id, :name, :code)",
                new MapSqlParameterSource("id", id)
                        .addValue("name", "anchor fixture " + tag + " " + id)
                        .addValue("code", "ANC" + id));
        return id;
    }

    private long seedImpact(boolean consumed) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type,"
                        + " upstream_object_id, upstream_version, downstream_object_type,"
                        + " downstream_id, disposition_strategy, status, consumed_at)"
                        + " VALUES (:edge, 'case', 500, 2, 'script', 700, 'grouped_draft', 2,"
                        + " CASE WHEN :consumed THEN now() ELSE NULL END) RETURNING id",
                new MapSqlParameterSource("edge", EDGE_PREFIX + System.nanoTime())
                        .addValue("consumed", consumed),
                Long.class);
        assertNotNull(id);
        impactIds.add(id);
        return id;
    }

    private long seedDraft(long projectId, String contextJson) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_ai_chat_draft (session_id, message_id, project_id,"
                        + " object_type, target_id, schema_version, summary, payload_json,"
                        + " status, seq, context_json)"
                        + " VALUES (NULL, NULL, :projectId, 'SCRIPT_REPAIR', :targetId, '1',"
                        + " 'anchor fixture draft', CAST('{}' AS jsonb), 1, 1,"
                        + " CAST(:ctx AS jsonb)) RETURNING id",
                new MapSqlParameterSource("projectId", projectId)
                        .addValue("targetId", 4000L + Math.abs(
                                (contextJson == null ? "unlinked" : contextJson).hashCode() % 1000))
                        .addValue("ctx", contextJson),
                Long.class);
        assertNotNull(id);
        draftIds.add(id);
        return id;
    }

    @Test
    void linkedDrafts_answerTheirLiveAnchorRows() {
        long project = seedProject("linked");
        long live = seedImpact(false);
        long settled = seedImpact(true);
        long liveDraft = seedDraft(project, "{\"linkageImpactId\":\"" + live + "\"}");
        long settledDraft = seedDraft(project, "{\"linkageImpactId\":\"" + settled + "\"}");

        List<LinkageDraftImpact> anchors =
                queryService.findImpactsByDrafts(List.of(liveDraft, settledDraft));

        assertEquals(2, anchors.size(), "every linked draft answers its own anchor");
        LinkageDraftImpact liveRow = anchors.stream()
                .filter(a -> a.draftId().equals(liveDraft)).findFirst().orElseThrow();
        LinkageDraftImpact settledRow = anchors.stream()
                .filter(a -> a.draftId().equals(settledDraft)).findFirst().orElseThrow();
        assertEquals(live, liveRow.impact().id());
        assertEquals(project, liveRow.projectId(),
                "the owner is read from the draft row itself");
        assertNull(liveRow.impact().consumedAt(), "a pending row stays unconsumed");
        assertNotNull(settledRow.impact().consumedAt(),
                "a consumed row discloses its consumed_at instead of vanishing");
        assertEquals("grouped_draft", liveRow.impact().dispositionStrategy());
    }

    @Test
    void unlinkedAndUnknownDrafts_areHonestlyAbsent() {
        long project = seedProject("absent");
        long plainDraft = seedDraft(project, null);

        List<LinkageDraftImpact> anchors =
                queryService.findImpactsByDrafts(List.of(plainDraft, 999_999_999_990L));

        assertTrue(anchors.isEmpty(),
                "a card with no ledger anchor gets no fabricated row");
    }

    @Test
    void emptyIdList_answersEmptyWithoutTouchingTheLedger() {
        assertTrue(queryService.findImpactsByDrafts(List.of()).isEmpty());
        assertTrue(queryService.findImpactsByDrafts(null).isEmpty());
    }
}
