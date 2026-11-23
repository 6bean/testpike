package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractLedgerStore;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * correction ledger (live doc 9084, evidence): once the run's
 * budget gate was tripped, two post-plane faces kept launching PAID provider
 * calls — the cross-group merge proposal ring burned 63 calls / 166,721 tokens
 * after the gate tripped because its cluster loop never consults the shared
 * run signal or the task window, and the reconcile supplementary rounds show
 * the same hole on the round head. The contract pinned here is the DoD wording
 * shared abort signal or the spent window BEFORE launching anything, and the
 * residual work fails soft — the merge ring leaves the remaining clusters on
 * the mechanical first-seen fold (no item is ever lost, no second fold
 * semantics), the reconcile loop closes the round list with the coverage
 * ledger still honest about the pending gaps.
 * <p>Fake-clock posture (taskClock seam + ExtractDeadline LongSupplier) as in
 * DocExtractReconcileResumeTest: the window dies deterministically with zero
 * sleeps, mid-run, exactly while the audited plane call is in flight. L2
 * isolation on the infrastructure seams only; real-chain behaviour lives at L4.
 */
class DocExtractPostGateStopTest {

    /** Gate window: armed at fake t=0, spent once the clock crosses this. */
    private static final long WINDOW_MILLIS = 3_600_000L;

    // ---- merge-ring face (doc 9084 live shape: two colliding clusters) ----

    private static final long MERGE_DOC = 9801L;
    private static final long MERGE_PROJECT = 9802L;
    private static final String MERGE_TOKEN = "gen-token-postgate-merge-001";
    private static final String MERGE_MARK = DocExtractServiceImpl.MERGE_INSTRUCTION;

    /**
 * the ring has TWO clusters and the gate can trip between them. */
    private static final String JSON_G1 = """
            {"requirements":[
              {"title":"标题甲","acceptanceCriteria":"五次错误锁定","chapter":"第一章","confidence":0.9,\
               "evidence":"第一章锚句"},
              {"title":"标题乙","acceptanceCriteria":"密码复杂度校验","chapter":"第一章","confidence":0.85,\
               "evidence":"第一章锚句"}]}""";
    private static final String JSON_G2 = """
            {"requirements":[
              {"title":"标题甲","acceptanceCriteria":"重复报出的甲","chapter":"第二章","confidence":0.9,\
               "evidence":"第二章锚句"},
              {"title":"标题乙","acceptanceCriteria":"重复报出的乙","chapter":"第二章","confidence":0.9,\
               "evidence":"第二章锚句"}]}""";
    
    private static final String JSON_MERGED_A = """
            {"requirements":[
              {"title":"标题甲","acceptanceCriteria":"五次错误锁定；重复报出的甲","chapter":"第一章",\
               "confidence":0.9,"evidence":"第一章锚句"}]}""";

    // ---- reconcile-round face (round head must consult the gate too) ----

    private static final long REC_DOC = 9811L;
    private static final long REC_PROJECT = 9812L;
    private static final String REC_TOKEN = "gen-token-postgate-reconcile-001";
    private static final String TEXT_A = "用户连续输错密码五次后账号锁定十分钟";
    private static final String TEXT_B = "报表导出支持按月归档并保留最近二十四个月";
    private static final String TEXT_C = "操作日志在线保留期限不少于六个月";
    private static final String TEXT_D = "水印在导出件上不可关闭";
    private static final String KEY_ROUNDS = "ai.doc.extract-reconcile-rounds";

    private static String item(String title, String criteria, String evidence) {
        return "{\"title\":\"" + title + "\",\"acceptanceCriteria\":\"" + criteria
                + "\",\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + evidence + "\"}";
    }

