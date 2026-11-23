package com.sixbean.testpike.aiservice.perfinsight.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.perfinsight.api.dto.PerfInsightRequest;
import com.sixbean.testpike.aiservice.perfinsight.domain.service.PerfInsightService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * AI load-test run-insight read-out, ai-service leg (/internal/perf/insight,
 * B-11 .
 * <p>Real database (tp_ai_call_log rows inserted for real and cleaned per test
 * in the dedicated project band); CoreGateway / LlmFallbackChain /
 * ProjectLlmRouter are stubbed = L2 isolation of remote + provider
 * infrastructure (PerfDraftServiceImplTest same baseline). The live provider
 * call and the core -> ai-service orchestration belong to L3, never this layer.
 * <p>Asserted groups: 1) a closed or mistyped ai.perf.insight switch refuses a
 * bare internal hit naming the exact key with zero model calls; 2) plain
 * parameter refusals (missing project, blank facts block); 3) happy path = the
 * facts block rendered verbatim into the prompt (no recomputed numbers), one
 * {insight:"..."} text parsed back, one call-log row under the request's
 * project; 4) fenced replies tolerated; 5) unparseable, contract-violating or
 * empty-insight replies refuse with AI_FORMAT_ERROR — never a guessed read-out;
 * 6) an anchored run lands the process-event tail in the shared job
 * ledger — start, thinking with token counts, success line — and every refusal
 * ends with the shaped terminal failure line ,
 * while an anchorless legacy door call stays a zero-touch no-op.
 */
@SpringBootTest
class PerfInsightServiceImplTest {

    /** Test-only project band (call-log rows isolated by project_id, wiped after each test). */
    private static final long PROJECT_BASE = 989_300_100L;

    /** test-only run band (event rows keyed by job_ref, wiped after each test). */
    private static final long RUN_BASE = 989_390_100L;

