package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-side edit-anchor re-reading extended from
 * the case/requirement kinds to the mock-stub and defect write kinds.
 * <p>
 * When a draft card proposes an EDIT of an existing row, the model echoes a
 * base_version that may already be stale. With the V330 hot-reload key on
 * (factory default) the landing re-reads the live row version and overwrites
 * the echo, so the human's one-click confirm applies against the current row
 * instead of being refused by the echo's age. With the key pinned off the
 * echo is kept exactly as emitted and an anchorless edit proposal never
 * becomes confirmable. This suite pins the landing ledger only — the write
 * door itself (issue token + apply) is exercised by each kind's own flow
 * suite, and nothing here mocks the database.
 */
@SpringBootTest(classes = CoreApplication.class)
class EditAnchorServerSideCoverageTest {

    private static final String ANCHOR_KEY = "ai.chat.draft-anchor-server-side.enabled";
    private static final String STUB_EDIT_PAYLOAD = "{\"name\":\"renamed by AI\"}";
    private static final String DEFECT_EDIT_PAYLOAD = "{\"title\":\"renamed defect\"}";
    private static final String TAG_RENAME_PAYLOAD = "{\"name\":\"renamed tag\"}";
    private static final String FACTORY_EDIT_PAYLOAD = "{\"name\":\"renamed factory\"}";
    private static final String SKILL_EDIT_PAYLOAD = "{\"name\":\"renamed skill\"}";
    private static final String FLAKY_PAYLOAD = "{\"reason\":\"flaky pattern observed\"}";


    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService mockStubService;

    @Autowired
    private DefectLibraryService defectLibraryService;

