package com.sixbean.testpike.aiservice.spec.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class SpecDraftExtractServiceImplTest {

    private static final long DOC_ID = 21L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final SpecDraftExtractServiceImpl service = new SpecDraftExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), null);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static AiSpecExtractMessage message(String modelHint) {
        return new AiSpecExtractMessage(DOC_ID, PROJECT_ID, OPERATOR_ID, modelHint, "pub-token-1");
    }

    private static Map<String, Object> chunk(int seq, String content) {
        return Map.of("seq", seq, "chapter", "接口清单", "content", content);
    }

    private void stubChunks(List<Map<String, Object>> chunks) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
    }

    private void stubLlmContent(String content) {
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L));
    }

    private static final String VALID_LLM_JSON = """
            {"items":[{"method":"POST","path":"/api/v1/orders","title":"创建订单",
            "description":"按购物车生成订单","sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    // ---- segment C1: channel-B run books its live frames into the shared
    // process event ledger (kind=spec-extract, ref=documentId, one fresh run
    // token per attempt) so the wizard tail drawer can replay the attempt
    // that actually runs. The absent seam keeps the legacy zero-report face
    // byte-identical — the other tests never wire a store. ----

    /** Success path: start frame first, per-group progress frames in the
     *  middle, done frame last; all six record args honest and single-run. */
    @Test
    void liveRunBooksStartProgressAndDoneFramesIntoLedger() {
        stubChunks(List.of(
                chunk(1, "创建订单接口 POST /api/v1/orders 用于下单"),
                chunk(2, "查询订单接口 GET /api/v1/orders/{id} 用于回查")));
        stubLlmContent(VALID_LLM_JSON);
        TaskProcessEventStore store = mock(TaskProcessEventStore.class);
        SpecDraftExtractServiceImpl reporter = new SpecDraftExtractServiceImpl(
                coreGateway, llmChain, llmRouter, new ObjectMapper(), null);
        reporter.setProcessEventStore(store);

        reporter.extract(message(null));

        ArgumentCaptor<String> kinds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refs = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> levels = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summaries = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.atLeast(3)).record(kinds.capture(), refs.capture(),
                tokens.capture(), levels.capture(), summaries.capture(), any());
        assertTrue(kinds.getAllValues().stream().allMatch("spec-extract"::equals),
                "kind 单源=spec-extract");
        assertTrue(refs.getAllValues().stream().allMatch(String.valueOf(DOC_ID)::equals),
                "业务锚=文档 id 字符串");
        assertEquals(1, tokens.getAllValues().stream().distinct().count(),
                "一次运行一个运行锚，全程同 token");
        assertTrue(tokens.getAllValues().get(0) != null && !tokens.getAllValues().get(0).isBlank(),
                "运行锚非空");
        List<String> frames = summaries.getAllValues();
        assertTrue(frames.get(0).startsWith("接口文档抽取已发起|分块 "),
                "首帧=发起帧点名分块与组数，实得=" + frames.get(0));
        assertTrue(frames.get(frames.size() - 1).startsWith("接口文档抽取完成|接口 "),
                "终态成功帧=完成帧点名接口数，实得=" + frames.get(frames.size() - 1));
        assertEquals("step", levels.getAllValues().get(levels.getAllValues().size() - 1),
                "完成帧级别在筛选闭集内");
        assertTrue(frames.stream().anyMatch(f -> f.startsWith("接口文档抽取进行中|第 ")),
                "每组完成落一条进行中帧");
    }

    /** Failure path: the run that throws lands exactly one error frame with
     *  the honest human reason as its tail sentence, and the exception keeps
     *  propagating untouched (MQ retry/dead-letter semantics unchanged). */
    @Test
    void failedRunBooksOneErrorFrameWithHonestReasonAndRethrows() {
        stubChunks(List.of(chunk(1, "番茄炒蛋的做法如下，先热锅再放蛋")));
        stubLlmContent("{\"items\":[]}");
        TaskProcessEventStore store = mock(TaskProcessEventStore.class);
        SpecDraftExtractServiceImpl reporter = new SpecDraftExtractServiceImpl(
                coreGateway, llmChain, llmRouter, new ObjectMapper(), null);
        reporter.setProcessEventStore(store);

        assertThrows(ServiceException.class, () -> reporter.extract(message(null)));

        ArgumentCaptor<String> levels = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summaries = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).record(eq("spec-extract"),
                eq(String.valueOf(DOC_ID)), any(), levels.capture(), summaries.capture(), any());
        int last = levels.getAllValues().size() - 1;
        assertEquals("error", levels.getAllValues().get(last), "失败终态=错误帧");
        assertTrue(summaries.getAllValues().get(last)
                        .startsWith("接口文档抽取失败|文档中未识别到接口定义"),
                "错误帧尾句=上屏人话原因，实得=" + summaries.getAllValues().get(last));
        assertEquals(1, levels.getAllValues().subList(0, last + 1).stream()
                        .filter("error"::equals).count(), "一次失败只落一条错误帧");
    }

    /** R52 unblock (dead letters 28746/28747): one path-less entry inside an
     *  otherwise healthy LLM answer must not fail the run — the valid entry
     *  is written back, the malformed one is dropped. */
    @Test
    void extract_混合畸形条目_丢弃病条目回写有效条目不失败() {
        stubChunks(List.of(chunk(1, "创建订单接口 POST /api/v1/orders。")));
        stubLlmContent("{\"items\":[{\"method\":\"POST\",\"path\":\"/api/v1/orders\","
                + "\"sourceAnchor\":\"创建订单接口\"},{\"method\":\"GET\",\"title\":\"无路径条目\"}]}");

        service.extract(message(null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeSpecDrafts(eq(DOC_ID), eq(OPERATOR_ID), items.capture());
        assertEquals(1, items.getValue().size(), "pathless entry dropped, valid one kept");
        assertEquals("/api/v1/orders", items.getValue().get(0).get("path"));
    }

    /** Every entry path-less = refusal stays (anti-overwrite guard) but the
     *  message names the real cause (rejected entries) instead of the
     *  misleading "document contains no interface definitions". */
    @Test
    void extract_全部条目缺路径_人话失败点名丢弃数() {
        stubChunks(List.of(chunk(1, "创建订单接口 POST /api/v1/orders。")));
        stubLlmContent("{\"items\":[{\"method\":\"POST\",\"title\":\"a\"},"
                + "{\"method\":\"GET\",\"title\":\"b\"}]}");

        ServiceException failure = assertThrows(ServiceException.class,
                () -> service.extract(message(null)));
        assertTrue(failure.getMessage().contains("path"),
                "human cause must name the missing path: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("2"),
                "rejected count must appear: " + failure.getMessage());
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
    }

    
    @Test
    void extract_单块成功_回写fromDrafts条目含通道与操作人() {
        stubChunks(List.of(chunk(1, "创建订单接口 POST /api/v1/orders，按购物车生成订单。")));
        stubLlmContent(VALID_LLM_JSON);

        service.extract(message(null));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String system = prompt.getValue().messages().get(0).textContent();
        assertTrue(system.contains(LlmExtractMarkers.SPEC_DRAFT_EXTRACT), "缺契约位");
        assertTrue(system.contains("不发明"), "缺防发明约束");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> items = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeSpecDrafts(eq(DOC_ID), eq(OPERATOR_ID), items.capture());
        assertEquals("B", items.getValue().get(0).get("channel"));
        assertEquals("/api/v1/orders", items.getValue().get(0).get("path"));
    }

    /** A3 root cause: endpoint paths live only in heading lines, so the chunk's
     *  chapter path must ride the user prompt — otherwise the model legitimately
     *  returns an empty list and the all-empty guard misreports "no endpoints". */
    @Test
    void extract_用户提示携带章节路径_接口名仅在标题时模型可见() {
        Map<String, Object> headed = new java.util.LinkedHashMap<>();
        headed.put("seq", 1);
        headed.put("chapter", "Demo API > GET /api/demo/ping");
        headed.put("content", "Returns pong. Query param: q (string, optional).");
        stubChunks(List.of(headed));
        stubLlmContent(VALID_LLM_JSON);

        service.extract(message(null));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String user = prompt.getValue().messages().get(1).textContent();
        assertTrue(user.contains("Demo API > GET /api/demo/ping"),
                "user 提示缺章节路径：接口名只存在于标题时模型无接口可抽");
        assertTrue(user.contains("Returns pong."), "user 提示必须仍含正文");
    }

    /** Untitled documents: a missing chapter must never inject the literal
     *  "null" into the prompt; extraction proceeds on the body alone. */
    @Test
    void extract_章节缺失_提示仅正文且零字面null() {
        stubChunks(List.of(Map.of("seq", 1, "content", "整段无标题正文")));
        stubLlmContent(VALID_LLM_JSON);

        service.extract(message(null));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String user = prompt.getValue().messages().get(1).textContent();
        assertTrue(user.contains("整段无标题正文"));
        assertTrue(!user.contains("null"), "章节缺席时禁把字面 null 注入提示");
    }

    
    @Test
    void extract_空分块_零LLM调用零回写人话失败() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.extract(message(null)));
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
    }

    
    @Test
    void extract_块数超上限_K8现读人话拒零LLM() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(
                List.of(chunk(1, "甲接口"), chunk(2, "乙接口")));
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_MAX_CHUNKS))
                .thenReturn("1");
        // : the cost-gate rejection face lives behind the closed door.
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("false");

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.extract(message(null)));
        assertTrue(e.getMessage().contains("1"), "人话原因应含当前上限值");
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
    }

    
    @Test
    void extract_非法输出_零回写人话失败() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent("对不起，我无法输出接口清单。");

        assertThrows(ServiceException.class, () -> service.extract(message(null)));
        verify(coreGateway, never()).writeSpecDrafts(anyLong(), anyLong(), any());
    }

    
    @Test
    void extract_modelHint非空_钉模型走pinned语义() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent(VALID_LLM_JSON);

        service.extract(message("glm-4-plus"));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ArgumentCaptor<Boolean> pinned = ArgumentCaptor.forClass(Boolean.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                pinned.capture());
        assertEquals("glm-4-plus", prompt.getValue().model());
        assertTrue(pinned.getValue());
    }

    /**
     * Kernel wiring, no published row: the system head keeps the built-in
     * contract AND carries the failure-transparency/evidence-first invariant
     * clause exactly once. An absent template row must never empty or strip
     * the head.
     */
    @Test
    void extract_无已发布模板行_契约头带内核不变量恰一次() {
        stubChunks(List.of(chunk(1, "创建订单接口 POST /api/v1/orders，按购物车生成订单。")));
        stubLlmContent(VALID_LLM_JSON);

        service.extract(message(null));

        String system = captureSystemHead();
        assertTrue(system.contains(LlmExtractMarkers.SPEC_DRAFT_EXTRACT), "缺契约位");
        assertTrue(system.contains(BehaviorKernelAssembly.KERNEL_CLAUSE), "缺内核不变量条款");
        assertEquals(1, countOccurrences(system, BehaviorKernelAssembly.KERNEL_CLAUSE),
                "内核不变量条款必须恰一次，禁重复堆叠");
    }

    /**
     * Kernel wiring, published row present: the operator-tuned template
     * content becomes the head body behind the leading kernel clause (the
     * clause is the stable cached prefix per the prefix-economics contract),
     * and the row is read from the spec-extract family with the published
     * (full-release) bucket.
     */
    @Test
    void extract_已发布模板行成为头正文_不变量仍恰一次() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent(VALID_LLM_JSON);
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_DIRECTIVES_ENABLED))
                .thenReturn("true");
        when(coreGateway.promptTemplateLatestByStatus(
                SpecDraftExtractServiceImpl.TEMPLATE_NAME,
                SpecDraftExtractServiceImpl.TEMPLATE_DOMAIN, 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(1L,
                        SpecDraftExtractServiceImpl.TEMPLATE_NAME,
                        SpecDraftExtractServiceImpl.TEMPLATE_DOMAIN, 1,
                        "定制抽取指令（含 " + LlmExtractMarkers.SPEC_DRAFT_EXTRACT + " 契约位）", 2));

        service.extract(message(null));

        String system = captureSystemHead();
        // Contract after the prefix-economics change (commit 9582d58cc): the
        // stable kernel clause leads the head as the shared cached prefix and
        // the published row becomes the body right behind it. This mirrors the
        // single-source assertion shape already used by BehaviorKernelHeadProbe.
        assertTrue(system.startsWith(BehaviorKernelAssembly.KERNEL_CLAUSE),
                "内核条款固定排头（前缀经济性）");
        assertTrue(system.contains("定制抽取指令"), "已发布行内容应成为头正文");
        assertEquals(1, countOccurrences(system, BehaviorKernelAssembly.KERNEL_CLAUSE),
                "不变量条款恰一次");
    }

    /**
     * Tri-state gate closed (explicit 0): the template family is not read at
     * all (zero extra core calls on the closed path), while the conservative
     * contract head keeps carrying the invariant clause — the floor is the
     * kernel's, not the operator row's.
     */
    @Test
    void extract_开关显式关闭_零模板读取且不变量仍在() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent(VALID_LLM_JSON);
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_DIRECTIVES_ENABLED))
                .thenReturn("0");

        service.extract(message(null));

        verify(coreGateway, never()).promptTemplateLatestByStatus(any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
        String system = captureSystemHead();
        assertTrue(system.contains(LlmExtractMarkers.SPEC_DRAFT_EXTRACT), "缺契约位");
        assertTrue(system.contains(BehaviorKernelAssembly.KERNEL_CLAUSE),
                "关闭态只关模板读，不关内核不变量");
    }

    /**
     * Honest degradation: a failing template read never takes the extraction
     * run down — the run continues on the built-in contract head plus the
     * invariant clause.
     */
    @Test
    void extract_模板读失败_回退契约头抽取不中断() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent(VALID_LLM_JSON);
        when(coreGateway.promptTemplateLatestByStatus(any(), any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("core unreachable"));

        service.extract(message(null));

        String system = captureSystemHead();
        assertTrue(system.contains(LlmExtractMarkers.SPEC_DRAFT_EXTRACT), "缺契约位");
        assertTrue(system.contains(BehaviorKernelAssembly.KERNEL_CLAUSE), "缺内核不变量条款");
        verify(coreGateway).writeSpecDrafts(eq(DOC_ID), eq(OPERATOR_ID), any());
    }

    /** Captures the system message sent to the fallback chain for the run. */
    private String captureSystemHead() {
        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        return prompt.getValue().messages().get(0).textContent();
    }

    private static int countOccurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
