package com.sixbean.testpike.core.session.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.api.dto.CaptureSessionInput;
import com.sixbean.testpike.core.session.api.dto.CaptureSlotStageInput;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.service.CaptureSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 refusal contract for the capture-session door.
 *
 * <p>Every user-reachable refusal raised here must name the offending value
 * and carry a repair path the human can actually follow: a literal page or
 * dialog name in 「」 brackets, or a literal config key, plus an action word.
 * The static gate (tools/error_guidance_coverage_gate.py) scans the sentence
 * literals; this class pins the same contract through the real service so a
 * future rewrite cannot silently drop the pointer again.
 *
 * <p>Real dev DB + real Redis (SpringBootTest, no mocks — red line 3). Each
 * refusal branch is entered before any collaborator writes anything, so the
 * probes leave no rows behind; the transient slots are still removed in a
 * finally block (R57 double-sweep).
 */
@SpringBootTest(classes = CoreApplication.class)
class CaptureSessionGuidanceTest {

    @Autowired
    private CaptureSessionService captureSessionService;
    @Autowired
    private CaptureSessionStore captureSessionStore;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private static CaptureCommittedEntry cookie(String name, String value) {
        return new CaptureCommittedEntry("cookie", name, ".guidance-contract.example",
                value, null);
    }

    @Test
    void openWithoutInputPointsBackToTheLoginStateLedgerPage() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.open(900_000_901L, null));
        assertTrue(e.getMessage().contains("「登录态账本」"),
                "refusal names the literal page where a new capture starts: " + e.getMessage());
        assertTrue(e.getMessage().contains("请"),
                "refusal carries an action word: " + e.getMessage());
    }

    @Test
    void findOnUnknownSessionQuotesTheWindowKeyAndTheLedgerPage() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.find("capture-guidance-" + UUID.randomUUID()));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
        assertTrue(e.getMessage().contains("session.capture-timeout-minutes"),
                "the window parameter travels as a literal config key: " + e.getMessage());
        assertTrue(e.getMessage().contains("「登录态账本」"),
                "the repair entry is the literal page name: " + e.getMessage());
    }

    @Test
    void stageWithoutChannelRefusesWithTheDialogRepairEntry() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.stageScreenshotEntries(new CaptureSlotStageInput(
                        " ", 900_000_902L, List.of(cookie("sbtoken", "v")))));
        assertTrue(e.getMessage().contains("「AI 对话」"),
                "refusal points at the dialog that re-parses the screenshot: " + e.getMessage());
        assertTrue(e.getMessage().contains("请"), "action word present: " + e.getMessage());
    }

    @Test
    void stageWithoutOwningSessionNamesTheOffendingIdAndPointsToTheDialog() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 0L,
                        List.of(cookie("sbtoken", "v")))));
        assertTrue(e.getMessage().contains("「AI 对话」"),
                "refusal points at the dialog that re-parses the screenshot: " + e.getMessage());
        assertTrue(e.getMessage().contains("请"), "action word present: " + e.getMessage());
    }

    @Test
    void stageWithNoRowsGivesAConcreteRetakeExample() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 900_000_903L,
                        List.of())));
        assertTrue(e.getMessage().contains("「AI 对话」"),
                "repair entry named: " + e.getMessage());
        assertTrue(e.getMessage().contains("如"),
                "a concrete screenshot example rides with the refusal: " + e.getMessage());
    }

    @Test
    void stageOverCapExplainsSplittingTheScreenshotByPanel() {
        List<CaptureCommittedEntry> rows = new ArrayList<>();
        for (int i = 0; i < CaptureSlotStageInput.MAX_ENTRIES + 1; i++) {
            rows.add(cookie("k-" + i, "v-" + i));
        }
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 900_000_904L, rows)));
        assertTrue(e.getMessage().contains("「AI 对话」"), "repair entry named: " + e.getMessage());
        assertTrue(e.getMessage().contains("如"), "splitting example present: " + e.getMessage());
    }

    @Test
    void stageBlankNameRowNamesTheRowPosition() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 900_000_905L,
                        List.of(cookie("ok-1", "v"), cookie("  ", "v2")))));
        assertTrue(e.getMessage().contains("第 2 行"),
                "the offending row position is named verbatim: " + e.getMessage());
        assertTrue(e.getMessage().contains("「AI 对话」"), "repair entry named: " + e.getMessage());
    }

    @Test
    void stageDuplicatedNameQuotesTheNameAndDemandsUniqueness() {
        String dup = "sbtoken-" + UUID.randomUUID().toString().substring(0, 6);
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.stageScreenshotEntries(new CaptureSlotStageInput(
                        CaptureSlotStageInput.CHANNEL_CHAT_SCREENSHOT, 900_000_906L,
                        List.of(cookie(dup, "v1"), cookie(dup, "v2")))));
        assertTrue(e.getMessage().contains(dup),
                "the duplicated entry name is quoted back: " + e.getMessage());
        assertTrue(e.getMessage().contains("唯一"),
                "the repair instruction states what to change: " + e.getMessage());
        assertTrue(e.getMessage().contains("「AI 对话」"), "repair entry named: " + e.getMessage());
    }

    @Test
    void commitWithGhostSelectionPointsToTheDialogAndTheLedgerPage() throws Exception {
        String sessionId = seedLiveSession("guidance-ghost");
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> captureSessionService.commit(sessionId, List.of("no-such-entry")));
            assertTrue(e.getMessage().contains("「AI 对话」"),
                    "re-check entry named: " + e.getMessage());
            assertTrue(e.getMessage().contains("「登录态账本」"),
                    "fresh-capture entry named for the empty-list case: " + e.getMessage());
        } finally {
            captureSessionStore.remove(sessionId);
        }
    }

    @Test
    void commitOnElapsedSessionQuotesTheWindowKey() throws Exception {
        String sessionId = seedElapsedSession("guidance-elapsed");
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> captureSessionService.commit(sessionId, List.of("alive-entry")));
            assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
            assertTrue(e.getMessage().contains("session.capture-timeout-minutes"),
                    "the voiding window is explained by its literal config key: " + e.getMessage());
        } finally {
            captureSessionStore.remove(sessionId);
        }
    }

    @Test
    void openWithUnknownModeGivesTheCorrectWriteExamples() {
        CaptureSessionInput input = new CaptureSessionInput(
                "guidance-mode", List.of("https://guidance-contract.example"),
                "url-prefix", "https://guidance-contract.example/home",
                "http://127.0.0.1:9222", "bogus-mode");
        ServiceException e = assertThrows(ServiceException.class,
                () -> captureSessionService.open(900_000_907L, input));
        assertTrue(e.getMessage().contains("正确写法"),
                "the refusal teaches the accepted spellings: " + e.getMessage());
        assertTrue(e.getMessage().contains("请"), "action word present: " + e.getMessage());
    }

    /** Live slot carrying exactly one candidate row so the ghost check fires. */
    private String seedLiveSession(String tag) throws Exception {
        String sessionId = "capture-" + tag + "-" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", 900_000_900L);
        payload.put("stateName", tag);
        payload.put("scopeOrigins", List.of("https://guidance-contract.example"));
        payload.put("anchorKind", "url-prefix");
        payload.put("anchorValue", "https://guidance-contract.example/home");
        payload.put("mode", "local-direct");
        payload.put("status", "candidates-ready");
        payload.put("createdAtEpochSecond", System.currentTimeMillis() / 1000);
        captureSessionStore.save(sessionId, payload);
        stringRedisTemplate.opsForList().rightPush(
                CaptureSessionStore.candidatesKey(sessionId), candidateJson("alive-entry"));
        return sessionId;
    }

    /** Slot whose created-at stamp sits beyond any sane window (default 10 min). */
    private String seedElapsedSession(String tag) throws Exception {
        String sessionId = seedLiveSession(tag);
        Map<String, Object> payload = captureSessionStore.load(sessionId);
        payload.put("createdAtEpochSecond", System.currentTimeMillis() / 1000 - 86_400L);
        captureSessionStore.save(sessionId, payload);
        return sessionId;
    }

    private String candidateJson(String name) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "cookie");
        row.put("name", name);
        row.put("origin", ".guidance-contract.example");
        row.put("expiresAtEpochSecond", 0L);
        return objectMapper.writeValueAsString(row);
    }
}
