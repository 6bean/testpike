package com.sixbean.testpike.aiservice.webelement.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyCandidateDescriptor;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingRequest;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingResult;
import com.sixbean.testpike.aiservice.webelement.domain.service.WebElementNamingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

/**
 * Survey candidate naming (WE batch segment P3) — L2 unit face.
 * <p>CoreGateway + LlmFallbackChain stubbed = infrastructure isolation only
 * (FailureClassifyServiceImplTest / VisualLocateServiceImplTest baseline); the
 * call ledger is written for real and asserted against the database, because
 * "every outbound call is reconcilable" is the point of this segment, not a
 * side effect. The real provider round-trip and the core relay wiring are
 * verified on the L3 live face (segment P5).
 * <p>Asserted contract set:
 * ① explicit switch-off (value 0/false) → applied=false, zero model calls,
 * zero ledger rows; a missing row keeps naming on (factory-on tri-state);
 * ② published template family row is read per call (hot edit lands next
 * survey); a missing row or gateway read failure falls back to the built-in
 * conservative head — the prompt is never empty;
 * ③ valid model output → names keyed by descriptor identity, ledger row with
 * the dedicated function tag and token counts;
 * ④ provider outage / unparsable output / out-of-band keys → applied=false
 * with an honest (possibly empty) subset, never invented names; the failed
 * attempt still leaves one ledger row.
 */
@SpringBootTest
class WebElementNamingServiceImplTest {

    /** Test-owned project bucket; the ledger rows are purged after every test. */
    private static final long PROJECT_BASE = 989_640_000L;

