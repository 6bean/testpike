package com.sixbean.testpike.core.aichat.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.support.RequirementDraftTestRig;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Card-time duplicate burn-in for requirement draft cards (AL batch
 * , design ruling A7 + adjudication item 10a): the shared DedupGate
 * verdict is burned into the card when the draft row is journaled, so the
 * human sees the two-choice form BEFORE confirming — never a zero-click
 * auto-apply (red line 10c untouched).
 *
 * <p>Observable contract asserted here:
 * <ul>
 * <li>a create proposal whose normalized title hits an existing row is
 * journaled as the dual-payload form the landing strategy already
 * speaks ({@code {"create":…,"update":…}}), the update branch
 * pre-installed with the existing row's id and live version anchor,
 * and the card summary names the hit;</li>
 * <li>a create proposal with no known hit keeps the single create payload
 * exactly as emitted (the write-instant TOCTOU re-check inside the
 * shared chain still guards the quiet window — covered by the
 * dual-action suite);</li>
 * <li>the gate switch off = no burn-in (hot-key honored, honest absence);</li>
 * <li>the woven card lands: choosing "update" writes the proposal fields
 * onto the hit row instead of filing a duplicate.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementDraftCardDedupWeavingTest {

    private static final String EXISTING_TITLE = "支付网关超时自动回退策略";
    private static final String UNIQUE_TITLE = "离线包裹清单导出功能";
    private static final String DRAFT_CRITERIA = "草案验收口径";

    private final ObjectMapper reader = new ObjectMapper();

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private LedgerSessionSeed seed;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private RequirementDraftTestRig rig;

    @BeforeEach
    void setUp() {
        rig = RequirementDraftTestRig.of(sessionService, frameService, requirementService,
                jdbcTemplate, seed);
        rig.openSession("t483w", "Card weaving");
        // The gate's factory default is on; pin the hot key THROUGH THE CACHE
        // (the single read source of SystemConfigReader, same channel as the
        // gate suite's own fixture) so a stale row value never decides what
        // this class proves.
        pinDedupEnabled(true);
    }

    @AfterEach
    void tearDown() {
        pinDedupEnabled(true);
        rig.closeSession();
    }

    private void pinDedupEnabled(boolean enabled) {
        configCacheHolder.put("ai.dedup.enabled", enabled ? "1" : "0");
    }

    @Test
    void knownHitAtCardTimeWeavesTheDualBranchIntoTheDraftRow() throws Exception {
        long hitId = rig.seedRequirement(EXISTING_TITLE);
        // Full-width ideographic space + trailing blank: the normalized key
        // (NFKC fold, whitespace collapse) must fire on it — the very gap the
        // legacy raw-title comparison fell through.
        String body = "{\"title\":\"" + EXISTING_TITLE + "　\",\"acceptanceCriteria\":\""
                + DRAFT_CRITERIA + "\",\"chapter\":\"2.1\"}";

        long draftId = rig.landRequirementDraft(0L, 0, body);
        JsonNode row = readDraftPayload(draftId);
        String summary = readDraftSummary(draftId);

        assertTrue(row.hasNonNull("create"), "known hit must weave a create branch");
        assertTrue(row.hasNonNull("update"), "known hit must weave an anchored update branch");
        JsonNode update = row.get("update");
        assertEquals(hitId, update.path("targetId").asLong(),
                "the update branch points at the requirement the gate hit");
        assertEquals(1, update.path("baseVersion").asInt(),
                "the update branch carries the live version anchor of the hit row");
        assertEquals(DRAFT_CRITERIA, update.path("acceptanceCriteria").asText(),
                "the update branch pre-installs the proposed fields");
        assertEquals(EXISTING_TITLE + "　", row.get("create").path("title").asText(),
                "the create branch keeps the proposal body untouched");
        assertNull(readDraftTargetId(draftId),
                "the ledger row stays create-kind until one branch lands");
        assertTrue(summary.contains("#" + hitId), "the card names the hit by id");
        assertTrue(summary.contains("接受新建") && summary.contains("接受变更"),
                "the card states the two one-click choices: " + summary);
    }

    @Test
    void unknownTitleKeepsTheSingleCreateShapeExactlyAsEmitted() throws Exception {
        rig.seedRequirement(EXISTING_TITLE);
        String body = "{\"title\":\"" + UNIQUE_TITLE + "\",\"acceptanceCriteria\":\"无关验收\","
                + "\"chapter\":\"3.2\"}";

        long draftId = rig.landRequirementDraft(0L, 0, body);
        JsonNode row = readDraftPayload(draftId);

        assertEquals(UNIQUE_TITLE, row.path("title").asText(),
                "no known hit = the proposal body lands as-is, unwoven");
        assertTrue(row.isMissingNode() || row.get("create") == null,
                "no known hit must not mint a dual payload");
    }

    @Test
    void gateSwitchOffNeverWeavesTheCard() throws Exception {
        pinDedupEnabled(false);
        try {
            rig.seedRequirement(EXISTING_TITLE);
            String body = "{\"title\":\"" + EXISTING_TITLE + "\",\"acceptanceCriteria\":\"照旧\","
                    + "\"chapter\":\"4.0\"}";

            long draftId = rig.landRequirementDraft(0L, 0, body);
            JsonNode row = readDraftPayload(draftId);

            assertEquals(EXISTING_TITLE, row.path("title").asText(),
                    "hot switch off = honest absence of the burn-in, single shape kept");
        } finally {
            pinDedupEnabled(true);
        }
    }

    @Test
    void wovenCardLandsTheUpdateBranchOnTheHitInsteadOfADuplicate() throws Exception {
        long hitId = rig.seedRequirement(EXISTING_TITLE);
        String body = "{\"title\":\"" + EXISTING_TITLE + "\",\"acceptanceCriteria\":\""
                + DRAFT_CRITERIA + "\",\"chapter\":\"2.1\"}";
        long draftId = rig.landRequirementDraft(0L, 0, body);

        DraftTicket ticket = confirmationService.issueConfirmToken(rig.userId(), draftId);
        confirmationService.apply(rig.userId(), draftId, ticket.token(), "update");

        RequirementInfo landed = requirementService.get(hitId);
        assertEquals(2, landed.version(), "accept-change writes onto the hit row's version chain");
        assertEquals(DRAFT_CRITERIA, landed.acceptanceCriteria(),
                "the proposal fields landed on the existing requirement");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id = ? AND deleted = 0",
                Integer.class, rig.projectId());
        assertEquals(1, rows, "the two-choice card must not file a second requirement row");
        assertNotNull(landed);
    }

    private JsonNode readDraftPayload(long draftId) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT payload_json::text FROM tp_ai_chat_draft WHERE id = ?", String.class, draftId);
        return reader.readTree(json);
    }

    private String readDraftSummary(long draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT summary FROM tp_ai_chat_draft WHERE id = ?", String.class, draftId);
    }

    private Long readDraftTargetId(long draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT target_id FROM tp_ai_chat_draft WHERE id = ?", Long.class, draftId);
    }
}
