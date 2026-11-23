package com.sixbean.testpike.aiservice.perfdraft.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.perfdraft.api.dto.PerfDraftRequest;
import com.sixbean.testpike.aiservice.perfdraft.domain.service.PerfDraftService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI load-test scenario drafting, ai-service leg (/internal/perf/draft, B-11).
 * <p>Real database (tp_ai_call_log rows are inserted for real and cleaned per
 * test in the dedicated project band); CoreGateway / LlmFallbackChain /
 * ProjectLlmRouter are stubbed = L2 isolation of remote + provider
 * infrastructure (NlStepServiceImplTest same baseline). The live provider call
 * and the core -> ai-service orchestration belong to L3, never this layer.
 * <p>Asserted groups: 1) a closed or mistyped switch refuses a bare internal
 * hit with a plain word naming the exact key and zero model calls; 2) plain
 * parameter refusals (missing project, blank description); 3) happy path =
 * strict JSON parsed back as {steps:[...]} with values kept native, prompt
 * renders the description + the single-source HTTP method vocabulary, one
 * call-log row; 4) fenced replies tolerated; 5) unparseable or contract-shaped
 * replies refuse with AI_FORMAT_ERROR — never guessed structures.
 */
@SpringBootTest
class PerfDraftServiceImplTest {

    /** Test-only project band (call-log rows isolated by project_id, wiped after each test). */
    private static final long PROJECT_BASE = 989_300_000L;

    @Autowired
    private PerfDraftService perfDraftService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @MockitoBean
    private ProjectLlmRouter projectLlmRouter;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
    }

    private void switchOn() {
        when(coreGateway.getSysConfig("ai.perf.scenario-draft")).thenReturn("true");
        when(projectLlmRouter.route(anyLong()))
                .thenReturn(new LlmEndpointConfig("test-provider", "perfdraft-test", "k", "http://x", "config"));
    }

    private PerfDraftRequest request(long projectId) {
        return new PerfDraftRequest(projectId, "模拟电商下单压测：查询商品详情后提交订单，共两步");
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "perfdraft-test",
                500, 300, 900L),
                new LlmEndpointConfig("test-provider", "perfdraft-test", "k", "http://x", "config"));
    }

    /** 1a A closed switch refuses a bare internal hit naming the exact key, zero model calls. */
    @Test
    void closedSwitchRefusesNamingTheExactKeyWithZeroModelCalls() {
        when(coreGateway.getSysConfig("ai.perf.scenario-draft")).thenReturn("false");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfDraftService.draft(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("ai.perf.scenario-draft"), ex.getMessage());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        verifyNoInteractions(llmFallbackChain);
    }

    /**
     * 1b A mistyped row (e.g. "1", the shape other bool keys use) keeps the
     * point closed — same acceptance as the core-side switch gate: only an
     * explicit true opens an AI point, a dirty value can never spend tokens.
     */
    @Test
    void mistypedSwitchValueKeepsThePointClosed() {
        when(coreGateway.getSysConfig("ai.perf.scenario-draft")).thenReturn("1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfDraftService.draft(request(PROJECT_BASE + 2)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        verifyNoInteractions(llmFallbackChain);
    }

    /** 2a No project anchor = no model routing and no cost attribution: refuse plainly. */
    @Test
    void missingProjectRefusesWithPlainWords() {
        switchOn();
        PerfDraftRequest noProject = new PerfDraftRequest(null, "查询商品详情");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfDraftService.draft(noProject));
        assertTrue(ex.getMessage().contains("项目"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    /** 2b A blank sentence is no material: refuse before any model call. */
    @Test
    void blankDescriptionRefuses() {
        switchOn();
        PerfDraftRequest blank = new PerfDraftRequest(PROJECT_BASE + 3, "   ");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfDraftService.draft(blank));
        assertTrue(ex.getMessage().contains("描述"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    /**
     * 3 Happy path: {steps:[...]} parsed back with native numbers/strings,
     * the prompt carries the user sentence and the method vocabulary from the
     * shared contract (no hand-copied list), and exactly one call-log row is
     * written under this project.
     */
    @Test
    void happyPathParsesStepsAndWritesOneCallLogRow() {
        switchOn();
        long projectId = PROJECT_BASE + 4;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"steps":[
                          {"action":"http.request","method":"GET","url":"/api/products/1","timeoutMs":3000},
                          {"action":"http.request","method":"POST","url":"/api/orders"}
                        ]}"""));

        Map<String, Object> draft = perfDraftService.draft(request(projectId));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(2, steps.size());
        assertEquals("http.request", steps.get(0).get("action"));
        // native values survive untouched (core re-validation + wizard form consume them)
        assertEquals(3000, ((Number) steps.get(0).get("timeoutMs")).intValue());
        assertEquals("POST", steps.get(1).get("method"));

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '压测场景草拟'",
                Integer.class, projectId);
        assertEquals(1, logged);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        List<ChatMessage> messages = captor.getValue().messages();
        String userText = messages.get(messages.size() - 1).textContent();
        assertTrue(userText.contains("模拟电商下单压测"), userText);
        // the method vocabulary rides the shared contract, not a hand-copied list
        assertTrue(userText.contains("GET"), "prompt must render the allowed methods");
        // the only-allowed-action contract lives in the system turn (full prompt check)
        String fullPrompt = messages.stream()
                .map(ChatMessage::textContent)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(fullPrompt.contains("http.request"), "prompt must state the only allowed action");
        assertEquals("perfdraft-test", captor.getValue().model());
    }

    /** 4 A fenced reply is unwrapped before parsing (same tolerance as sibling generation legs). */
    @Test
    void fencedReplyIsStrippedAndParsed() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("```json\n{\"steps\":[{\"action\":\"http.request\",\"method\":\"GET\",\"url\":\"/api/ping\"}]}\n```"));

        Map<String, Object> draft = perfDraftService.draft(request(PROJECT_BASE + 5));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(1, steps.size());
        assertEquals("/api/ping", steps.get(0).get("url"));
    }

    /** 5a An unparseable reply refuses with AI_FORMAT_ERROR — no guessed structure is returned. */
    @Test
    void unparseableReplyRefusesWithFormatError() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉，我无法把这句话转成压测步骤"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfDraftService.draft(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    /** 5b A reply without a steps array violates the draft contract: refuse. */
    @Test
    void missingStepsArrayRefusesWithFormatError() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"steps\":\"查询商品详情\"}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfDraftService.draft(request(PROJECT_BASE + 7)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }
}
