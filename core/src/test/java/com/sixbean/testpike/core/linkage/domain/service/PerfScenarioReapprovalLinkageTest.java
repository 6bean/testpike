package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftMintInput;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.infrastructure.mq.ChangeLinkageConsumer;
import com.sixbean.testpike.core.linkage.support.ObjectChangedEventQueueProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-approval drafting for performance scenarios: editing a scenario that
 * already carries a decided approval must surface ONE confirmable
 * re-approval card, and accepting it must file a fresh approval request
 * through the SAME approval chain — the original ticket stays as written
 * history and is never rewritten.
 * <p>Red lines asserted here: the ledger's grouped-draft disposition decides
 * that a card is minted (no type list duplicated in code); the card is
 * system-origin (no chat session, project anchored on the approval row); the
 * re-approval kind has a landing strategy at all — a card nobody can accept
 * is the silent failure this batch forbids; landing writes only a NEW pending
 * ticket and leaves the old row byte-identical; and the bus leg files the
 * proposal snapshot deterministically from the affected ticket instead of
 * leaving the draft pending with nothing to accept.
 * <p>Real chain only: the bus leg runs through the live consumer entry against
 * the seeded production edge ledger (perf_scenario -&gt; perf_approval);
 * fixtures are owned rows only (nano-suffixed names, deleted by id after).
 */
@SpringBootTest(classes = CoreApplication.class)
class PerfScenarioReapprovalLinkageTest {

    /** Landing kind token of the re-approval card (DraftObjectType value). */
    private static final String KIND_PERF_APPROVAL = "PERF_APPROVAL";

    private static final String EDGE_KEY = "perf_scenario->perf_approval";

    @Autowired
    private LinkageProposalService proposalService;

    @Autowired
    private ChangeLinkageConsumer linkageConsumer;

    @Autowired
    private SystemDraftService systemDraftService;

    @Autowired
    private List<DraftWriteStrategy> writeStrategies;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long envId;
    private long operatorId;
    private long scenarioId;
    private long approvalId;

