package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case-write drafts behind the generic confirmation door (AO batch
 * stage 2): an AI proposal to create or edit a test case becomes a real
 * business write ONLY after a human walks the same shared chain the repair
 * card uses — no second confirmation chain (red line 6), proposals never
 * self-apply.
 * <p>The spectrum this suite pins on the real dev database, zero mocks:
 * <ul>
 * <li>create-kind draft lands pending, confirm → case really exists
 * (reloaded from CaseService, steps included) and the receipt points
 * at the new case id;</li>
 * <li>update-kind draft confirm → field-level edit lands with the version
 * bump recorded on the draft row;</li>
 * <li>stale base_version = optimistic-lock refusal, ticket burned, case
 * untouched;</li>
 * <li>horizontal access: a stranger cannot open the door;</li>
 * <li>attribution guard: a draft aimed at a case from another project than
 * the session's is refused before any write;</li>
 * <li>kind-aware anchor contract: a CASE_WRITE frame without a proposal
 * body skips the landing honestly (half-trusted snapshot = no
 * confirmable row).</li>
 * </ul>
 * Same posture as DraftConfirmationChainTest / DraftConfirmationLandingTest.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseWriteDraftFlowTest {

    /** Proposal body of the create kind (shape the nlstep dialog emitter will send). */
    private static final String CREATE_PAYLOAD =
            "{\"title\":\"AI drafted login case\",\"precondition\":\"user seeded\","
                    + "\"expectedResult\":\"home shown\",\"priority\":1,\"confidence\":2,"
                    + "\"endType\":\"web\",\"phase\":2,"
                    + "\"steps\":[{\"stepNo\":1,\"description\":\"open login\",\"isAssert\":0},"
                    + "{\"stepNo\":2,\"description\":\"home visible\",\"isAssert\":1}]}";

    private static final String TOKEN_KEY_PREFIX = "aichat:draft:confirm:";

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DraftCardMinting minting;

    @Autowired
    private DraftLedgerCleanup cleanup;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tag;
    private long projectId;
    private long userId;
    private long sessionId;
    private final java.util.List<Long> createdCaseIds = new java.util.ArrayList<>();
    private final java.util.List<Long> sideProjectIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "t430w" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        ChatSessionInfo session = sessionService.create(projectId, userId, "Case write " + tag,
                1, "{\"route\":\"/cases\"}");
        sessionId = session.id();
    }

    @AfterEach
    void tearDown() {
        cleanup.sessionChain(sessionId);
        cleanup.caseCascade(createdCaseIds);
        for (long sideProject : sideProjectIds) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", sideProject);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    /** Land a CASE_WRITE draft through the real frame ledger (shared write point). */
    private long landCaseWriteDraft(long caseId, int baseVersion, String payloadJson) {
        return minting.caseWriteDraft(sessionId, caseId, baseVersion, payloadJson);
    }

    private int draftStatus(long draftId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertNotNull(status);
        return status;
    }

    private long seedCase(String title) {
        CaseInfo created = caseService.create(userId, new CaseCreateInput(projectId, title,
                "pre", "expect", 1, 2, "web", null, 2, 1, null, List.of()));
        createdCaseIds.add(created.id());
        return created.id();
    }

    @Test
    void confirmedCreateDraftLandsARealCaseAndReceiptPointsAtTheNewId() {
        long draftId = landCaseWriteDraft(0L, 0, CREATE_PAYLOAD);
        assertEquals("CASE_WRITE", jdbcTemplate.queryForObject(
                "SELECT object_type FROM tp_ai_chat_draft WHERE id = ?", String.class, draftId));
        assertEquals(1, draftStatus(draftId), "a create proposal waits behind the door");

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        DraftApplyResult result = confirmationService.apply(userId, draftId, ticket.token());

        assertNotNull(result.targetId(), "the receipt must name the case that was created");
        createdCaseIds.add(result.targetId());
        CaseInfo live = caseService.get(result.targetId());
        assertEquals("AI drafted login case", live.title());
        assertEquals(projectId, live.projectId(), "the case lands in the session's project");
        assertEquals(2, live.steps().size(), "steps ride into the real save chain");
        assertEquals("chat", live.sourceRef(),
                "the in-product conversation marker rides on the landed case row");
        assertEquals(1, result.appliedVersion(), "a fresh case is at version 1");
        assertEquals(2, draftStatus(draftId));
        Long pinnedTarget = jdbcTemplate.queryForObject(
                "SELECT target_id FROM tp_ai_chat_draft WHERE id = ?", Long.class, draftId);
        assertEquals(result.targetId(), pinnedTarget, "the ledger row is pinned to the created case");
    }

    @Test
    void confirmedUpdateDraftEditsTheCaseWithVersionBump() {
        long caseId = seedCase("original title");
        int baseVersion = caseService.get(caseId).version();
        long draftId = landCaseWriteDraft(caseId, baseVersion,
                "{\"title\":\"renamed by AI\",\"steps\":[{\"stepNo\":1,"
                        + "\"description\":\"only step\",\"isAssert\":0}]}");

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        DraftApplyResult result = confirmationService.apply(userId, draftId, ticket.token());

        CaseInfo live = caseService.get(caseId);
        assertEquals("renamed by AI", live.title());
        assertEquals(1, live.steps().size(), "steps replace wholesale per the update contract");
        assertEquals(baseVersion + 1, live.version());
        assertEquals(baseVersion + 1, result.appliedVersion());
        assertEquals(caseId, result.targetId());
    }

    @Test
    void staleBaseVersionRefusesAndBurnsTheTicket() {
        long caseId = seedCase("conflict target");
        int baseVersion = caseService.get(caseId).version();
        long draftId = landCaseWriteDraft(caseId, baseVersion, "{\"title\":\"late proposal\"}");

        // Someone edits the case after the proposal was cut: the anchor moves on.
        caseService.update(userId, caseId,
                new com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput(
                        "human edit", null, null, null, null, null, null, null), baseVersion);

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, ticket.token()));
        assertEquals(BizErrorCode.VERSION_CONFLICT, conflict.getCode());
        assertEquals("human edit", caseService.get(caseId).title(), "a stale proposal writes nothing");
        assertEquals(1, draftStatus(draftId), "the draft survives for a fresh proposal round");

        redis.delete(TOKEN_KEY_PREFIX + ticket.token());
        // The ticket was already consumed by the refused attempt — no blind retry.
        ServiceException burned = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, ticket.token()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, burned.getCode());
    }

    @Test
    void foreignUserCannotOpenTheCaseWriteDoor() {
        long draftId = landCaseWriteDraft(0L, 0, CREATE_PAYLOAD);
        long stranger = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag + "x", tag + "x");
        try {
            ServiceException issue = assertThrows(ServiceException.class,
                    () -> confirmationService.issueConfirmToken(stranger, draftId));
            assertEquals(BizErrorCode.FORBIDDEN, issue.getCode());

            DraftTicket owner = confirmationService.issueConfirmToken(userId, draftId);
            ServiceException apply = assertThrows(ServiceException.class,
                    () -> confirmationService.apply(stranger, draftId, owner.token()));
            assertEquals(BizErrorCode.FORBIDDEN, apply.getCode());
            assertEquals(1, draftStatus(draftId));
            Integer cases = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_test_case WHERE project_id = ?", Integer.class, projectId);
            assertNotNull(cases);
            assertEquals(0, cases.intValue(), "a refused door creates no case");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", stranger);
        }
    }

    @Test
    void crossProjectTargetCaseIsRefusedByTheAttributionGuard() {
        long sideProject = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag + "p2", tag + "p2");
        sideProjectIds.add(sideProject);
        CaseInfo foreign = caseService.create(userId, new CaseCreateInput(sideProject,
                "case in another project", "pre", "exp", 1, 2, "web", null, 2, 1, null, List.of()));
        createdCaseIds.add(foreign.id());

        long draftId = landCaseWriteDraft(foreign.id(), foreign.version(),
                "{\"title\":\"hijack attempt\"}");
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        ServiceException refused = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, ticket.token()));
        assertEquals(BizErrorCode.FORBIDDEN, refused.getCode());
        assertEquals("case in another project", caseService.get(foreign.id()).title(),
                "the session cannot write outside its own project slice");
    }

    @Test
    void caseWriteFrameWithoutProposalBodySkipsTheLanding() {
        long message = frameService.openMessage(sessionId, "assistant", null);
        try {
            frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                    "empty body", objectMapper.writeValueAsString(Map.of(
                            "objectType", "CASE_WRITE", "caseId", 0, "baseVersion", 0)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE message_id = ?",
                Integer.class, message);
        assertNotNull(rows);
        assertEquals(0, rows.intValue(), "a snapshot with no body is not confirmable");
    }

    @Test
    void updateAnchorWithoutBaseVersionSkipsTheLanding() {
        long caseId = seedCase("anchor check");
        // V330 semantics: with the server-side anchor switch off the old
        // half-shape contract holds (an edit echo missing its optimistic
        // anchor can never become confirmable). The factory default is on,
        // where the anchor is re-read live at landing — pinned here to the
        // documented off state to keep the refusal contract observable.
        java.util.List<String> prior = jdbcTemplate.queryForList(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, "ai.chat.draft-anchor-server-side.enabled");
        try {
            jdbcTemplate.update("""
                    INSERT INTO tp_sys_config (config_key, config_value, description, group_name)
                    VALUES ('ai.chat.draft-anchor-server-side.enabled', '0', 'test-pinned', 'ai')
                    ON CONFLICT (config_key) DO UPDATE SET config_value = '0'
                    """);
            long message = frameService.openMessage(sessionId, "assistant", null);
            try {
                frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                        "no anchor", objectMapper.writeValueAsString(Map.of(
                                "objectType", "CASE_WRITE", "caseId", caseId,
                                "actionJson", "{\"title\":\"x\"}")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_ai_chat_draft WHERE message_id = ?",
                    Integer.class, message);
            assertNotNull(rows);
            assertEquals(0, rows.intValue(),
                    "an update proposal missing its optimistic anchor cannot become confirmable");
        } finally {
            if (prior.isEmpty()) {
                jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?",
                        "ai.chat.draft-anchor-server-side.enabled");
            } else {
                jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?",
                        prior.get(0), "ai.chat.draft-anchor-server-side.enabled");
            }
        }
    }
}
