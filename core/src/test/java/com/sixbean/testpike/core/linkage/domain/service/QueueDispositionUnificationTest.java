package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One disposition seam for both change-analysis queues and the impact
 * ledger .
 * <p>Before this package the api_spec/doc queues and the linkage bus kept
 * two unconnected ledgers: accepting every grouped card left the analysis
 * row pending forever, and disposing on the queue page left the armed
 * impact rows pending forever — the reviewer had to clear the same fact
 * twice (a third account was emerging). These spectra pin the unified
 * seam on the real database with zero mocks:
 * <ul>
 * <li>card consume also settles the matching queue row and names the
 * card in handle_note;</li>
 * <li>queue disposition also settles the armed impacts of the same
 * upstream save;</li>
 * <li>each entry still refuses a second disposition (guards preserved),
 * and the ledger freezes its first stamp on replay (no phantom
 * re-consume facts).</li>
 * </ul>
 * Fixtures are run-owned nano-suffixed rows deleted by id; both analysis
 * rows are seeded directly so the queue services' own handle path is the
 * thing under test, not their analyze producer.
 */
@SpringBootTest(classes = CoreApplication.class)
class QueueDispositionUnificationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LinkageImpactConsumptionService consumptionService;

    @Autowired
    private com.sixbean.testpike.core.spec.domain.service.ApiSpecChangeAnalysisService
            apiSpecAnalysisService;

    @Autowired
    private com.sixbean.testpike.core.document.domain.service.DocumentChangeAnalysisService
            docAnalysisService;

    private long projectId;
    private long userId;
    private long oldSpecId;
    private long newSpecId;
    private long specAnalysisId;
    private long newDocumentId;
    private long docAnalysisId;
    private final List<Long> impactIds = new ArrayList<>();
    private final List<Long> draftIds = new ArrayList<>();

    @BeforeEach
    void seedProjectAndOperator() {
        String tag = "lnk-uni-" + System.nanoTime();
        String code = "lnu" + System.nanoTime() % 1_000_000_000_000L;
        projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, code);
        userId = jdbc.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, '$2a$10$x', ?, 4, 1) RETURNING id",
                Long.class, code, tag);
        jdbc.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, userId);
        oldSpecId = insertSpec("旧契约 " + tag, null);
        newSpecId = insertSpec("新契约 " + tag, oldSpecId);
        newDocumentId = jdbc.queryForObject(
                "INSERT INTO tp_document (project_id, name, file_hash, doc_category, status)"
                        + " VALUES (?, ?, ?, 'TEST_SPEC', 1) RETURNING id",
                Long.class, projectId, "联动文档 " + tag,
                java.util.UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void removeOwnedRows() {
        for (Long impactId : impactIds) {
            jdbc.update("DELETE FROM tp_linkage_proposal WHERE impact_id = ?", impactId);
            jdbc.update("DELETE FROM tp_linkage_impact WHERE id = ?", impactId);
        }
        for (Long draftId : draftIds) {
            jdbc.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", draftId);
        }
        jdbc.update("DELETE FROM tp_api_spec_change_analysis WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_doc_change_analysis WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_api_spec WHERE id IN (?, ?)", oldSpecId, newSpecId);
        jdbc.update("DELETE FROM tp_document WHERE id = ?", newDocumentId);
        jdbc.update("DELETE FROM tp_user_project WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tp_user WHERE id = ?", userId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long insertSpec(String name, Long prevId) {
        return jdbc.queryForObject(
                "INSERT INTO tp_api_spec (project_id, name, spec_format, spec_title,"
                        + " spec_text, content_sha256, spec_version, endpoints, prev_spec_id)"
                        + " VALUES (?, ?, 'openapi3-json', ?, '{}', ?, 1, '[]'::jsonb, ?) RETURNING id",
                Long.class, projectId, name, name,
                java.util.UUID.randomUUID().toString().replace("-", ""), prevId);
    }

    /** Seed a pending api_spec queue row for the version pair under test. */
    private long seedSpecAnalysis() {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_api_spec_change_analysis (project_id, old_spec_id,"
                        + " new_spec_id, changes, detected_source, status, created_by)"
                        + " VALUES (?, ?, ?, '[]'::jsonb, 'upload', 0, ?) RETURNING id",
                Long.class, projectId, oldSpecId, newSpecId, userId);
        assertNotNull(id);
        specAnalysisId = id;
        return id;
    }

    /** Seed a pending doc queue row for the document under test. */
    private long seedDocAnalysis() {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_doc_change_analysis (project_id, old_document_id,"
                        + " new_document_id, changes, chapter_locatable, status, created_by)"
                        + " VALUES (?, ?, ?, '[]'::jsonb, true, 0, ?) RETURNING id",
                Long.class, projectId, newDocumentId, newDocumentId, userId);
        assertNotNull(id);
        docAnalysisId = id;
        return id;
    }

    /** Arm one open ledger row for an upstream save (edge keys are fixture). */
    private long armImpact(String upstreamType, long upstreamId, int status) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type,"
                        + " upstream_object_id, upstream_version, downstream_object_type,"
                        + " downstream_id, disposition_strategy, status)"
                        + " VALUES (?, ?, ?, 1, 'mock_stub', ?, 'mark_only', ?) RETURNING id",
                Long.class, "uni-" + System.nanoTime(), upstreamType, upstreamId,
                newSpecId, status);
        assertNotNull(id);
        impactIds.add(id);
        return id;
    }

    /** Seed a draft card keyed to the impact (the shape the mint seam writes). */
    private long seedDraft(long impactId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_ai_chat_draft (session_id, message_id, project_id,"
                        + " object_type, target_id, schema_version, summary, payload_json,"
                        + " status, seq, context_json)"
                        + " VALUES (NULL, NULL, ?, 'SCRIPT_REPAIR', ?, '1', '统一处置谱',"
                        + " '{}'::jsonb, 1, 1, ?::jsonb) RETURNING id",
                Long.class, projectId, newSpecId,
                "{\"linkageImpactId\": \"" + impactId + "\"}");
        assertNotNull(id);
        draftIds.add(id);
        return id;
    }

    private Map<String, Object> specAnalysisRow() {
        return jdbc.queryForMap("SELECT status, handled_by, handled_at, handle_note"
                + " FROM tp_api_spec_change_analysis WHERE id = ?", specAnalysisId);
    }

    @Test
    void cardAcceptAll_settlesTheQueueRowAndNamesTheCard() {
        long analysisId = seedSpecAnalysis();
        long impactId = armImpact("api_spec", newSpecId, 2);
        long draftId = seedDraft(impactId);

        assertTrue(consumptionService.consumeForDraft(draftId, userId),
                "the keyed impact must settle (existing door behaviour preserved)");

        Map<String, Object> row = specAnalysisRow();
        assertEquals(1, ((Number) row.get("status")).intValue(),
                "queue row " + analysisId + " must be HANDLED after its card was accepted");
        assertNotNull(row.get("handled_at"), "handled_at must be stamped by the seam");
        String note = (String) row.get("handle_note");
        assertNotNull(note, "handle_note must carry the card anchor");
        assertTrue(note.contains(String.valueOf(draftId)),
                "handle_note must name the accepted card, got: " + note);
    }

    @Test
    void specQueueDisposition_settlesArmedImpactsOfTheSameUpstreamSave() {
        long analysisId = seedSpecAnalysis();
        long impactId = armImpact("api_spec", newSpecId, 1);

        apiSpecAnalysisService.handle(userId, analysisId, "复核完成，下游按新契约调整");

        Map<String, Object> impact = jdbc.queryForMap(
                "SELECT status, consumed_at FROM tp_linkage_impact WHERE id = ?", impactId);
        assertEquals(4, ((Number) impact.get("status")).intValue(),
                "armed impact of the same upstream save must settle with the queue row");
        assertNotNull(impact.get("consumed_at"), "consumed_at must be stamped once");
    }

    @Test
    void docQueueDisposition_settlesArmedImpactsOfTheSameUpstreamSave() {
        long analysisId = seedDocAnalysis();
        long impactId = armImpact("doc", newDocumentId, 1);

        docAnalysisService.handle(userId, analysisId, "复核完成，受影响条目已确认");

        assertEquals(1, jdbc.queryForObject(
                "SELECT status FROM tp_doc_change_analysis WHERE id = ?", Integer.class,
                analysisId).intValue(), "doc queue row keeps its existing HANDLED outcome");
        Map<String, Object> impact = jdbc.queryForMap(
                "SELECT status, consumed_at FROM tp_linkage_impact WHERE id = ?", impactId);
        assertEquals(4, ((Number) impact.get("status")).intValue(),
                "doc-side armed impact must settle through the same seam");
        assertNotNull(impact.get("consumed_at"), "consumed_at must be stamped once");
    }

    @Test
    void settledByCard_thenQueueHandle_stillRefusesDoubleDisposition() {
        long analysisId = seedSpecAnalysis();
        long impactId = armImpact("api_spec", newSpecId, 2);
        long draftId = seedDraft(impactId);
        consumptionService.consumeForDraft(draftId, userId);

        assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                () -> apiSpecAnalysisService.handle(userId, analysisId, "再处置一次"),
                "the pending-only guard must survive unification (no silent double fact)");

        // The impact was already consumed by the card; nothing may re-stamp it.
        assertNotNull(jdbc.queryForObject(
                "SELECT consumed_at FROM tp_linkage_impact WHERE id = ?",
                java.sql.Timestamp.class, impactId));
    }

    @Test
    void settledByQueue_thenReplayedQueueHandle_freezesTheFirstStamp() {
        long analysisId = seedSpecAnalysis();
        long impactId = armImpact("api_spec", newSpecId, 2);
        apiSpecAnalysisService.handle(userId, analysisId, "首次处置");
        java.sql.Timestamp firstStamp = jdbc.queryForObject(
                "SELECT consumed_at FROM tp_linkage_impact WHERE id = ?",
                java.sql.Timestamp.class, impactId);
        assertNotNull(firstStamp);
        // The V515 insert default stamps annotated_at at seeding time; the
        // invariant is that a queue disposition (and its replay) never
        // restamps it — only the human card door annotates.
        java.sql.Timestamp annotatedBefore = jdbc.queryForObject(
                "SELECT annotated_at FROM tp_linkage_impact WHERE id = ?",
                java.sql.Timestamp.class, impactId);

        // A second upstream save arms nothing new for this fixture, but a
        // replayed disposition attempt must not move the ledger stamps.
        assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                () -> apiSpecAnalysisService.handle(userId, analysisId, "重复处置"));
        assertEquals(firstStamp, jdbc.queryForObject(
                "SELECT consumed_at FROM tp_linkage_impact WHERE id = ?",
                java.sql.Timestamp.class, impactId),
                "replayed disposition must freeze the first stamp (no phantom facts)");
        assertEquals(annotatedBefore, jdbc.queryForObject(
                        "SELECT annotated_at FROM tp_linkage_impact WHERE id = ?",
                        java.sql.Timestamp.class, impactId),
                "the queue settle path never restamps annotated_at");
    }
}
