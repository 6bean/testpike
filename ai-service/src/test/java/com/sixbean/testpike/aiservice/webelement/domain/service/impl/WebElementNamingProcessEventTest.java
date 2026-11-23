package com.sixbean.testpike.aiservice.webelement.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyCandidateDescriptor;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingRequest;
import com.sixbean.testpike.aiservice.webelement.domain.service.WebElementNamingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Naming-batch frames on the survey process stream (R62 segment b2, ).
 * <p>Every attempted naming batch lands exactly one frame on the same
 * (kind, ref, token) triple the core face writes its step and terminal
 * frames onto — job_kind web-element-survey, job_ref = run_token = the
 * explore session id — so the survey tail drawer shows the naming outcome
 * without any second stream. The written branches: adopted (with counts),
 * honest degrade to zero adoption (counts never swallowed), model unset
 * (names the config key), provider outage (error level). An explicit
 * switch-off keeps the "burns nothing and writes nothing" contract: no
 * frame at all. A missing session id skips only the frame — the naming
 * itself still runs and still lands its call-ledger row.
 * <p>L2 face with real tp_ai_task_event writes (the frame IS the point of
 * this segment, same posture as the call-ledger assertions in
 * WebElementNamingServiceImplTest); the core relay forwarding is covered
 * in WebElementSurveyNamingTest, the live hop on the L3 face.
 */
@SpringBootTest
class WebElementNamingProcessEventTest {

    /** Test-owned project bucket; ledger and event rows purged after every test. */
    private static final long PROJECT_BASE = 989_650_000L;
    /** Session-id band owned by this class (job_ref values land here). */
    private static final long SESSION_BASE = 989_650_100L;

    private static final String KIND = "web-element-survey";
    private static final String KEY_ENABLED = "ai.element.naming.enabled";
    private static final String KEY_MODEL = "ai.element.naming.model";