    @BeforeEach
    void seedApprovedScenario() {
        long nano = System.nanoTime();
        operatorId = jdbc.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "lnk-reappr-" + nano, "lnkr" + (nano % 1_000_000));
        envId = jdbc.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url)"
                        + " VALUES (?, '联动重审环境', 1, 'http://perf-target.local/api') RETURNING id",
                Long.class, projectId);
        scenarioId = jdbc.queryForObject(
                "INSERT INTO tp_perf_scenario (project_id, env_id, name, pressure_model,"
                        + " pressure_config, steps_json) VALUES (?, ?, ?, 'CONSTANT_RPS',"
                        + " '{\"targetRps\":40,\"durationSeconds\":60}'::jsonb,"
                        + " '[{\"action\":\"http.request\",\"method\":\"GET\","
                        + "\"url\":\"http://perf-target.local/api/orders\"}]'::jsonb) RETURNING id",
                Long.class, projectId, envId, "联动重审场景" + nano);
        approvalId = jdbc.queryForObject(
                "INSERT INTO tp_perf_approval (project_id, env_id, requested_concurrency,"
                        + " isolation_mode, reason, applicant_id, status, scenario_id)"
                        + " VALUES (?, ?, 40, 'SHADOW_TABLE', '联动重审原审批单', ?, 3, ?) RETURNING id",
                Long.class, projectId, envId, operatorId, scenarioId);
    }

    @AfterEach
    void removeOwnedRows() {
        jdbc.update("DELETE FROM tp_linkage_proposal WHERE impact_id IN"
                + " (SELECT id FROM tp_linkage_impact WHERE edge_key = ? AND downstream_id = ?)",
                EDGE_KEY, approvalId);
        jdbc.update("DELETE FROM tp_ai_chat_draft WHERE context_json->>'linkageImpactId' IN"
                + " (SELECT CAST(id AS TEXT) FROM tp_linkage_impact"
                + "  WHERE edge_key = ? AND downstream_id = ?)", EDGE_KEY, approvalId);
        jdbc.update("DELETE FROM tp_linkage_impact WHERE edge_key = ? AND downstream_id = ?",
                EDGE_KEY, approvalId);
        // FK order: approval tickets link the scenario, so children go first.
        jdbc.update("DELETE FROM tp_perf_approval WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_perf_scenario WHERE id = ?", scenarioId);
        jdbc.update("DELETE FROM tp_project_env WHERE id = ?", envId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** The re-approval proposal shape the deterministic producer files. */
    private String reapprovalPayload() {
        return "{\"scenarioId\":" + scenarioId + ",\"envId\":" + envId
                + ",\"requestedConcurrency\":40,\"isolationMode\":\"SHADOW_TABLE\","
                + "\"reason\":\"场景在上次审批后又被修改，按当前形状重新申请审批\"}";
    }

    private long armReapprovalImpact() {
        return jdbc.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type,"
                        + " upstream_object_id, upstream_version, downstream_object_type,"
                        + " downstream_id, disposition_strategy, status)"
                        + " VALUES (?, 'perf_scenario', ?, 2, 'perf_approval', ?,"
                        + " 'grouped_draft', 2) RETURNING id",
                Long.class, EDGE_KEY, scenarioId, approvalId);
    }

    private List<Map<String, Object>> mintedDrafts(long impactId) {
        return jdbc.queryForList(
                "SELECT id, session_id, project_id, object_type, target_id, payload_json, status"
                        + " FROM tp_ai_chat_draft WHERE context_json->>'linkageImpactId' = ?",
                String.valueOf(impactId));
    }

    /** Byte-for-byte reading of the history ticket the loop must never rewrite. */
    private String historyOf(long id) {
        return jdbc.queryForObject(
                "SELECT row_to_json(t)::text FROM (SELECT status, requested_concurrency,"
                        + " isolation_mode, reason, approver_id, handled_at, reject_reason"
                        + " FROM tp_perf_approval WHERE id = ?) t", String.class, id);
    }

    private DraftWriteStrategy strategyFor(String kind) {
        return writeStrategies.stream()
                .filter(s -> kind.equals(s.objectType()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void usable_reapproval_snapshot_mints_one_confirmable_card_on_the_ticket_project() {
        long impactId = armReapprovalImpact();

        proposalService.record(new LinkageProposalInput(impactId, "perf_approval", approvalId,
                reapprovalPayload(), "v2", "perf-reapproval-snapshot", null, null));

        List<Map<String, Object>> cards = mintedDrafts(impactId);
        assertEquals(1, cards.size(),
                "an armed re-approval impact must mint exactly one confirmable card");
        Map<String, Object> card = cards.get(0);
        assertEquals(KIND_PERF_APPROVAL, card.get("object_type"),
                "the re-approval card names its own landing kind");
        assertNull(card.get("session_id"),
                "a linkage card is system-origin: no chat session owns it");
        assertEquals(projectId, ((Number) card.get("project_id")).longValue(),
                "the card anchors on the affected approval ticket's project");
        assertEquals(approvalId, ((Number) card.get("target_id")).longValue(),
                "the card names the approval row the human confirmed an action on");
        assertEquals(2, jdbc.queryForObject(
                        "SELECT status FROM tp_linkage_impact WHERE id = ?",
                        Integer.class, impactId).intValue(),
                "the ledger keeps draft-pending only while a card exists");
    }

    @Test
    void reapproval_landing_files_a_fresh_pending_ticket_and_leaves_the_history_row_untouched() {
        assertNotNull(strategyFor(KIND_PERF_APPROVAL),
                "the re-approval kind needs a landing strategy — a card nobody can accept"
                        + " is the silent failure this batch forbids");

        long impactId = armReapprovalImpact();
        long draftId = systemDraftService.mintLinkageDraft(new SystemDraftMintInput(
                projectId, KIND_PERF_APPROVAL, approvalId, null, "v2",
                "perf-reapproval-snapshot", reapprovalPayload(),
                "联动重审草案：上游压测场景在上次审批后被修改，接受后按当前形状重新提交审批申请",
                impactId));
        Map<String, Object> card = jdbc.queryForMap(
                "SELECT id, session_id, project_id, object_type, target_id, base_version,"
                        + " schema_version, payload_json, status FROM tp_ai_chat_draft"
                        + " WHERE id = ?", draftId);
        String before = historyOf(approvalId);

        Integer appliedVersion = strategyFor(KIND_PERF_APPROVAL).land(card, operatorId);

        assertNull(appliedVersion, "an approval ticket has no version ledger for this kind");
        assertEquals(before, historyOf(approvalId),
                "the original approval row is written history, never rewritten by the loop");
        List<Map<String, Object>> fresh = jdbc.queryForList(
                "SELECT status, env_id, requested_concurrency, isolation_mode, reason,"
                        + " applicant_id FROM tp_perf_approval WHERE scenario_id = ? AND id <> ?"
                        + " ORDER BY id DESC", scenarioId, approvalId);
        assertEquals(1, fresh.size(),
                "accepting the re-approval card files exactly one fresh request");
        Map<String, Object> filed = fresh.get(0);
        assertEquals(2, ((Number) filed.get("status")).intValue(),
                "the fresh request waits for approval (status 2); the human approver decides");
        assertEquals(envId, ((Number) filed.get("env_id")).longValue(),
                "the fresh request names the environment of the scenario's current shape");
        assertEquals(operatorId, ((Number) filed.get("applicant_id")).longValue(),
                "the accepting human is the applicant of the fresh request");
    }

    @Test
    void scenario_edit_on_the_bus_files_a_deterministic_reapproval_snapshot() {
        linkageConsumer.onObjectChanged(ObjectChangedEventQueueProbe.rawFrame(
                new ObjectChangedEventMessage(
                ObjectChangedEventType.PERF_SCENARIO.name(), scenarioId, projectId, operatorId,
                2, List.of("pressure_config"), System.currentTimeMillis())));

        Long impactId = jdbc.queryForObject(
                "SELECT id FROM tp_linkage_impact WHERE edge_key = ? AND upstream_object_id = ?"
                        + " AND downstream_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, EDGE_KEY, scenarioId, approvalId);
        assertNotNull(impactId, "the production edge ledger arms the re-approval impact");
        List<String> snapshots = jdbc.queryForList(
                "SELECT proposal_json::text FROM tp_linkage_proposal WHERE impact_id = ?",
                String.class, impactId);
        assertFalse(snapshots.isEmpty(),
                "the bus leg must file the proposal snapshot, not leave the draft pending"
                        + " with nothing to accept");
        String payload = snapshots.get(0);
        // jsonb text rendering puts a space after the colon; match the
        // key->value fact, not the storage's whitespace.
        assertTrue(payload.matches("(?s).*\"scenarioId\":\\s*" + scenarioId + ".*"),
                "the snapshot re-approves the scenario's current shape: " + payload);
        assertTrue(payload.matches("(?s).*\"envId\":\\s*" + envId + ".*"),
                "the snapshot names the scenario's current environment: " + payload);

        List<Map<String, Object>> cards = mintedDrafts(impactId);
        assertEquals(1, cards.size(),
                "the deterministic snapshot reaches the same mint door as a posted proposal");
        assertEquals(KIND_PERF_APPROVAL, cards.get(0).get("object_type"));
    }

    @Test
    void a_ticket_already_waiting_for_approval_needs_no_second_reapproval_card() {
        // The history row is already pending (status 2): nothing expired, so
        // the ledger must not stack a re-approval card on top of a live request.
        jdbc.update("UPDATE tp_perf_approval SET status = 2, handled_at = NULL, approver_id = NULL"
                + " WHERE id = ?", approvalId);

        linkageConsumer.onObjectChanged(ObjectChangedEventQueueProbe.rawFrame(
                new ObjectChangedEventMessage(
                ObjectChangedEventType.PERF_SCENARIO.name(), scenarioId, projectId, operatorId,
                3, List.of("pressure_config"), System.currentTimeMillis())));

        Long impactId = jdbc.queryForObject(
                "SELECT id FROM tp_linkage_impact WHERE edge_key = ? AND upstream_object_id = ?"
                        + " AND downstream_id = ? AND upstream_version = 3 ORDER BY id DESC LIMIT 1",
                Long.class, EDGE_KEY, scenarioId, approvalId);
        assertNotNull(impactId, "the impact ledger still records the edit");
        assertTrue(mintedDrafts(impactId).isEmpty(),
                "a scenario whose approval is still open gets no duplicate re-approval card");
    }
}
