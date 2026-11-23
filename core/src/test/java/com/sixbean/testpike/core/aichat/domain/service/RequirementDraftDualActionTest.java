package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
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
 * Dual-action confirmation cards for requirement drafts (AL batch
 * package 1): the card carries BOTH a create and an update payload and the
 * human picks one at confirm time — never zero-click auto-apply (red line),
 * never a silent guess by the door.
 * <p>Spectrum, pinned on the real dev database, zero mocks. Session
 * fixtures, frame landing, seeding and config pinning come from the shared
 * {@link RequirementDraftTestRig}:
 * <ul>
 * <li>a dual-payload draft refuses confirmation without an explicit
 * either-or choice (40001, nothing lands, draft stays pending);</li>
 * <li>choosing the change branch lands the anchored update on the existing
 * requirement — field edit, version bump, receipt names the target;</li>
 * <li>choosing create against a KNOWN hit (the card already showed the
 * duplicate) is respected as a human override: the row is created and
 * the DedupGate's single-source force-new audit trail records it;</li>
 * <li>a duplicate that only appeared between card time and write time
 * (TOCTOU) refuses the insert and names the existing requirement in
 * plain words — nothing lands.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementDraftDualActionTest {

    private static final String DEDUP_ENABLED_KEY = "ai.dedup.enabled";
    private static final String CREATE_TITLE = "AI drafted login flow";
    private static final String UPDATE_TITLE = "AI revised login flow";

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private DraftConfirmationService confirmationService;

    private RequirementDraftTestRig rig;

    @Autowired
    private LedgerSessionSeed seed;

    @BeforeEach
    void setUp() {
        rig = RequirementDraftTestRig.of(sessionService, frameService, requirementService,
                jdbcTemplate, seed);
        rig.openSession("t483d", "Dual action");
        // The gate's factory default is on; pin it so a stale hot-key value
        // left by another run can never silence the spectra under test.
        rig.pinConfig(DEDUP_ENABLED_KEY, "1");
    }

    @AfterEach
    void tearDown() {
        rig.closeSession();
    }

    /** Dual-payload body: one create branch + one anchored update branch. */
    private String dualPayload(long existingId, int existingVersion) {
        return "{\"create\":{\"title\":\"" + CREATE_TITLE + "\","
                + "\"acceptanceCriteria\":\"login within 3s\",\"chapter\":\"2.1\"},"
                + "\"update\":{\"targetId\":" + existingId + ",\"baseVersion\":" + existingVersion + ","
                + "\"title\":\"" + UPDATE_TITLE + "\",\"acceptanceCriteria\":\"revised criteria\"}}";
    }

    private int countRequirementsWithNormalizedTitle(String title) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id = ? AND deleted = 0 AND title = ?",
                Integer.class, rig.projectId(), title);
        assertNotNull(n);
        return n;
    }

    @Test
    void dualPayloadDraftRefusesConfirmationWithoutAnExplicitChoice() {
        long existingId = rig.seedRequirement(CREATE_TITLE);
        long draftId = rig.landRequirementDraft(0L, 0, dualPayload(existingId, 1));

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        ServiceException noChoice = assertThrows(ServiceException.class,
                () -> confirmationService.apply(rig.userId(), draftId, ticket.token()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noChoice.getCode().intValue(),
                "a dual-payload draft must not guess the human's choice");

        // An unknown selection is the same refusal: only create|update open the door.
        DraftTicket second = confirmationService.issueConfirmToken(rig.userId(), draftId);
        ServiceException bogus = assertThrows(ServiceException.class,
                () -> confirmationService.apply(rig.userId(), draftId, second.token(), "delete"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, bogus.getCode().intValue());

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertEquals(1, pending.intValue(), "refused confirmations leave the draft pending");
        assertEquals(CREATE_TITLE, requirementService.get(existingId).title(),
                "nothing landed against the existing requirement");
        assertEquals(1, countRequirementsWithNormalizedTitle(CREATE_TITLE));
    }

    @Test
    void changeCardChoiceLandsTheAnchoredUpdateBranch() {
        long existingId = rig.seedRequirement(CREATE_TITLE);
        long draftId = rig.landRequirementDraft(0L, 0, dualPayload(existingId, 1));

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        DraftApplyResult result =
                confirmationService.apply(rig.userId(), draftId, ticket.token(), "update");

        assertEquals(existingId, result.targetId().longValue(),
                "the receipt must name the requirement the change branch edited");
        RequirementInfo live = requirementService.get(existingId);
        assertEquals(UPDATE_TITLE, live.title());
        assertEquals("revised criteria", live.acceptanceCriteria());
        assertEquals(2, live.version(), "the change branch lands as a versioned edit");
        assertEquals(2, result.appliedVersion().intValue());
        Integer totalInProject = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id = ? AND deleted = 0",
                Integer.class, rig.projectId());
        assertEquals(1, totalInProject.intValue(),
                "the change choice edits the anchored row and creates no second row");
    }

    @Test
    void overrideCreateChoiceAgainstAKnownHitLandsWithForceNewAuditTrail() {
        long existingId = rig.seedRequirement(CREATE_TITLE);
        long draftId = rig.landRequirementDraft(0L, 0, dualPayload(existingId, 1));

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        DraftApplyResult result =
                confirmationService.apply(rig.userId(), draftId, ticket.token(), "create");

        assertNotNull(result.targetId(), "the human override lands a real new row");
        rig.trackRequirement(result.targetId());
        assertEquals(CREATE_TITLE, requirementService.get(result.targetId()).title());
        assertEquals(CREATE_TITLE, requirementService.get(existingId).title(),
                "the override never touches the row it duplicated");
        Integer audits = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_audit_log
                WHERE action = 'DEDUP_FORCE_NEW' AND object_type = 'requirement'
                  AND object_id = ? AND operator_id = ?
                """, Integer.class, String.valueOf(existingId), rig.userId());
        assertNotNull(audits);
        assertTrue(audits >= 1,
                "the override keeps its single-source audit trail with the blocked match named");
    }

    @Test
    void duplicateAppearingAfterCardTimeRefusesTheInsertAndNamesTheExisting() {
        long draftId = rig.landRequirementDraft(0L, 0,
                "{\"title\":\"" + CREATE_TITLE + "\",\"acceptanceCriteria\":\"login within 3s\"}");
        // The duplicate only shows up between card time and confirm time (TOCTOU).
        long existingId = rig.seedRequirement(CREATE_TITLE);

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        ServiceException refusal = assertThrows(ServiceException.class,
                () -> confirmationService.apply(rig.userId(), draftId, ticket.token()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, refusal.getCode().intValue(),
                "the shared requirement chain owns the duplicate refusal");
        assertTrue(refusal.getMessage().contains(String.valueOf(existingId))
                        && refusal.getMessage().contains(CREATE_TITLE),
                "the refusal names the existing requirement in plain words, never a bare conflict");
        assertEquals(1, countRequirementsWithNormalizedTitle(CREATE_TITLE),
                "the refused insert wrote nothing");
    }
}