    @Autowired
    private WebElementNamingService namingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
        jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = ?"
                + " AND job_ref >= ? AND job_ref <= ?",
                KIND, String.valueOf(SESSION_BASE), String.valueOf(SESSION_BASE + 1000L));
    }

    private SurveyNamingRequest request(long projectId, long sessionId) {
        return new SurveyNamingRequest(projectId, "https://demo.example.test/login", List.of(
                new SurveyCandidateDescriptor("css=#login-btn|", "登录", "button", "表单顶部"),
                new SurveyCandidateDescriptor("text=帮助|", "帮助", null, "页脚")), sessionId);
    }

    private void namingOn() {
        when(coreGateway.getSysConfig(KEY_ENABLED)).thenReturn("1");
        when(coreGateway.getSysConfig(KEY_MODEL)).thenReturn("test-naming-model");
    }

    private void templateMissing() {
        when(coreGateway.promptTemplateLatestByStatus(
                WebElementNamingServiceImpl.TEMPLATE_NAME,
                WebElementNamingServiceImpl.TEMPLATE_DOMAIN, 2)).thenReturn(null);
    }

    private LlmChatHandle handleWith(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "stub-provider",
                "test-naming-model", 512, 64, 800L), null);
    }

    private List<Map<String, Object>> frames(long sessionId) {
        return jdbcTemplate.queryForList("SELECT level, summary, run_token"
                + " FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?"
                + " ORDER BY id ASC", KIND, String.valueOf(sessionId));
    }

    /** Adopted names → one step frame carrying adopted/total counts. */
    @Test
    void adoptedBatchLandsOneStepFrameWithCounts() {
        namingOn();
        templateMissing();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("{\"names\":["
                        + "{\"key\":\"css=#login-btn|\",\"name\":\"登录按钮\"},"
                        + "{\"key\":\"text=帮助|\",\"name\":\"帮助链接\"}]}"));

        namingService.nameSurveyCandidates(request(PROJECT_BASE + 1, SESSION_BASE + 1));

        List<Map<String, Object>> frames = frames(SESSION_BASE + 1);
        assertEquals(1, frames.size(), "exactly one naming-batch frame per attempt");
        assertEquals("step", frames.get(0).get("level"));
        assertEquals(String.valueOf(SESSION_BASE + 1), frames.get(0).get("run_token"),
                "single-tail contract: run_token is the session id like every other frame");
        assertTrue(String.valueOf(frames.get(0).get("summary")).contains("2/2"),
                "adopted counts travel in the frame: " + frames.get(0).get("summary"));
    }

    /** Unparsable answer → one step frame, degrade branch keeps 0/total honest. */
    @Test
    void unparsableAnswerLandsDegradeFrameWithoutSwallowingCounts() {
        namingOn();
        templateMissing();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("我觉得这些元素应该叫登录按钮和帮助链接"));

        namingService.nameSurveyCandidates(request(PROJECT_BASE + 2, SESSION_BASE + 2));

        List<Map<String, Object>> frames = frames(SESSION_BASE + 2);
        assertEquals(1, frames.size());
        assertEquals("step", frames.get(0).get("level"));
        assertTrue(String.valueOf(frames.get(0).get("summary")).contains("0/2"),
                "the degrade frame must not swallow the candidate counts: "
                        + frames.get(0).get("summary"));
    }

    /** Model unset → one step frame naming the config key (fix-me guidance). */
    @Test
    void missingModelLandsOneStepFrameNamingTheConfigKey() {
        when(coreGateway.getSysConfig(KEY_ENABLED)).thenReturn("1");
        when(coreGateway.getSysConfig(KEY_MODEL)).thenReturn(" ");

        namingService.nameSurveyCandidates(request(PROJECT_BASE + 3, SESSION_BASE + 3));

        List<Map<String, Object>> frames = frames(SESSION_BASE + 3);
        assertEquals(1, frames.size());
        assertEquals("step", frames.get(0).get("level"));
        String summary = String.valueOf(frames.get(0).get("summary"));
        assertTrue(summary.contains(KEY_MODEL),
                "frame must name the config key to fix: " + summary);
        assertTrue(summary.contains("2"),
                "frame keeps the untouched candidate count: " + summary);
    }

    /** Provider outage → one error-level frame with 0/total. */
    @Test
    void providerOutageLandsOneErrorFrame() {
        namingOn();
        templateMissing();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenThrow(new RuntimeException("provider unreachable"));

        namingService.nameSurveyCandidates(request(PROJECT_BASE + 4, SESSION_BASE + 4));

        List<Map<String, Object>> frames = frames(SESSION_BASE + 4);
        assertEquals(1, frames.size());
        assertEquals("error", frames.get(0).get("level"));
        assertTrue(String.valueOf(frames.get(0).get("summary")).contains("0/2"),
                "the outage frame keeps adopted/total counts: "
                        + frames.get(0).get("summary"));
    }

    /** Explicit switch-off keeps the writes-nothing contract: no frame. */
    @Test
    void explicitSwitchOffWritesNoFrame() {
        when(coreGateway.getSysConfig(KEY_ENABLED)).thenReturn("0");

        namingService.nameSurveyCandidates(request(PROJECT_BASE + 5, SESSION_BASE + 5));

        assertEquals(0, frames(SESSION_BASE + 5).size(),
                "switch-off burns nothing and writes nothing, frames included");
    }

    /** Missing session id skips only the frame; naming itself still runs. */
    @Test
    void absentSessionIdSkipsFrameButKeepsNamingRunning() {
        namingOn();
        templateMissing();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("{\"names\":["
                        + "{\"key\":\"css=#login-btn|\",\"name\":\"登录按钮\"}]}"));

        var result = namingService.nameSurveyCandidates(
                new SurveyNamingRequest(PROJECT_BASE + 6, "https://demo.example.test/login",
                        List.of(new SurveyCandidateDescriptor("css=#login-btn|", "登录",
                                "button", null)), null));

        assertTrue(result.applied(), "naming still adopts names without a session anchor");
        Integer events = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = ? AND job_ref LIKE ?",
                Integer.class, KIND, "null%");
        assertEquals(0, events);
    }
}
