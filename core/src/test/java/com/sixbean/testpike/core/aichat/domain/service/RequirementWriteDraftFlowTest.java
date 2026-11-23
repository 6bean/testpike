package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.support.RequirementDraftTestRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement-write drafts behind the generic confirmation door (AL batch
 * ): an AI proposal to create or edit a requirement point lands through
 * the SAME RequirementService chain the human UI uses — optimistic anchor,
 * version snapshots and audit come free, never a parallel write path.
 * <p>The spectrum pins on the real dev database, zero mocks. Session
 * fixtures, frame landing, seeding and config pinning come from the shared
 * {@link RequirementDraftTestRig} so the dual-action suite never re-cuts
 * the same scaffolding:
 * <ul>
 * <li>create-kind draft confirm → requirement really exists in the
 * session's project, receipt points at the new id, the version ledger
 * carries the create snapshot;</li>
 * <li>edit-kind draft confirm → field-level update lands with the version
 * bump and its snapshot;</li>
 * <li>server-side anchor (V330 switch on): a draft whose echoed anchor is
 * stale gets re-anchored at landing time and still applies against the
 * current version;</li>
 * <li>switch off = old behavior: the stale echo refuses with the
 * plain-word version conflict;</li>
 * <li>attribution guard: a draft aimed at a requirement from another
 * project than the session's is refused before any write;</li>
 * <li>anchor contract: a REQUIREMENT_WRITE frame without a proposal body
 * skips the landing honestly.</li>
 * </ul>
 * Same posture as CaseWriteDraftFlowTest.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementWriteDraftFlowTest {

    private static final String ANCHOR_SWITCH_KEY = "ai.chat.draft-anchor-server-side.enabled";

    private static final String CREATE_PAYLOAD =
            "{\"title\":\"AI drafted requirement\",\"acceptanceCriteria\":\"login within 3s\","
                    + "\"chapter\":\"2.1\"}";

    private static final String EDIT_PAYLOAD =
            "{\"title\":\"AI edited requirement\",\"acceptanceCriteria\":\"edited criteria\","
                    + "\"chapter\":null}";

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerSessionSeed seed;

    private RequirementDraftTestRig rig;

    @BeforeEach
    void setUp() {
        rig = RequirementDraftTestRig.of(sessionService, frameService, requirementService,
                jdbcTemplate, seed);
        rig.openSession("t477w", "Requirement write");
    }

    @AfterEach
    void tearDown() {
        rig.closeSession();
    }

    private int draftStatus(long draftId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertNotNull(status);
        return status;
    }

    private Integer readBaseVersion(long draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT base_version FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
    }

    private void bumpRequirementExternally(long requirementId) {
        RequirementInfo current = requirementService.get(requirementId);
        requirementService.update(rig.userId(), requirementId, new RequirementUpdateInput(
                "human bump", null, null, null, null, null, current.version()));
    }

    /** Pin the anchor switch to an explicit value for one test, restore it after. */
    private void setAnchorSwitch(boolean on) {
        rig.pinConfig(ANCHOR_SWITCH_KEY, on ? "1" : "0");
    }

    @Test
    void confirmedCreateDraftLandsARealRequirementWithCreateSnapshot() {
        long draftId = rig.landRequirementDraft(0L, 0, CREATE_PAYLOAD);
        assertEquals("REQUIREMENT_WRITE", jdbcTemplate.queryForObject(
                "SELECT object_type FROM tp_ai_chat_draft WHERE id = ?", String.class, draftId));
        assertEquals(1, draftStatus(draftId), "a create proposal waits behind the door");

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        DraftApplyResult result = confirmationService.apply(rig.userId(), draftId, ticket.token());

        assertNotNull(result.targetId(), "the receipt must name the requirement that was created");
        rig.trackRequirement(result.targetId());
        RequirementInfo live = requirementService.get(result.targetId());
        assertEquals("AI drafted requirement", live.title());
        assertEquals(rig.projectId(), live.projectId(), "the requirement lands in the session's project");
        assertEquals(3, live.source(), "the dialog is a manual source — never the Jira source");
        assertEquals(1, live.version(), "a fresh requirement is at version 1");
        assertEquals(1, result.appliedVersion().intValue());
        Integer snapshots = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement_version WHERE requirement_id = ?",
                Integer.class, result.targetId());
        assertEquals(1, snapshots.intValue(), "the create snapshot rides the version ledger");
        assertEquals(2, draftStatus(draftId), "the draft is consumed after landing");
    }

    @Test
    void confirmedEditDraftLandsFieldUpdateWithVersionBumpAndSnapshot() {
        long requirementId = rig.seedRequirement("seed for edit");
        long draftId = rig.landRequirementDraft(requirementId, 1, EDIT_PAYLOAD);
        assertEquals(1, draftStatus(draftId));

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        DraftApplyResult result = confirmationService.apply(rig.userId(), draftId, ticket.token());

        RequirementInfo live = requirementService.get(requirementId);
        assertEquals("AI edited requirement", live.title());
        assertEquals("edited criteria", live.acceptanceCriteria());
        assertEquals("1.0", live.chapter(), "null chapter in the payload leaves the field untouched");
        assertEquals(2, live.version(), "the edit bumps the optimistic version");
        assertEquals(2, result.appliedVersion().intValue());
        Integer snapshots = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement_version WHERE requirement_id = ?",
                Integer.class, requirementId);
        assertEquals(2, snapshots.intValue(), "edit snapshots too (create + edit)");
    }

    @Test
    void serverSideAnchorReLandsAStaleDraftAgainstTheCurrentVersion() {
        setAnchorSwitch(true);
        long requirementId = rig.seedRequirement("seed for re-anchor");
        // A human bumps the row to v2 BEFORE the dialog frame arrives; the
        // emitter still echoes the stale version 1 it saw in its context.
        bumpRequirementExternally(requirementId);
        long draftId = rig.landRequirementDraft(requirementId, 1, EDIT_PAYLOAD);
        // Landing-time server anchor rewrote the ledger row onto the current version.
        assertEquals(2, readBaseVersion(draftId).intValue(),
                "the server re-anchored the stale echo to the current version");

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        DraftApplyResult result = confirmationService.apply(rig.userId(), draftId, ticket.token());

        RequirementInfo live = requirementService.get(requirementId);
        assertEquals("AI edited requirement", live.title(), "the re-anchored draft still lands");
        assertEquals(3, live.version(), "human bump v2 + draft edit v3");
        assertEquals(3, result.appliedVersion().intValue());
    }

    @Test
    void anchorSwitchOffKeepsTheOldBehaviorAndRefusesTheStaleEcho() {
        setAnchorSwitch(false);
        long requirementId = rig.seedRequirement("seed for stale refusal");
        bumpRequirementExternally(requirementId);
        long draftId = rig.landRequirementDraft(requirementId, 1, EDIT_PAYLOAD);
        assertEquals(1, readBaseVersion(draftId).intValue(),
                "with the switch off the echoed anchor is kept exactly as emitted");

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        // Assertion corrected with source ( revision-merge gate landed
        // first on this kind): an intersecting concurrent edit is refused by
        // the GATE — naming the changed field and the single-key way out —
        // before the shared chain's optimistic lock ever sees the stale echo.
        // The V330 switch only governs the card-time anchor; refusal semantics
        // on a true conflict belong to the gate either way.
        ServiceException failure = assertThrows(ServiceException.class,
                () -> confirmationService.apply(rig.userId(), draftId, ticket.token()));
        assertTrue(failure.getMessage().contains("标题已被他人修改")
                        && failure.getMessage().contains("以本次为准"),
                "the refusal names the changed field and the single-key way out");
        assertTrue(failure.getMessage().contains("当前版本 2")
                        && failure.getMessage().contains("草案基于版本 1"),
                "the refusal carries the current version and the draft baseline");
        RequirementInfo untouched = requirementService.get(requirementId);
        assertEquals("human bump", untouched.title(), "the refused draft writes nothing");
    }

    @Test
    void editDraftAimedAtAnotherProjectIsRefusedBeforeAnyWrite() {
        long sideProjectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, rig.tag() + "-side", rig.tag() + "-side");
        rig.trackSideProject(sideProjectId);
        RequirementInfo foreign = requirementService.create(rig.userId(),
                new RequirementCreateInput(sideProjectId, 3, null, "foreign requirement", null, null));
        rig.trackRequirement(foreign.id());

        long draftId = rig.landRequirementDraft(foreign.id(), foreign.version(), EDIT_PAYLOAD);
        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        ServiceException failure = assertThrows(ServiceException.class,
                () -> confirmationService.apply(rig.userId(), draftId, ticket.token()));
        assertEquals(BizErrorCode.FORBIDDEN, failure.getCode().intValue());
        RequirementInfo untouched = requirementService.get(foreign.id());
        assertEquals("foreign requirement", untouched.title());
        assertEquals(1, untouched.version(), "the refusal happened before the shared chain ran");
    }

    @Test
    void frameWithoutProposalBodySkipsLandingHonestly() {
        long message = rig.landDraftWithoutProposal(0L, 0);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE message_id = ?", Integer.class, message);
        assertEquals(0, rows.intValue(), "a half-trusted snapshot never becomes confirmable");
        assertTrue(rig.createdRequirementIds().isEmpty());
    }

    @Test
    void cardTimeWeavingBurnsTheDualFormOntoTheLiveFrameMeta() {
        rig.pinConfig("ai.dedup.enabled", "1");
        long hit = rig.seedRequirement("AI drafted requirement");
        rig.trackRequirement(hit);
        long draftId = rig.landRequirementDraft(0L, 0, CREATE_PAYLOAD);

        String ledger = jdbcTemplate.queryForObject(
                "SELECT payload_json::text FROM tp_ai_chat_draft WHERE id = ?", String.class, draftId);
        assertNotNull(ledger);
        assertTrue(ledger.contains("\"create\"") && ledger.contains("\"update\""),
                "the draft ledger must carry the woven two-choice payload");

        // The live card renders from the frame row (single source), so the same
        // verdict rides that frame's meta side-channel, never only the ledger.
        String frameAction = jdbcTemplate.queryForObject(
                "SELECT meta_json ->> 'actionJson' FROM tp_ai_chat_frame "
                        + "WHERE session_id = ? ORDER BY seq DESC LIMIT 1", String.class, rig.sessionId());
        assertNotNull(frameAction, "the landed frame keeps its meta side-channel");
        assertTrue(frameAction.contains("\"create\"") && frameAction.contains("\"update\""),
                "the live frame meta must carry the woven dual form, not the raw single payload");
        assertTrue(frameAction.contains(String.valueOf(hit)),
                "the update branch names the duplicated row it anchors on");
    }
}
