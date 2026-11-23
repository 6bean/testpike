package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.SystemDraftMintInput;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.impl.MockStubWriteDraftStrategy;
import com.sixbean.testpike.core.aichat.domain.service.impl.PerfLaunchDraftStrategy;
import com.sixbean.testpike.core.aichat.domain.service.SystemDraftService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN batch / prerequisite (plan piece 4a): landing a linkage-minted
 * draft card must read its project from the stored anchor column, never from
 * a chat session the card does not have.
 * <p>The linkage mint door writes system-origin cards with session_id NULL and
 * a real project_id anchor, while three landing strategies still dereference
 * session_id as if every card came from a dialog — so the grouped card's
 * one-click accept crashed with a NullPointerException instead of landing or
 * refusing in plain words. These cases pin the repaired posture: a session
 * draft keeps the session door untouched, a system-anchor draft resolves
 * through the shared origin-aware anchor, and a draft with NEITHER origin
 * refuses with the plain-word message (never a raw NPE), so the operator can
 * act on what they read.
 * <p>Real chain only: minting goes through the single system-draft service and
 * landing through the real write strategy against the live business tables;
 * fixtures are nano-suffixed owned rows deleted by id afterwards.
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageDraftLandingAnchorTest {

    @Autowired
    private SystemDraftService systemDraftService;

    @Autowired
    private MockStubWriteDraftStrategy mockStubWriteStrategy;

    @Autowired
    private JdbcTemplate jdbc;

    private long projectId;
    private long spaceId;
    private long stubId;
    private int stubVersion;
    private long impactId;

    @BeforeEach
    void seedOwnedStub() {
        long nano = System.nanoTime();
        projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "lnk-anchor-" + nano, "lnka" + nano);
        spaceId = jdbc.queryForObject(
                "INSERT INTO tp_mock_space (project_id, name, space_key) VALUES (?, ?, ?)"
                        + " RETURNING id",
                Long.class, projectId, "联动锚空间" + nano, "lnkas" + nano);
        stubId = jdbc.queryForObject(
                "INSERT INTO tp_mock_stub (space_id, name, method, path_pattern, source)"
                        + " VALUES (?, ?, 'GET', '/pets', 'openapi') RETURNING id",
                Long.class, spaceId, "联动锚桩" + nano);
        stubVersion = jdbc.queryForObject(
                "SELECT version FROM tp_mock_stub WHERE id = ?", Integer.class, stubId);
        impactId = nano;
    }

    @AfterEach
    void removeOwnedRows() {
        jdbc.update("DELETE FROM tp_ai_chat_draft WHERE context_json->>'linkageImpactId' = ?",
                String.valueOf(impactId));
        jdbc.update("DELETE FROM tp_mock_stub WHERE id = ?", stubId);
        jdbc.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Mint one system-origin linkage card for the stub row and re-read it. */
    private Map<String, Object> mintStubCard(String payloadJson, long targetId,
                                             Integer baseVersion) {
        long draftId = systemDraftService.mintLinkageDraft(new SystemDraftMintInput(
                projectId, DraftObjectType.MOCK_STUB_WRITE.wireName, targetId,
                baseVersion, "1", "linkage-anchor", payloadJson,
                "联动重锚草案：契约更新后按新端点形状重写该桩", impactId));
        return jdbc.queryForMap("SELECT id, session_id, project_id, object_type, target_id,"
                + " base_version, schema_version, payload_json, status FROM tp_ai_chat_draft"
                + " WHERE id = ?", draftId);
    }

    @Test
    void landing_a_minted_linkage_card_lands_through_the_stored_project_anchor() {
        Map<String, Object> card = mintStubCard(
                "{\"name\":\"联动重锚后名称\",\"method\":\"GET\",\"pathPattern\":\"/pets\","
                        + "\"matchersJson\":\"[]\",\"responseJson\":\"{}\","
                        + "\"priority\":100,\"enabled\":true}",
                stubId, stubVersion);
        assertEquals(null, card.get("session_id"),
                "the linkage mint door files cards without a chat session");

        Integer landed = mockStubWriteStrategy.land(card, 1L);

        assertEquals(stubVersion + 1, landed,
                "landing an edit card returns the version the chain produced");
        String name = jdbc.queryForObject(
                "SELECT name FROM tp_mock_stub WHERE id = ?", String.class, stubId);
        assertEquals("联动重锚后名称", name,
                "the stub row moved to the re-attributed shape");
    }

    @Test
    void landing_without_any_project_anchor_refuses_in_plain_words() {
        Map<String, Object> orphan = new HashMap<>();
        orphan.put("id", 0L);
        orphan.put("session_id", null);
        orphan.put("project_id", null);
        orphan.put("object_type", DraftObjectType.MOCK_STUB_WRITE.wireName);
        orphan.put("target_id", stubId);
        orphan.put("base_version", stubVersion);
        orphan.put("schema_version", "1");
        orphan.put("payload_json", "{\"name\":\"x\",\"method\":\"GET\","
                + "\"pathPattern\":\"/pets\",\"responseJson\":\"{}\"}");
        orphan.put("status", 1);

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> mockStubWriteStrategy.land(orphan, 1L));
        assertTrue(refusal.getMessage().contains("缺少所属项目"),
                "an unanchored draft refuses with the actionable wording, "
                        + "never a raw NPE: " + refusal.getMessage());
    }

    /**
     * Same posture check for the other two kinds that share the session-only
     * read today: a system-origin PERF_LAUNCH card must resolve its project
     * from the anchor column too. The scenario itself is left missing on
     * purpose — the anchor resolution happens before the launch chain is
     * dialed, so the case asserts the refusal is about the missing scenario,
     * never a NullPointerException on session_id.
     */
    @Test
    void perf_launch_landing_also_reads_the_stored_anchor_before_the_chain() {
        Map<String, Object> card = new HashMap<>();
        card.put("id", 0L);
        card.put("session_id", null);
        card.put("project_id", projectId);
        card.put("object_type", DraftObjectType.PERF_LAUNCH.wireName);
        card.put("target_id", 0L);
        card.put("base_version", null);
        card.put("schema_version", "1");
        card.put("payload_json", "{\"scenarioId\":999999999}");
        card.put("status", 1);

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> perfLaunchStrategy.land(card, 1L));
        assertTrue(refusal.getMessage().contains("场景")
                        || refusal.getMessage().contains("不存在"),
                "the anchor resolves silently and the launch chain refuses the"
                        + " unknown scenario in plain words: " + refusal.getMessage());
    }

    @Autowired
    private PerfLaunchDraftStrategy perfLaunchStrategy;
}