    /** First pass settles only blocks A and B → ledger gap = {C, D}. */
    private static final String REC_PASS1 = "{\"requirements\":["
            + item("登录失败锁定", "五次锁定", TEXT_A) + ","
            + item("报表按月归档", "保留24个月", TEXT_B) + "]}";
    /** Round 1 closes one block (C) and adds one unattributable row — the
 * draft count satisfies the round assertion so the ring converges
 * in ONE call, and the deficit strictly shrinks so round 2 would open. */
    private static final String REC_ROUND1 = "{\"requirements\":["
            + item("日志保留六个月", "在线可查", TEXT_C) + ","
            + item("无关条目", "越界提案", "这段摘录不在任何分块里") + "]}";
    /** Only ever reached when the gate is ignored at the round head. */
    private static final String REC_ROUND2 = "{\"requirements\":["
            + item("水印不可关", "水印常开", TEXT_D) + "]}";

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger callsAfterGate = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();
    private final List<ChatCompletionRequest> planeRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Transport fake shared by both faces. {@code gateTrigger} answers true
     * for the request that marks the gate death: the fake clock crosses the
     * window AT that call (it is the legitimate in-flight drain tail) and
     * every LATER chat call is booked as a post-gate new call.
     */
    private LlmChatResult reply(ChatCompletionRequest request,
                                Function<ChatCompletionRequest, String> responseOf,
                                Predicate<ChatCompletionRequest> gateTrigger,
                                boolean[] gateTripped) {
        calls.incrementAndGet();
        planeRequests.add(request);
        String system = request.messages().get(0).content();
        String user = request.messages().size() > 1 ? request.messages().get(1).content() : "";
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        if (!gateTripped[0] && gateTrigger.test(request)) {
            gateTripped[0] = true;
            now.set(WINDOW_MILLIS + 1L);
        } else if (gateTripped[0]) {
            callsAfterGate.incrementAndGet();
        }
        String json = user.contains(MERGE_MARK) ? JSON_MERGED_A : responseOf.apply(request);
        return new LlmChatResult(json, "openai", "routed-model", 100, 50, 20L);
    }

    private void stubTransports(LlmFallbackChain llmChain, long projectId,
                                Function<ChatCompletionRequest, String> responseOf,
                                Predicate<ChatCompletionRequest> gateTrigger) {
        boolean[] gateTripped = new boolean[1];
        when(llmChain.chat(any(), eq(projectId), any(LlmFallbackNotice.class),
                anyBoolean(), any()))
                .thenAnswer(inv -> reply(inv.getArgument(0), responseOf, gateTrigger, gateTripped));
        when(llmChain.chatStreamHandle(any(), eq(projectId), any(LlmFallbackNotice.class),
                any(), any()))
                .thenAnswer(inv -> new com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle(
                        reply(inv.getArgument(0), responseOf, gateTrigger, gateTripped),
                        DocExtractTestFixtures.endpoint()));
    }

    private long mergeCallCount() {
        return planeRequests.stream()
                .filter(r -> r.messages().get(1).content().contains(MERGE_MARK))
                .count();
    }