    @Autowired
    private TagService tagService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerSessionSeed seed;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tag;
    private long projectId;
    private long userId;
    private long sessionId;
    private String anchorBefore;
    private final java.util.List<Long> spaceIds = new java.util.ArrayList<>();
    private final java.util.List<Long> stubIds = new java.util.ArrayList<>();
    private final java.util.List<Long> defectIds = new java.util.ArrayList<>();
    private final java.util.List<Long> tagIds = new java.util.ArrayList<>();
    private final java.util.List<Long> caseIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        // Scope seeding is single-sourced (R40) in LedgerSessionSeed.
        LedgerSessionSeed.Scope scope = seed.open("t485e", "Edit anchor coverage",
                "{\"route\":\"/mock\"}");
        userId = scope.userId();
        projectId = scope.projectId();
        sessionId = scope.sessionId();
        tag = scope.tag();
        anchorBefore = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, ANCHOR_KEY);
    }

    @AfterEach
    void tearDown() {
        // Restore the factory value this suite may have pinned during a test;
        // the shared baseline row must never leave this suite mutated (R37).
        if (anchorBefore != null) {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?",
                    anchorBefore, ANCHOR_KEY);
        }
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        for (long stubId : stubIds) {
            jdbcTemplate.update("DELETE FROM tp_mock_stub WHERE id = ?", stubId);
        }
        for (long spaceId : spaceIds) {
            jdbcTemplate.update("DELETE FROM tp_mock_space WHERE id = ?", spaceId);
        }
        for (long defectId : defectIds) {
            jdbcTemplate.update("DELETE FROM tp_defect WHERE id = ?", defectId);
        }
        for (long tagId : tagIds) {
            jdbcTemplate.update("DELETE FROM tp_tag_dict WHERE id = ?", tagId);
        }
        for (long caseId : caseIds) {
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        }
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    /** Pin the V330 switch for one test (hot-reload read at landing, no restart). */
    private void pinAnchorSwitch(String value) {
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?",
                value, ANCHOR_KEY);
    }

    /**
     * Land a DRAFT frame through the real frame ledger and return the draft
     * row id, or -1 when the landing was honestly skipped.
     */
    private long landDraftFrame(String objectType, long targetId, int baseVersion, String payload) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("objectType", objectType);
        meta.put("targetId", targetId);
        meta.put("baseVersion", baseVersion);
        meta.put("model", "test-model");
        meta.put("actionJson", payload);
        long message = frameService.openMessage(sessionId, "assistant", null);
        try {
            frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                    "edit anchor coverage proposal", objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_ai_chat_draft WHERE message_id = ?", Long.class, message);
        return ids.isEmpty() ? -1L : ids.get(0);
    }

    private int draftBaseVersion(long draftId) {
        Integer base = jdbcTemplate.queryForObject(
                "SELECT base_version FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertNotNull(base);
        return base;
    }

    /** Raw base_version of a draft row (null = the card pins no anchor). */
    private Integer baseVersionOrNull(long draftId) {
        return jdbcTemplate.queryForObject(
                "SELECT base_version FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
    }

    /** A create card pins no optimistic anchor: the ledger keeps NULL or 0. */
    private void assertNoAnchorPinned(long draftId, String label) {
        Integer base = baseVersionOrNull(draftId);
        assertTrue(base == null || base == 0, label + " must pin no anchor, got " + base);
    }

    private long seedStub() {
        String key = "anchor-" + tag + "-" + spaceIds.size();
        long spaceId = spaceService.create(projectId, new MockSpaceInput(
                "Anchor space " + key, key, null, null, "prefix", "none", null, null, 100, null));
        spaceIds.add(spaceId);
        long stubId = mockStubService.create(spaceId, new MockStubInput(
                "anchor stub", 100, "GET", "prefix", "/anchor", null,
                "{\"status\":200}", null, null, null, "manual", null, true));
        stubIds.add(stubId);
        return stubId;
    }

    private long seedDefect() {
        DefectLibraryInfo created = defectLibraryService.create(userId, new DefectCreateInput(
                projectId, null, null, "anchor defect", "seeded by the coverage suite",
                2, null, null, null));
        defectIds.add(created.id());
        return created.id();
    }

    /** AM batch seeds: tag row (V439 version ledger) and case row. */
    private long seedTag() {
        long tagId = tagService.create(userId,
                new TagCreateInput(projectId, "anchor tag " + tagIds.size() + " " + tag, 4)).id();
        tagIds.add(tagId);
        return tagId;
    }

    private long seedCase() {
        CaseInfo created = caseService.create(userId, new CaseCreateInput(projectId,
                "anchor case " + caseIds.size() + " " + tag, "pre", "expect", 1, 2, "web",
                null, 2, 1, null, List.of()));
        caseIds.add(created.id());
        return created.id();
    }

    /** Raw version column of a tag row (the same value the door re-anchors onto). */
    private int tagLiveVersion(long tagId) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_tag_dict WHERE id = ?", Integer.class, tagId);
        assertNotNull(version);
        return version;
    }

    @Test
    void staleEchoOnStubEditIsReanchoredToTheLiveVersion() {
        long stubId = seedStub();
        int liveVersion = mockStubService.get(stubId).version();
        assertNotNull(liveVersion, "a stub row carries an optimistic version (V240)");
        long draftId = landDraftFrame("MOCK_STUB_WRITE", stubId, liveVersion + 97, STUB_EDIT_PAYLOAD);
        assertTrue(draftId > 0, "a stub edit proposal becomes confirmable");
        assertEquals(liveVersion, draftBaseVersion(draftId),
                "the stale echo must be overwritten by the live row version at landing");
    }

    @Test
    void staleEchoOnDefectEditIsReanchoredToTheLiveVersion() {
        long defectId = seedDefect();
        int liveVersion = defectLibraryService.get(defectId).version();
        long draftId = landDraftFrame("DEFECT_WRITE", defectId, liveVersion + 97, DEFECT_EDIT_PAYLOAD);
        assertTrue(draftId > 0, "a defect edit proposal becomes confirmable");
        assertEquals(liveVersion, draftBaseVersion(draftId),
                "the stale echo must be overwritten by the live row version at landing");
    }

    @Test
    void createShapeOfTheNewKindsLandsWithoutAnyAnchor() {
        // targetId 0 = proposal to create: no row to re-anchor, the landing
        // must stay exactly as permissive as it was before this segment, and
        // the ledger keeps an honest "no anchor" NULL on the draft row.
        long draftId = landDraftFrame("MOCK_STUB_WRITE", 0L, 0, STUB_EDIT_PAYLOAD);
        assertTrue(draftId > 0, "a create proposal without an anchor still lands pending");
        assertNoAnchorPinned(draftId, "stub create");
        long defectDraft = landDraftFrame("DEFECT_WRITE", 0L, 0, DEFECT_EDIT_PAYLOAD);
        assertTrue(defectDraft > 0, "a defect create proposal without an anchor still lands pending");
        assertNoAnchorPinned(defectDraft, "defect create");
    }

    @Test
    void switchOffKeepsTheEchoAndRefusesAnchorlessEdit() {
        pinAnchorSwitch("0");
        try {
            long stubId = seedStub();
            long kept = landDraftFrame("MOCK_STUB_WRITE", stubId, 7, STUB_EDIT_PAYLOAD);
            assertTrue(kept > 0, "with the switch off an echoed anchor still lands as emitted");
            assertEquals(7, draftBaseVersion(kept), "the echo must be preserved exactly (old behavior)");

            long defectId = seedDefect();
            long refused = landDraftFrame("DEFECT_WRITE", defectId, 0, DEFECT_EDIT_PAYLOAD);
            assertEquals(-1L, refused,
                    "with the switch off an edit without an echoed anchor never becomes confirmable");
        } finally {
            pinAnchorSwitch("1");
        }
    }

    @Test
    void anchorOnAVanishedRowSkipsTheLandingHonestly() {
        // Server-side re-anchor reads the live row; a target that no longer
        // exists cannot be anchored, so the card honestly stays absent.
        long draftId = landDraftFrame("MOCK_STUB_WRITE", 999_999_997L, 1, STUB_EDIT_PAYLOAD);
        assertEquals(-1L, draftId, "an edit aimed at a gone stub row is skipped at landing");
        long defectDraft = landDraftFrame("DEFECT_WRITE", 999_999_998L, 1, DEFECT_EDIT_PAYLOAD);
        assertEquals(-1L, defectDraft, "an edit aimed at a gone defect row is skipped at landing");
    }

    @Test
    void staleEchoOnTagRenameIsReanchoredToTheLiveVersion() {
        long tagId = seedTag();
        int liveVersion = tagLiveVersion(tagId);
        long draftId = landDraftFrame("TAG_WRITE", tagId, liveVersion + 97, TAG_RENAME_PAYLOAD);
        assertTrue(draftId > 0, "a tag rename proposal becomes confirmable");
        assertEquals(liveVersion, draftBaseVersion(draftId),
                "the stale echo must be overwritten by the live tag-dict version at landing");
    }

    @Test
    void staleEchoOnFlakyIsolationIsReanchoredToTheLiveCaseVersion() {
        long caseId = seedCase();
        int liveVersion = caseService.get(caseId).version();
        long draftId = landDraftFrame("FLAKY_ISOLATION", caseId, liveVersion + 97, FLAKY_PAYLOAD);
        assertTrue(draftId > 0, "a flaky-quarantine proposal becomes confirmable");
        assertEquals(liveVersion, draftBaseVersion(draftId),
                "the quarantine card anchors on the live case version, not the echoed one");
    }

    @Test
    void anchorOnAVanishedFactoryOrSkillRowSkipsTheLandingHonestly() {
        long factoryDraft = landDraftFrame("DATA_FACTORY_WRITE", 999_999_991L, 1, FACTORY_EDIT_PAYLOAD);
        assertEquals(-1L, factoryDraft, "an edit aimed at a gone factory row is skipped at landing");
        long skillDraft = landDraftFrame("SKILL_WRITE", 999_999_992L, 1, SKILL_EDIT_PAYLOAD);
        assertEquals(-1L, skillDraft, "an edit aimed at a gone skill row is skipped at landing");
    }

    @Test
    void createShapesOfTheSliceThreeKindsStillLandWithoutAnchor() {
        // targetId 0 = create proposal: no row to re-anchor, so the landing
        // stays exactly as permissive as before this segment for these kinds.
        long tagDraft = landDraftFrame("TAG_WRITE", 0L, 0, TAG_RENAME_PAYLOAD);
        assertTrue(tagDraft > 0, "a tag create proposal without an anchor still lands pending");
        assertNoAnchorPinned(tagDraft, "tag create");
        long factoryDraft = landDraftFrame("DATA_FACTORY_WRITE", 0L, 0, FACTORY_EDIT_PAYLOAD);
        assertTrue(factoryDraft > 0, "a factory create proposal without an anchor still lands pending");
        assertNoAnchorPinned(factoryDraft, "factory create");
        long skillDraft = landDraftFrame("SKILL_WRITE", 0L, 0, SKILL_EDIT_PAYLOAD);
        assertTrue(skillDraft > 0, "a skill create proposal without an anchor still lands pending");
        assertNoAnchorPinned(skillDraft, "skill create");
    }

    @Test
    void switchOffKeepsTagEchoAndRefusesAnchorlessFlakyEdit() {
        pinAnchorSwitch("0");
        try {
            long tagId = seedTag();
            long kept = landDraftFrame("TAG_WRITE", tagId, 7, TAG_RENAME_PAYLOAD);
            assertTrue(kept > 0, "with the switch off an echoed tag anchor still lands as emitted");
            assertEquals(7, draftBaseVersion(kept), "the echo must be preserved exactly (old behavior)");

            long caseId = seedCase();
            long refused = landDraftFrame("FLAKY_ISOLATION", caseId, 0, FLAKY_PAYLOAD);
            assertEquals(-1L, refused,
                    "with the switch off an anchorless quarantine card never becomes confirmable");
        } finally {
            pinAnchorSwitch("1");
        }
    }
}