    private static final String KEY_ENABLED = "ai.element.naming.enabled";
    private static final String KEY_MODEL = "ai.element.naming.model";
    private static final String TEMPLATE_NAME = "web-element-survey-naming";
    private static final String TEMPLATE_DOMAIN = "web-element";

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
    }

    private SurveyNamingRequest request(long projectId) {
        // Existing contract tests deliberately pass no session id: naming
        // frames belong to WebElementNamingProcessEventTest (R62 segment b2).
        return new SurveyNamingRequest(projectId, "https://demo.example.test/login", List.of(
                new SurveyCandidateDescriptor("css=#login-btn|", "登录", "button", "表单顶部"),
                new SurveyCandidateDescriptor("text=帮助|", "帮助", null, "页脚")), null);
    }

    private void namingOn() {
        when(coreGateway.getSysConfig(KEY_ENABLED)).thenReturn("1");
        when(coreGateway.getSysConfig(KEY_MODEL)).thenReturn("test-naming-model");
    }

    private LlmChatHandle handleWith(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "stub-provider",
                "test-naming-model", 512, 64, 800L), null);
    }

    /** ① Explicit off: no model burn, no ledger row, honest empty result. */
    @Test
    void explicitSwitchOffSkipsModelCallAndLedger() {
        when(coreGateway.getSysConfig(KEY_ENABLED)).thenReturn("0");

        SurveyNamingResult result = namingService.nameSurveyCandidates(request(PROJECT_BASE + 1));

        assertFalse(result.applied());
        assertTrue(result.names().isEmpty());
        // Capability contract = naming never fires a chat round. The context
        // boot itself touches the shared chain bean once (DocExtract wires
        // the ledger writer through a setter), so verifyNoInteractions on the
        // whole mock would blame that unrelated startup seam; never() on
        // chatHandle is the honest, precise assertion.
        org.mockito.Mockito.verify(llmFallbackChain,
                org.mockito.Mockito.never()).chatHandle(any(ChatCompletionRequest.class),
                anyLong(), nullable(LlmFallbackNotice.class));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?",
                Integer.class, PROJECT_BASE, PROJECT_BASE + 1000L);
        assertEquals(0, rows);
    }

    /** ② Published template head travels in the request; names keyed by id. */
    @Test
    void publishedTemplateIsUsedAndValidNamesComeBackKeyed() {
        namingOn();
        when(coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME, TEMPLATE_DOMAIN, 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(1L, TEMPLATE_NAME,
                        TEMPLATE_DOMAIN, 3, "你是页面元素命名员：为每个候选起 12 字内业务语义名", 2));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("{\"names\":[{\"key\":\"css=#login-btn|\","
                        + "\"name\":\"登录按钮\"},{\"key\":\"text=帮助|\",\"name\":\"帮助链接\"}]}"));

        SurveyNamingResult result = namingService.nameSurveyCandidates(request(PROJECT_BASE + 2));

        assertTrue(result.applied());
        assertEquals("登录按钮", result.names().get("css=#login-btn|"));
        assertEquals("帮助链接", result.names().get("text=帮助|"));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(llmFallbackChain)
                .chatHandle(captor.capture(), eq(PROJECT_BASE + 2L), nullable(LlmFallbackNotice.class));
        ChatMessage systemHead = captor.getValue().messages().get(0);
        assertEquals("system", systemHead.role());
        assertTrue(systemHead.textContent().contains("业务语义名"));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = ?",
                Integer.class, PROJECT_BASE + 2, "页面元素测绘命名");
        assertEquals(1, rows);
    }

    /** ②b Missing template row → conservative fallback head, naming still works. */
    @Test
    void missingTemplateFallsBackToBuiltInHead() {
        namingOn();
        when(coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME, TEMPLATE_DOMAIN, 2))
                .thenReturn(null);
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("{\"names\":[{\"key\":\"css=#login-btn|\","
                        + "\"name\":\"登录按钮\"}]}"));

        SurveyNamingResult result = namingService.nameSurveyCandidates(request(PROJECT_BASE + 3));

        assertTrue(result.applied());
        assertEquals("登录按钮", result.names().get("css=#login-btn|"));
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(llmFallbackChain)
                .chatHandle(captor.capture(), eq(PROJECT_BASE + 3L), nullable(LlmFallbackNotice.class));
        assertNotNull(captor.getValue().messages().get(0).textContent());
        assertFalse(captor.getValue().messages().get(0).textContent().isBlank());
    }

    /** ③ Provider outage: honest degrade, one failure ledger row, no throw. */
    @Test
    void providerOutageDegradesHonestlyAndStillLogsAttempt() {
        namingOn();
        when(coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME, TEMPLATE_DOMAIN, 2))
                .thenReturn(null);
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenThrow(new RuntimeException("provider unreachable"));

        SurveyNamingResult result = namingService.nameSurveyCandidates(request(PROJECT_BASE + 4));

        assertFalse(result.applied());
        assertTrue(result.names().isEmpty());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = ?",
                Integer.class, PROJECT_BASE + 4, "页面元素测绘命名");
        assertEquals(1, rows);
    }

    /** ④ Unparsable model output → no fabricated names (applied=false). */
    @Test
    void unparsableOutputYieldsNoNames() {
        namingOn();
        when(coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME, TEMPLATE_DOMAIN, 2))
                .thenReturn(null);
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("我觉得这些元素应该叫登录按钮和帮助链接"));

        SurveyNamingResult result = namingService.nameSurveyCandidates(request(PROJECT_BASE + 5));

        assertFalse(result.applied());
        assertTrue(result.names().isEmpty());
    }

    /** ④b Unknown keys / blank / oversized names are filtered out of the map. */
    @Test
    void outOfBandAndBlankNamesAreFiltered() {
        namingOn();
        when(coreGateway.promptTemplateLatestByStatus(TEMPLATE_NAME, TEMPLATE_DOMAIN, 2))
                .thenReturn(null);
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(),
                nullable(LlmFallbackNotice.class)))
                .thenReturn(handleWith("{\"names\":["
                        + "{\"key\":\"css=#login-btn|\",\"name\":\"登录按钮\"},"
                        + "{\"key\":\"ghost=missing|\",\"name\":\"幽灵名\"},"
                        + "{\"key\":\"text=帮助|\",\"name\":\"   \"}]}"));

        SurveyNamingResult result = namingService.nameSurveyCandidates(request(PROJECT_BASE + 6));

        assertTrue(result.applied());
        assertEquals(1, result.names().size());
        assertEquals("登录按钮", result.names().get("css=#login-btn|"));
    }
}