    @Test
    void mergeRingStopsAtClusterHeadsAfterTheGateTrips() {
        CoreGateway coreGateway = mock(CoreGateway.class);
        LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
        ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
        when(llmRouter.route(MERGE_PROJECT)).thenReturn(DocExtractTestFixtures.endpoint());
        when(coreGateway.getDocumentChunks(MERGE_DOC)).thenReturn(List.of(
                DocExtractTestFixtures.chunkWithId(21L, 1, "第一章",
                        "第一章锚句：锁定异常登录策略" + "字".repeat(100_000)),
                DocExtractTestFixtures.chunkWithId(22L, 2, "第二章",
                        "第二章锚句：重复报出的同一需求" + "字".repeat(100_000))));
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY))
                .thenReturn(String.valueOf(WINDOW_MILLIS / 1000L));
        DocExtractServiceImpl service = new DocExtractServiceImpl(
                coreGateway, llmChain, llmRouter,
                new com.fasterxml.jackson.databind.ObjectMapper());
        service.setTaskClock(now::get);
        // The gate dies while the FIRST merge proposal is in flight: cluster
        // one's call is the drain tail, cluster two must never launch.
        stubTransports(llmChain, MERGE_PROJECT,
                r -> {
                    String user = r.messages().get(1).content();
                    return user.contains("第一章") ? JSON_G1
                            : user.contains("第二章") ? JSON_G2 : "{\"requirements\":[]}";
                },
                r -> r.messages().get(1).content().contains(MERGE_MARK));

        service.extract(new AiDocExtractMessage(MERGE_DOC, MERGE_PROJECT, 1L, null, MERGE_TOKEN));

        assertEquals(1, mergeCallCount(),
                "闸断发生在归并簇一的外呼在途期间，簇二起闸首检查必须拦截全部新归并外呼");
        assertEquals(0, callsAfterGate.get(),
                "闸后新外呼恒 0（live doc 9084 归并环 63 笔付费外呼的防回归钉）");
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(MERGE_DOC), captor.capture(), eq(MERGE_TOKEN));
        List<Map<String, Object>> items = captor.getValue();
        assertEquals(2, items.size(), "残余簇回退机械首见折叠，条目一枚不丢: " + items);
        Map<String, Object> survivorB = items.stream()
                .filter(r -> "标题乙".equals(r.get("title"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(survivorB.get("acceptanceCriteria")).contains("重复报出的乙"),
                "闸后未走归并环的簇保持首见折叠语义（被折叠重复行正文并入存活行）");
    }

    @Test
    void reconcileRoundStopsAtRoundHeadAfterTheGateTrips() {
        CoreGateway coreGateway = mock(CoreGateway.class);
        LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
        ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
        DocExtractLedgerStore ledgerStore = mock(DocExtractLedgerStore.class);
        when(llmRouter.route(REC_PROJECT)).thenReturn(DocExtractTestFixtures.endpoint());
        when(coreGateway.getDocumentChunks(REC_DOC)).thenReturn(List.of(
                Map.of("id", 9101L, "seq", 1, "chapter", "安全", "content", TEXT_A),
                Map.of("id", 9102L, "seq", 2, "chapter", "安全", "content", TEXT_B),
                Map.of("id", 9103L, "seq", 3, "chapter", "安全", "content", TEXT_C),
                Map.of("id", 9104L, "seq", 4, "chapter", "安全", "content", TEXT_D)));
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY))
                .thenReturn(String.valueOf(WINDOW_MILLIS / 1000L));
        when(coreGateway.getSysConfig(KEY_ROUNDS)).thenReturn("3");
        DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
                coreGateway, llmChain, llmRouter, ledgerStore);
        service.setTaskClock(now::get);
        // Small chunks ride the single final-bucket plane: call 1 is the
        // initial pass, call 2 the first reconcile round — the gate dies
        // there, so round 2 must never open.
        AtomicInteger seen = new AtomicInteger();
        stubTransports(llmChain, REC_PROJECT,
                r -> switch (seen.incrementAndGet()) {
                    case 1 -> REC_PASS1;
                    case 2 -> REC_ROUND1;
                    default -> REC_ROUND2;
                },
                r -> seen.get() >= 1);

        service.extract(new AiDocExtractMessage(REC_DOC, REC_PROJECT, 1L, null, REC_TOKEN));

        assertEquals(2, calls.get(),
                "闸断发生在补提轮一在途期间，轮二起闸首检查必须拦截全部新补提外呼");
        assertEquals(0, callsAfterGate.get(),
                "闸后新外呼恒 0（复核补提轮路径的调用前闸断检查防回归钉）");
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(REC_DOC), captor.capture(), eq(REC_TOKEN));
        List<Map<String, Object>> items = captor.getValue();
        assertEquals(3, items.size(),
                "闸前已锚定条目照常落库（首轮两枚+补提轮一一枚），闸后轮次不落地: " + items);
        assertFalse(items.stream().anyMatch(r -> String.valueOf(r.get("evidence")).contains(TEXT_D)),
                "闸后轮次永不产出条目（水印块只可能来自被拦截的轮二）");
        // The coverage ledger still books the run — stopping the loop must
        // never weld the books closed (pending gap for block D stays honest).
        verify(ledgerStore).upsertIfChanged(eq(REC_DOC), eq(REC_TOKEN), any());
    }
}
