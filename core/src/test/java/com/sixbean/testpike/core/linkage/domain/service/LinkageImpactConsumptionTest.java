package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftGroupApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.domain.component.DraftGroupScope;
import com.sixbean.testpike.core.aichat.domain.service.DraftConfirmationService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Impact-ledger consumption behind the human confirmation door (
 * closing gap): when the operator accepts a minted linkage card, the ledger
 * row keyed by that card must settle to status 4=consumed with its
 * consumed_at stamped, or the panel's debt list keeps carrying a row whose
 * proposal already landed (the spectrum window pinned 198 rows stuck at
 * status 2 after a green one-click accept).
 * <p>Both doors are walked end-to-end on the real database with zero mocks:
 * the single confirm ticket path and the grouped "accept all" path share one
 * consumption seam. A replayed consume writes nothing a second time —
 * consumed_at freezes on its first stamp (no phantom re-consume facts), and
 * a draft without a linkage anchor consumes nothing at all.
 * <p>Fixtures are run-owned nano-suffixed rows deleted by id; the ledger
 * cards themselves are minted through the proposal service so the payload
 * shape is production truth, never a hand-copy.
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageImpactConsumptionTest {

    private static final String REPAIR_KIND = "SCRIPT_REPAIR";
    private static final String PROPOSAL_JSON = "{\"steps\":[{\"action\":\"click\"}]}";

    @Autowired
    private LinkageProposalService proposalService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long caseId;
    private long scriptId;
    private long userId;
    private final List<Long> impactIds = new ArrayList<>();

    @BeforeEach
    void seedProjectCaseScriptAndOperator() {
        String tag = "lnk-cons-" + System.nanoTime();
        String code = "lnc" + System.nanoTime() % 1_000_000_000_000L;
        projectId = LinkageCaseFixture.insertProject(jdbc, tag, code);
        userId = jdbc.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                        + " VALUES (?, '$2a$10$x', ?, 4, 1) RETURNING id",
                Long.class, code, tag);
        jdbc.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, userId);
        caseId = LinkageCaseFixture.seedCaseUnderProject(
                jdbc, caseService, projectId, userId, "联动消费用例");
        scriptId = LinkageCaseFixture.seedScriptForCase(jdbc, caseId);
    }

    @AfterEach
    void removeOwnedRows() {
        LinkageCaseFixture.purgeUpstreamChain(jdbc, "case", caseId);
        for (Long impactId : impactIds) {
            jdbc.update("DELETE FROM tp_linkage_impact WHERE id = ?", impactId);
        }
        jdbc.update("DELETE FROM tp_user_project WHERE user_id = ?", userId);
        LinkageCaseFixture.purgeCaseChain(jdbc,
                new LinkageCaseChain(projectId, caseId, scriptId));
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbc.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    /** Arm a draft-pending ledger row and mint its card through the real door. */
    private long armImpactAndMintCard(long downstreamCaseId, long downstreamScriptId) {
        Long impactId = jdbc.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type,"
                        + " upstream_object_id, upstream_version, downstream_object_type,"
                        + " downstream_id, disposition_strategy, status)"
                        + " VALUES (?, 'case', ?, 2, 'script', ?, 'grouped_draft', 2) RETURNING id",
                Long.class, "consume-" + System.nanoTime(), downstreamCaseId, downstreamScriptId);
        assertNotNull(impactId);
        impactIds.add(impactId);
        proposalService.record(new LinkageProposalInput(impactId, "script", downstreamScriptId,
                PROPOSAL_JSON, "v2", "test-model", 4, null));
        return impactId;
    }

    private long mintedDraftId(long impactId) {
        return jdbc.queryForObject("SELECT id FROM tp_ai_chat_draft"
                        + " WHERE context_json->>'linkageImpactId' = ?",
                Long.class, String.valueOf(impactId));
    }

    private Map<String, Object> impactRow(long impactId) {
        return jdbc.queryForMap("SELECT status, consumed_at FROM tp_linkage_impact WHERE id = ?",
                impactId);
    }

    @Test
    void singleConfirmedCard_settlesItsLedgerRowToConsumed() {
        long impactId = armImpactAndMintCard(caseId, scriptId);
        long draftId = mintedDraftId(impactId);

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        DraftApplyResult applied = confirmationService.apply(userId, draftId, ticket.token());

        assertNotNull(applied, "the repair landed through the real door");
        assertEquals(5, ((Number) jdbc.queryForObject(
                "SELECT version FROM tp_script WHERE id = ?", Integer.class, scriptId))
                .intValue(), "the repaired script line moved one version");
        Map<String, Object> row = impactRow(impactId);
        assertEquals(4, ((Number) row.get("status")).intValue(),
                "an accepted card settles its ledger row to 4=consumed");
        assertNotNull(row.get("consumed_at"), "consumed_at stamps at the acceptance");
    }

    @Test
    void groupAcceptedCards_settleEveryKeyedRow() {
        long impactId = armImpactAndMintCard(caseId, scriptId);
        long draftId = mintedDraftId(impactId);
        String scope = DraftGroupScope.forSystem(projectId, REPAIR_KIND).wire();

        DraftTicket ticket = confirmationService.issueGroupConfirmTokenByScope(userId, scope);
        DraftGroupApplyResult result =
                confirmationService.applyGroupByScope(userId, scope, ticket.token(), List.of(draftId));

        assertFalse(result.items().isEmpty(), "the group receipt names the landed card");
        Map<String, Object> row = impactRow(impactId);
        assertEquals(4, ((Number) row.get("status")).intValue(),
                "the one-click accept-all path settles the ledger row like the single door");
        assertNotNull(row.get("consumed_at"));
    }

    @Test
    void unrelatedDraft_consumesNothingAndStaysSilent() {
        // A card with no linkage anchor must not touch the ledger at all —
        // the consume seam is keyed, never a blanket flip.
        long otherCase = caseService.create(userId, new CaseCreateInput(
                projectId, "无锚消费用例", "前置", "预期", 1, 3, "web", 1, null, 1, null, null)).id();
        long draftId = jdbc.queryForObject(
                "INSERT INTO tp_ai_chat_draft (session_id, message_id, project_id, object_type,"
                        + " target_id, base_version, schema_version, summary, payload_json,"
                        + " status, seq, context_json)"
                        + " VALUES (NULL, NULL, ?, ?, ?, 4, 'v2', '无锚修复草案',"
                        + " CAST(? AS jsonb), 1, 7, NULL) RETURNING id",
                Long.class, projectId, REPAIR_KIND, otherCase, PROPOSAL_JSON);
        jdbc.update("INSERT INTO tp_script (case_id, status, action_json, version)"
                + " VALUES (?, 1, '{\"steps\":[]}'::jsonb, 4)", otherCase);

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        confirmationService.apply(userId, draftId, ticket.token());

        assertTrue(impactIds.stream().allMatch(id ->
                        ((Number) impactRow(id).get("status")).intValue() != 4),
                "no keyed anchor means no ledger row moves");

        jdbc.update("DELETE FROM tp_script WHERE case_id = ?", otherCase);
        jdbc.update("DELETE FROM tp_case_step WHERE case_id = ?", otherCase);
        jdbc.update("DELETE FROM tp_test_case WHERE id = ?", otherCase);
    }

    /**
     * The consumption seam is idempotent: a replay finds consumed_at already
     * stamped and writes nothing a second time — no phantom re-consume facts.
     */
    @Test
    void replayedConsume_writesNothingAndFreezesTheFirstStamp() {
        long impactId = armImpactAndMintCard(caseId, scriptId);
        long draftId = mintedDraftId(impactId);
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        confirmationService.apply(userId, draftId, ticket.token());
        Object first = impactRow(impactId).get("consumed_at");
        assertNotNull(first, "the acceptance stamped the row");

        boolean rewrote = consumptionService.consumeForDraft(draftId, userId);

        assertFalse(rewrote, "a replayed consume is a true no-op");
        assertEquals(String.valueOf(first),
                String.valueOf(impactRow(impactId).get("consumed_at")),
                "consumed_at keeps its first stamp");
    }

    @Autowired
    private LinkageImpactConsumptionService consumptionService;
}
