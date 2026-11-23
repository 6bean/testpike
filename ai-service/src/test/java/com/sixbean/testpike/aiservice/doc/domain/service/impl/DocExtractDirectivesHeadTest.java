package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour-kernel wiring proof for the requirement-extraction head
 * (generation-pipeline generalisation, segment: doc extract joins the shared
 * L0 clause assembly). The head must come from BehaviorKernelAssembly
 * single source: published template row replaces the built-in contract,
 * an explicit governance-key "off" skips the template read entirely, and a
 * failing read degrades to the built-in contract without breaking the run.
 * The failure-transparency/evidence-first invariant is appended exactly
 * once on every path.
 */
class DocExtractDirectivesHeadTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final DocExtractServiceImpl service = new DocExtractServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            new ObjectMapper());

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_DIRECTIVES_ENABLED))
                .thenReturn("1");
        String published = "需求点抽取指令（模板页定制口径），保留 "
                + DocExtractServiceImpl.EXTRACT_MARKER + " 标记与 JSON 输出要求。";
        when(coreGateway.promptTemplateLatestByStatus(
                DocExtractServiceImpl.TEMPLATE_NAME, DocExtractServiceImpl.TEMPLATE_DOMAIN, 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(9L,
                        DocExtractServiceImpl.TEMPLATE_NAME,
                        DocExtractServiceImpl.TEMPLATE_DOMAIN, 1, published, 2));

        String head = service.extractDirectivesHead();

        assertTrue(head.contains(published), "已发布行=头正文: " + head);
        assertFalse(head.contains("你是资深需求分析师"), "模板在位=内置契约不叠加: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "内核不变量恰一次: " + head);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_DIRECTIVES_ENABLED))
                .thenReturn("0");

        String head = service.extractDirectivesHead();

        verify(coreGateway, never()).promptTemplateLatestByStatus(any(), any(), anyInt());
        assertTrue(head.contains("你是资深需求分析师"), "关态=内置契约头: " + head);
        assertTrue(head.contains(DocExtractServiceImpl.EXTRACT_MARKER),
                "关态头保留输出标记: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "内核不变量恰一次: " + head);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        when(coreGateway.getSysConfig(DocExtractServiceImpl.KEY_DIRECTIVES_ENABLED))
                .thenReturn(null);
        when(coreGateway.promptTemplateLatestByStatus(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("core template endpoint unavailable"));

        String head = service.extractDirectivesHead();

        assertTrue(head.contains("你是资深需求分析师"), "降级=内置契约头: " + head);
        assertTrue(head.contains(DocExtractServiceImpl.EXTRACT_MARKER),
                "降级头保留输出标记: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "降级路径不变量仍恰一次: " + head);
    }
}
