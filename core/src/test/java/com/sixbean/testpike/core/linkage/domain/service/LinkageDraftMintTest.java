package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalWrite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN batch segment C: a usable linkage proposal snapshot must become a
 * confirmable system-origin draft row behind the single system-draft door,
 * keyed one-per-impact, so the grouped card can offer the one-click accept.
 * <p>Red lines asserted here: the ledger's disposition strategy (not a
 * hard-coded type list in code) decides whether a draft row is minted; a
 * replayed snapshot writes nothing (no phantom "changed" facts, same posture
 * as the empty-save gate); a newer base version re-arms the single row in
 * place instead of stacking cards; a rejected first run leaves no card and
 * honestly withdraws the draft-pending claim on the impact row; minting never
 * touches downstream business content — applying stays behind the human
 * confirmation door.
 * <p>Fixtures are owned rows only (nano-suffixed names, deleted by id) and
 * impact rows are armed directly through the ledger table so each case pins
 * one disposition shape without waiting on the message bus.
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageDraftMintTest {

    @Autowired
    private LinkageProposalService proposalService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long caseId;
    private long scriptId;
    // AN batch ticket 4a second half: contract-side members get their own
    // fixtures (stub under a mock space, request under an api-test collection)
    private long spaceId;
    private long stubId;
    private long collectionId;
    private long requestId;
    private long contractUpstreamId;

    @BeforeEach
    void seedCaseWithScript() {
        LinkageCaseChain seeded = LinkageCaseFixture.seedProjectCaseWithScript(
                jdbc, caseService, "lnk-mint-" + System.nanoTime(),
                "lnkm" + System.nanoTime(), 1L, "联动草案用例");
        projectId = seeded.projectId();
        caseId = seeded.caseId();
        scriptId = seeded.scriptId();
    }

    @AfterEach
    void removeOwnedRows() {
        LinkageCaseFixture.purgeUpstreamChain(jdbc, "case", caseId);
        if (contractUpstreamId > 0) {
            LinkageCaseFixture.purgeUpstreamChain(jdbc, "api_spec", contractUpstreamId);
        }
        if (requestId > 0) {
            jdbc.update("DELETE FROM tp_apitest_request WHERE id = ?", requestId);
        }
        if (collectionId > 0) {
            jdbc.update("DELETE FROM tp_apitest_collection WHERE id = ?", collectionId);
        }
        if (stubId > 0) {
            jdbc.update("DELETE FROM tp_mock_stub WHERE id = ?", stubId);
        }
        if (spaceId > 0) {
            jdbc.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
        LinkageCaseFixture.purgeCaseChain(jdbc,
                new LinkageCaseChain(projectId, caseId, scriptId));
    }

    /** Arm an impact ledger row directly (the shape the dispatch pass writes). */
    private long armImpact(String edgeKey, String downstreamType, long downstreamId,
                           String strategy, int status) {
        return armImpact(edgeKey, "case", caseId, downstreamType, downstreamId, strategy, status);
    }

    /** Arm an impact with any upstream member (contract fan rows name api_spec). */
    private long armImpact(String edgeKey, String upstreamType, long upstreamId,
                           String downstreamType, long downstreamId,
                           String strategy, int status) {
        return jdbc.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type,"
                        + " upstream_object_id, upstream_version, downstream_object_type,"
                        + " downstream_id, disposition_strategy, status)"
                        + " VALUES (?, ?, ?, 3, ?, ?, ?, ?) RETURNING id",
                Long.class, edgeKey, upstreamType, upstreamId, downstreamType, downstreamId,
                strategy, status);
    }

    /**
     * Seed the contract-side fixture pair: one derived stub under a mock space
     * and one imported request under an api-test collection, both anchored on
     * the folded keys the contract detectors compare against.
     */
    private void seedContractSideFixtures() {
        spaceId = jdbc.queryForObject(
                "INSERT INTO tp_mock_space (project_id, name, space_key) VALUES (?, ?, ?)"
                        + " RETURNING id",
                Long.class, projectId, "联动mint空间" + System.nanoTime(),
                "lnkmint" + (System.nanoTime() % 1_000_000));
        stubId = jdbc.queryForObject(
                "INSERT INTO tp_mock_stub (space_id, name, method, path_pattern, source)"
                        + " VALUES (?, ?, 'GET', '/pets', 'openapi') RETURNING id",
                Long.class, spaceId, "联动mint派生桩" + System.nanoTime());
        collectionId = jdbc.queryForObject(
                "INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags,"
                        + " headers_json) VALUES (?, 0, ?, '', '[]'::jsonb) RETURNING id",
                Long.class, projectId, "联动mint接口集" + System.nanoTime());
        requestId = jdbc.queryForObject(
                "INSERT INTO tp_apitest_request (collection_id, name, method, url_path,"
                        + " contract_endpoint_key) VALUES (?, ?, 'POST', '/pets', 'POST /pets')"
                        + " RETURNING id",
                Long.class, collectionId, "联动mint引入请求" + System.nanoTime());
        // The bus keys impacts on the spec version row; minting only anchors
        // the downstream member, so a synthetic upstream id pins the ledger
        // shape without waiting for a real contract upload.
        contractUpstreamId = 990000000L + Math.abs(java.util.UUID.randomUUID().getMostSignificantBits() % 1_000_000);
    }

    private LinkageProposalInput usableProposal(long impactId) {
        return new LinkageProposalInput(impactId, "script", scriptId,
                "{\"steps\":[{\"action\":\"click\"}]}", "v2", "test-model", 4, null);
    }

    private List<Map<String, Object>> mintedDrafts(long impactId) {
        return jdbc.queryForList(
                "SELECT id, project_id, session_id, message_id, object_type, target_id,"
                        + " base_version, schema_version, payload_json, status, updated_at"
                        + " FROM tp_ai_chat_draft WHERE context_json->>'linkageImpactId' = ?",
                String.valueOf(impactId));
    }

    private int impactStatus(long impactId) {
        Integer status = jdbc.queryForObject(
                "SELECT status FROM tp_linkage_impact WHERE id = ?", Integer.class, impactId);
        assertNotNull(status);
        return status;
    }

    @Test
    void usable_snapshot_mints_one_confirmable_system_draft_row() {
        long impactId = armImpact("case->script", "script", scriptId, "grouped_draft", 2);

        LinkageProposalWrite written = proposalService.record(usableProposal(impactId));

        assertEquals(1, written.status().intValue());
        List<Map<String, Object>> drafts = mintedDrafts(impactId);
        assertEquals(1, drafts.size(), "a usable snapshot must mint exactly one draft card");
        Map<String, Object> row = drafts.get(0);
        assertEquals("SCRIPT_REPAIR", row.get("object_type"));
        assertEquals(caseId, ((Number) row.get("target_id")).longValue(),
                "the repair landing strategy anchors on the owning case");
        assertEquals(4, ((Number) row.get("base_version")).intValue(),
                "the optimistic anchor is the script version the proposal was cut against");
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
        assertEquals("v2", row.get("schema_version"));
        assertTrue(String.valueOf(row.get("payload_json")).contains("click"),
                "the payload handed to the door is the repaired action JSON");
        assertNull(row.get("session_id"),
                "the minted card is system-origin: no session, no message anchor");
        assertNull(row.get("message_id"));
        // marking/drafting never rewrites downstream business content (batch red line)
        assertEquals(1, jdbc.queryForObject("SELECT status FROM tp_script WHERE id = ?",
                Integer.class, scriptId).intValue());
        assertEquals(4, jdbc.queryForObject("SELECT version FROM tp_script WHERE id = ?",
                Integer.class, scriptId).intValue());
    }

    @Test
    void replayed_snapshot_mints_nothing_twice_and_freezes_the_draft_row() {
        long impactId = armImpact("case->script", "script", scriptId, "grouped_draft", 2);
        proposalService.record(usableProposal(impactId));
        Map<String, Object> first = mintedDrafts(impactId).get(0);

        proposalService.record(usableProposal(impactId));

        List<Map<String, Object>> drafts = mintedDrafts(impactId);
        assertEquals(1, drafts.size(), "a replayed snapshot stacks no card");
        assertEquals(first.get("id"), drafts.get(0).get("id"));
        assertEquals(first.get("updated_at"), drafts.get(0).get("updated_at"),
                "an identical replay must not stamp the card (no phantom changed fact)");
    }

    @Test
    void newer_base_version_rearms_the_single_draft_row_in_place() {
        long impactId = armImpact("case->script", "script", scriptId, "grouped_draft", 2);
        proposalService.record(usableProposal(impactId));
        long draftId = ((Number) mintedDrafts(impactId).get(0).get("id")).longValue();

        proposalService.record(new LinkageProposalInput(impactId, "script", scriptId,
                "{\"steps\":[{\"action\":\"type\"}]}", "v2", "test-model", 5, null));

        List<Map<String, Object>> drafts = mintedDrafts(impactId);
        assertEquals(1, drafts.size(), "a re-armed impact keeps exactly one pending card");
        assertEquals(draftId, ((Number) drafts.get(0).get("id")).longValue());
        assertEquals(5, ((Number) drafts.get(0).get("base_version")).intValue());
        assertTrue(String.valueOf(drafts.get(0).get("payload_json")).contains("type"));
        assertEquals(1, ((Number) drafts.get(0).get("status")).intValue(),
                "the re-armed card waits for confirmation again");
    }

    @Test
    void rejected_first_run_mints_no_card_and_withdraws_the_pending_claim() {
        long impactId = armImpact("case->script", "script", scriptId, "grouped_draft", 2);

        LinkageProposalWrite rejected = proposalService.record(new LinkageProposalInput(
                impactId, "script", scriptId, null, null, null, 4, "提案服务暂时不可用"));

        assertEquals(3, rejected.status().intValue());
        assertTrue(mintedDrafts(impactId).isEmpty(),
                "an unusable proposal must not surface a card the door cannot honour");
        assertEquals(1, impactStatus(impactId),
                "without a minted card the impact is a stale mark, not a draft pending");
    }

    @Test
    void disposition_ledger_not_a_type_list_decides_minting() {
        long impactId = armImpact("case->plan_case", "plan_case", caseId, "mark_only", 1);

        proposalService.record(new LinkageProposalInput(impactId, "plan_case", caseId,
                "{\"steps\":[{\"action\":\"click\"}]}", "v2", "test-model", 4, null));

        assertTrue(mintedDrafts(impactId).isEmpty(),
                "only edges whose ledger strategy says grouped_draft mint cards");
        assertEquals(1, impactStatus(impactId));
    }

    @Test
    void contract_stub_member_snapshot_mints_a_stub_edit_card_on_the_space_project() {
        seedContractSideFixtures();
        long impactId = armImpact("api_spec->mock_stub", "api_spec", contractUpstreamId,
                "mock_stub", stubId, "grouped_draft", 2);
        String stubName = jdbc.queryForObject(
                "SELECT name FROM tp_mock_stub WHERE id = ?", String.class, stubId);
        Integer stubVersion = jdbc.queryForObject(
                "SELECT version FROM tp_mock_stub WHERE id = ?", Integer.class, stubId);

        proposalService.record(new LinkageProposalInput(impactId, "mock_stub", stubId,
                "{\"name\":\"" + stubName + "\",\"method\":\"GET\",\"pathPattern\":\"/pets\"}",
                "v2", "contract-snapshot", stubVersion, null));

        List<Map<String, Object>> cards = mintedDrafts(impactId);
        assertEquals(1, cards.size(),
                "an armed contract-derived stub must mint exactly one confirmable card");
        Map<String, Object> card = cards.get(0);
        assertEquals("MOCK_STUB_WRITE", card.get("object_type"),
                "the stub card lands through the existing mock-stub write strategy");
        assertNull(card.get("session_id"),
                "a linkage card is system-origin: no chat session owns it");
        assertEquals(projectId, ((Number) card.get("project_id")).longValue(),
                "the card anchors on the owning mock space's project");
        assertEquals(stubId, ((Number) card.get("target_id")).longValue());
        assertTrue(String.valueOf(card.get("payload_json")).contains("/pets"),
                "the deterministic snapshot is handed verbatim to the landing strategy");
        assertEquals(2, impactStatus(impactId),
                "the ledger keeps draft-pending only while a card actually exists");
    }

    @Test
    void contract_request_member_snapshot_mints_a_collection_defaults_card_on_the_collection_project() {
        seedContractSideFixtures();
        long impactId = armImpact("api_spec->apitest_request", "api_spec", contractUpstreamId,
                "apitest_request", requestId, "grouped_draft", 2);

        proposalService.record(new LinkageProposalInput(impactId, "apitest_request", requestId,
                "{\"collectionId\":" + collectionId + ",\"headersJson\":\"[]\"}",
                "v2", "contract-snapshot", null, null));

        List<Map<String, Object>> cards = mintedDrafts(impactId);
        assertEquals(1, cards.size(),
                "an armed imported request must mint exactly one confirmable card");
        Map<String, Object> card = cards.get(0);
        assertEquals("APITEST_WRITE", card.get("object_type"),
                "the request card lands through the existing collection-defaults strategy");
        assertNull(card.get("session_id"),
                "a linkage card is system-origin: no chat session owns it");
        assertEquals(projectId, ((Number) card.get("project_id")).longValue(),
                "the card anchors on the owning collection's project");
        assertEquals(requestId, ((Number) card.get("target_id")).longValue());
        assertEquals(2, impactStatus(impactId),
                "the ledger keeps draft-pending only while a card actually exists");
    }
}
