package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunk;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * the extraction pipeline reports every fine step into the
 * process event ledger (tp_ai_task_event) so the viewer can tail a long run
 * like a live log: one thinking event per provider call carrying the visible
 * output digest and the token counts, one step event per coarse milestone
 * (double-written beside the stage ledger, which stays byte-identical), and
 * one error event naming the failure cause when a call dies — the exception
 * itself still propagates (the red event never swallows the failure).
 * <p>L2 isolation on the infrastructure doors (CoreGateway / LlmFallbackChain /
 * ProjectLlmRouter / event store) — the pipeline and the reporting hooks run
 * for real. An absent store (legacy wiring without the seam) must stay a
 * zero-touch no-op.
 */
class DocExtractProcessEventRecordingTest {

    private static final long DOC_ID = 95L;
    private static final long PROJECT_ID = 35L;
    private static final String TOKEN = "gen-token-proc-001";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final DocExtractServiceImpl service = fixtures.pipeline();
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);

    /** All thinking rows landed by the run: summary + detail pairs. */
    private List<String[]> thinkingRows() {
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("doc-extract"), eq(String.valueOf(DOC_ID)),
                eq(TOKEN), eq("thinking"), summary.capture(), detail.capture());
        return java.util.stream.IntStream.range(0, summary.getAllValues().size())
                .mapToObj(i -> new String[]{summary.getAllValues().get(i),
                        detail.getAllValues().get(i)})
                .toList();
    }

    @Test
    void 每次外呼成功_落thinking事件_摘要含可见输出与函数位_detail带token计数() {
        service.setProcessEventStore(store);
        fixtures.stubHappyChain();

        service.extract(fixtures.message());

        List<String[]> rows = thinkingRows();
        assertTrue(rows.size() >= 1, "每一次提取面外呼都应留下一条过程事件");
        String firstSummary = rows.get(0)[0];
        assertTrue(firstSummary.startsWith("模型应答完成，输出片段："),
                "摘要点名动作语义（模型应答完成）: " + firstSummary);
        assertTrue(firstSummary.contains("正确账号密码登录"),
                "摘要携带模型可见输出片段（非隐藏思维链）: " + firstSummary);
        String firstDetail = rows.get(0)[1];
        assertTrue(firstDetail.contains("promptTokens") && firstDetail.contains("completionTokens")
                        && firstDetail.contains("latencyMs"),
                "机器可读面携带 token 计数与耗时: " + firstDetail);
    }

    @Test
    void 粗阶段里程碑_同批并落step事件_与阶段账双写并存() {
        service.setProcessEventStore(store);
        fixtures.stubHappyChain();

        service.extract(fixtures.message());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store, atLeast(1)).record(eq("doc-extract"), eq(String.valueOf(DOC_ID)),
                eq(TOKEN), eq("step"), contains("提取启动｜文档「"), detail.capture());
        assertTrue(detail.getValue().contains("start"),
                "step 事件 detail 带上阶段码便于对账: " + detail.getValue());
    }

    @Test
    void 外呼失败_落error事件点名失败因_异常照常上抛不吞() {
        service.setProcessEventStore(store);
        when(fixtures.llmRouter().route(PROJECT_ID)).thenReturn(endpoint());
        when(fixtures.coreGateway().getDocumentChunks(DOC_ID))
                .thenReturn(List.of(chunk(1, "登录", "账号密码正确可登录。")));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(), any(), any()))
                .thenThrow(new com.sixbean.testpike.common.exception.ServiceException(
                        "stream transport down",
                        com.sixbean.testpike.common.exception.BizErrorCode.AI_PROVIDER_ERROR));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("provider refused 503"));

        assertThrows(RuntimeException.class, () -> service.extract(fixtures.message()));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("doc-extract"), eq(String.valueOf(DOC_ID)),
                eq(TOKEN), eq("error"), summary.capture(), detail.capture());
        assertTrue(summary.getAllValues().stream().anyMatch(s -> s.contains("provider refused")),
                "error 事件摘要必须点名失败因: " + summary.getAllValues());
    }

    /**
 * The live defect "a long run died and the drawer showed nothing" pinned
 * as contract: a job-level failure must land a terminal process event
 * reasonCategory/suggestion/retryEntry for the viewer. Today's failExtraction
 * books no terminal event at all, which is exactly what this pins RED first.
 */
    @Test
    void 任务级失败_末事件带类别建议与重试入口_机读面同批() {
        service.setProcessEventStore(store);
        when(fixtures.llmRouter().route(PROJECT_ID)).thenReturn(endpoint());
        when(fixtures.coreGateway().getDocumentChunks(DOC_ID))
                .thenReturn(List.of(chunk(1, "登录", "账号密码正确可登录。")));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(), any(), any()))
                .thenThrow(new com.sixbean.testpike.common.exception.ServiceException(
                        "模型调用超时",
                        com.sixbean.testpike.common.exception.BizErrorCode.AI_TIMEOUT));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any()))
                .thenThrow(new com.sixbean.testpike.common.exception.ServiceException(
                        "模型调用超时",
                        com.sixbean.testpike.common.exception.BizErrorCode.AI_TIMEOUT));

        assertThrows(RuntimeException.class, () -> service.extract(fixtures.message()));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("doc-extract"), eq(String.valueOf(DOC_ID)),
                eq(TOKEN), eq("error"), summary.capture(), detail.capture());
        java.util.List<String[]> rows = java.util.stream.IntStream
                .range(0, summary.getAllValues().size())
                .mapToObj(i -> new String[]{summary.getAllValues().get(i),
                        detail.getAllValues().get(i)})
                .toList();
        assertTrue(rows.stream().anyMatch(r -> r[0] != null
                        && r[0].contains("失败：【")
                        && r[0].contains("建议：")
                        && r[0].contains("重试入口：")
                        && (r[0].contains("模型侧") || r[0].contains("校验侧")
                                || r[0].contains("环境侧"))),
                "末事件必须是「失败：【归类】原因｜建议｜重试入口」形态: " + rows);
        assertTrue(rows.stream().anyMatch(r -> r[1] != null
                        && r[1].contains("reasonCategory")
                        && r[1].contains("retryEntry")),
                "末事件机读面必须带 reasonCategory/retryEntry: " + rows);
    }

    @Test
    void 未注入事件门_管道照常跑零崩溃_旧装配语义不变() {
        fixtures.stubHappyChain();
        assertDoesNotThrow(() -> service.extract(fixtures.message()));
    }
}