    @Autowired
    private PerfInsightService perfInsightService;

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
        // R37/R57 posture: only the rows this family created for its own test
        // band are removed; the shared event ledger keeps every other job's tail.
        jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'perfinsight'"
                + " AND (job_ref LIKE '9893901%' OR job_ref = 'null')");
    }

    private void switchOn() {
        when(coreGateway.getSysConfig("ai.perf.insight")).thenReturn("true");
        when(projectLlmRouter.route(anyLong()))
                .thenReturn(new LlmEndpointConfig("test-provider", "perfinsight-test", "k", "http://x", "config"));
    }

    private PerfInsightRequest request(long projectId) {
        return new PerfInsightRequest(projectId, """
                结论：未通过。越线门禁：P99<=800ms（实测 1240ms）。
                P50=210ms P95=760ms P99=1240ms 错误率=2.4% 吞吐=318/s。
                错误分类TopN：timeout 41次, http5xx 12次。
                瓶颈线索：step_key=POST /api/orders 段耗时占比 61%。""", null);
    }

    /** Same facts block, but carrying the run anchor the event tail keys on. */
    private PerfInsightRequest anchoredRequest(long projectId, long runId) {
        return new PerfInsightRequest(projectId, """
                结论：未通过。越线门禁：P99<=800ms（实测 1240ms）。
                P50=210ms P95=760ms P99=1240ms 错误率=2.4% 吞吐=318/s。""", runId);
    }

    /** Event rows of one anchored run, oldest first (the drawer's read order). */
    private List<java.util.Map<String, Object>> eventRows(long runId) {
        return jdbcTemplate.queryForList(
                "SELECT level, summary, detail, run_token FROM tp_ai_task_event"
                        + " WHERE job_kind = 'perfinsight' AND job_ref = ? ORDER BY id ASC",
                String.valueOf(runId));
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "perfinsight-test",
                600, 200, 1200L),
                new LlmEndpointConfig("test-provider", "perfinsight-test", "k", "http://x", "config"));
    }

    /** 1a A closed switch refuses a bare internal hit naming the exact key, zero model calls. */
    @Test
    void closedSwitchRefusesNamingTheExactKeyWithZeroModelCalls() {
        when(coreGateway.getSysConfig("ai.perf.insight")).thenReturn("false");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("ai.perf.insight"), ex.getMessage());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        // Zero model calls is the precise contract here. verifyNoInteractions on
        // the shared context mock was order-fragile: the doc leg's wiring-time
        // enableCallLedger seam (DocExtractServiceImpl.setAiCallLogWriter) lands
        // on whichever test runs first and is not a model call at all.
        verify(llmFallbackChain, never()).chatHandle(any(ChatCompletionRequest.class), anyLong(), any());
    }

    /**
     * 1b A mistyped row (e.g. "1", the shape other bool keys use) keeps the
     * point closed — same acceptance as the core-side switch gate: only an
     * explicit true opens an AI point, a dirty value can never spend tokens.
     */
    @Test
    void mistypedSwitchValueKeepsThePointClosed() {
        when(coreGateway.getSysConfig("ai.perf.insight")).thenReturn("1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(request(PROJECT_BASE + 2)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        // Zero model calls is the precise contract here. verifyNoInteractions on
        // the shared context mock was order-fragile: the doc leg's wiring-time
        // enableCallLedger seam (DocExtractServiceImpl.setAiCallLogWriter) lands
        // on whichever test runs first and is not a model call at all.
        verify(llmFallbackChain, never()).chatHandle(any(ChatCompletionRequest.class), anyLong(), any());
    }

    /** 2a No project anchor = no model routing and no cost attribution: refuse plainly. */
    @Test
    void missingProjectRefusesWithPlainWords() {
        switchOn();
        PerfInsightRequest noProject = new PerfInsightRequest(null, "P99=1240ms", null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(noProject));
        assertTrue(ex.getMessage().contains("项目"), ex.getMessage());
        // Zero model calls is the precise contract here. verifyNoInteractions on
        // the shared context mock was order-fragile: the doc leg's wiring-time
        // enableCallLedger seam (DocExtractServiceImpl.setAiCallLogWriter) lands
        // on whichever test runs first and is not a model call at all.
        verify(llmFallbackChain, never()).chatHandle(any(ChatCompletionRequest.class), anyLong(), any());
    }

    /** 2b An empty facts block is no material: refuse before any model call. */
    @Test
    void blankFactsRefuses() {
        switchOn();
        PerfInsightRequest blank = new PerfInsightRequest(PROJECT_BASE + 3, "   ", null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(blank));
        assertTrue(ex.getMessage().contains("归因"), ex.getMessage());
        // Zero model calls is the precise contract here. verifyNoInteractions on
        // the shared context mock was order-fragile: the doc leg's wiring-time
        // enableCallLedger seam (DocExtractServiceImpl.setAiCallLogWriter) lands
        // on whichever test runs first and is not a model call at all.
        verify(llmFallbackChain, never()).chatHandle(any(ChatCompletionRequest.class), anyLong(), any());
    }

    /**
     * 3 Happy path: the run facts ride the prompt verbatim (numbers come from
     * core's attribution single source — this leg must not recompute or drop
     * them), {insight:"..."} is parsed back as plain text, and exactly one
     * call-log row is written under this project.
     */
    @Test
    void happyPathParsesInsightAndWritesOneCallLogRow() {
        switchOn();
        long projectId = PROJECT_BASE + 4;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"insight\":\"慢尾巴集中在下单接口：P99 已越过 800ms 门禁，超时类错误 41 次为主要来源，建议优先排查 POST /api/orders 的下游依赖。\"}"));

        Map<String, Object> result = perfInsightService.explain(request(projectId));

        String insight = (String) result.get("insight");
        assertTrue(insight.contains("下单"), insight);

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '压测归因解读'",
                Integer.class, projectId);
        assertEquals(1, logged);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        List<ChatMessage> messages = captor.getValue().messages();
        String userText = messages.get(messages.size() - 1).textContent();
        // facts block rides verbatim: numbers are core's, never re-derived here
        assertTrue(userText.contains("P99=1240ms"), userText);
        assertTrue(userText.contains("timeout 41"), userText);
        assertEquals("perfinsight-test", captor.getValue().model());
    }

    /** 4 A fenced reply is unwrapped before parsing (same tolerance as sibling generation legs). */
    @Test
    void fencedReplyIsStrippedAndParsed() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("```json\n{\"insight\":\"错误率主要来自超时，先看下游慢查询。\"}\n```"));

        Map<String, Object> result = perfInsightService.explain(request(PROJECT_BASE + 5));

        assertEquals("错误率主要来自超时，先看下游慢查询。", result.get("insight"));
    }

    /** 5a An unparseable reply refuses with AI_FORMAT_ERROR — no guessed read-out is returned. */
    @Test
    void unparseableReplyRefusesWithFormatError() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉，我无法解读这份归因数据"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    /** 5b A reply without an insight text field violates the contract: refuse. */
    @Test
    void missingInsightFieldRefusesWithFormatError() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"summary\":\"还行\"}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(request(PROJECT_BASE + 7)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    /** 5c An insight present but blank is not a read-out: refuse, never render an empty card. */
    @Test
    void blankInsightTextRefusesWithFormatError() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"insight\":\"   \"}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(request(PROJECT_BASE + 8)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    /**
 * 6a tail-f: an anchored run lands the whole process tail in the
 * shared job ledger — start line, thinking line carrying the token counts
 * of the model hop, success line — all keyed job_kind='perfinsight',
 * job_ref = run_token = the run id, in strictly ascending row order.
 */
    @Test
    void anchoredRunLandsStartThinkingAndSuccessEvents() {
        switchOn();
        long runId = RUN_BASE + 1;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"insight\":\"瓶颈集中在下单接口的下游慢查询。\"}"));

        perfInsightService.explain(anchoredRequest(PROJECT_BASE + 9, runId));

        List<java.util.Map<String, Object>> rows = eventRows(runId);
        assertEquals(3, rows.size(), String.valueOf(rows));
        assertEquals("step", rows.get(0).get("level"));
        assertTrue(String.valueOf(rows.get(0).get("summary")).contains("开始"),
                String.valueOf(rows.get(0)));
        assertEquals("thinking", rows.get(1).get("level"));
        String detail = String.valueOf(rows.get(1).get("detail"));
        assertTrue(detail.contains("\"promptTokens\":600") && detail.contains("\"completionTokens\":200"),
                "thinking line must carry the token counts for the drawer: " + detail);
        assertEquals("step", rows.get(2).get("level"));
        assertTrue(String.valueOf(rows.get(2).get("summary")).contains("完成"),
                String.valueOf(rows.get(2)));
        for (java.util.Map<String, Object> row : rows) {
            assertEquals(String.valueOf(runId), row.get("run_token"),
                    "run_token must equal the job_ref the cursor reads by");
        }
    }

    /**
 * 6b failure must feed back: a refused reply still ends the anchored
 * run's tail with the single-source shaped line (category + suggestion +
 * retry entry), and the exception itself propagates unchanged.
 */
    @Test
    void anchoredFailureLandsShapedTerminalErrorEventAndRethrows() {
        switchOn();
        long runId = RUN_BASE + 2;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉，我无法解读这份归因数据"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> perfInsightService.explain(anchoredRequest(PROJECT_BASE + 10, runId)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());

        List<java.util.Map<String, Object>> rows = eventRows(runId);
        java.util.Map<String, Object> tail = rows.get(rows.size() - 1);
        assertEquals("error", tail.get("level"));
        String summary = String.valueOf(tail.get("summary"));
        assertTrue(summary.startsWith("失败：【"), summary);
        assertTrue(summary.contains("建议：") && summary.contains("重试入口："), summary);
    }

    /**
     * 6c Even a closed-switch refusal reached through the internal door with an
     * anchor must not go silently: the ledger ends with the shaped error line.
     */
    @Test
    void anchoredClosedSwitchRefusalLandsTerminalErrorEvent() {
        long runId = RUN_BASE + 3;
        when(coreGateway.getSysConfig("ai.perf.insight")).thenReturn("false");

        assertThrows(ServiceException.class,
                () -> perfInsightService.explain(anchoredRequest(PROJECT_BASE + 11, runId)));

        List<java.util.Map<String, Object>> rows = eventRows(runId);
        assertEquals("error", rows.get(rows.size() - 1).get("level"), String.valueOf(rows));
    }

    /** 6d Legacy anchorless door call stays a zero-touch no-op on the event ledger. */
    @Test
    void legacyAnchorlessRequestLandsNoEventRows() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"insight\":\"ok\"}"));

        Map<String, Object> out = perfInsightService.explain(request(PROJECT_BASE + 12));

        assertEquals("ok", out.get("insight"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'perfinsight'"
                        + " AND (job_ref = 'null' OR job_ref LIKE '9893901%')",
                Integer.class);
        assertEquals(0, rows, "an anchorless call must not claim any event row");
    }
}
