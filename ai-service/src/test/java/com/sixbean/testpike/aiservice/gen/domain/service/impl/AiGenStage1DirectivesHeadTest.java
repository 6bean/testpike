package com.sixbean.testpike.aiservice.gen.domain.service.impl;

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
 * Behaviour-kernel wiring proof for the case-generation stage-1 head
 * (generation-pipeline generalisation: stage 1 joins the shared L0 clause
 * assembly). Same tri-state contract as the other upgraded domains, plus one
 * domain-specific invariant: the per-task output-language instruction stays
 * attached to the head on every path, because a published operator template
 * is language-neutral while the generation task carries its own language.
 */
class AiGenStage1DirectivesHeadTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final AiGenServiceImpl service = new AiGenServiceImpl(
            mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class), coreGateway,
            new ObjectMapper(), null, null, null, null, null, null);

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKeepsLanguageInstruction() {
        when(coreGateway.getSysConfig(AiGenServiceImpl.KEY_STAGE1_DIRECTIVES_ENABLED))
                .thenReturn("1");
        String published = "用例生成指令（模板页定制口径），保留 STAGE1_CASE 标记与 JSON 输出要求。";
        when(coreGateway.promptTemplateLatestByStatus(
                AiGenServiceImpl.STAGE1_TEMPLATE_NAME,
                AiGenServiceImpl.STAGE1_TEMPLATE_DOMAIN, 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(11L,
                        AiGenServiceImpl.STAGE1_TEMPLATE_NAME,
                        AiGenServiceImpl.STAGE1_TEMPLATE_DOMAIN, 1, published, 2));

        String head = service.stage1DirectivesHead("zh-TW");

        assertTrue(head.contains(published), "已发布行=头正文: " + head);
        assertFalse(head.contains("你是资深测试工程师"), "模板在位=内置契约不叠加: " + head);
        assertTrue(head.contains("繁体中文"), "语言指令随任务保留: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "内核不变量恰一次: " + head);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        when(coreGateway.getSysConfig(AiGenServiceImpl.KEY_STAGE1_DIRECTIVES_ENABLED))
                .thenReturn("0");

        String head = service.stage1DirectivesHead("zh-CN");

        verify(coreGateway, never()).promptTemplateLatestByStatus(any(), any(), anyInt());
        assertTrue(head.contains("你是资深测试工程师"), "关态=内置契约头: " + head);
        assertTrue(head.contains("简体中文"), "关态头保留语言指令: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "内核不变量恰一次: " + head);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        when(coreGateway.getSysConfig(AiGenServiceImpl.KEY_STAGE1_DIRECTIVES_ENABLED))
                .thenReturn(null);
        when(coreGateway.promptTemplateLatestByStatus(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("core template endpoint unavailable"));

        String head = service.stage1DirectivesHead("zh-CN");

        assertTrue(head.contains("你是资深测试工程师"), "降级=内置契约头: " + head);
        assertTrue(head.contains("简体中文"), "降级头保留语言指令: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                "降级路径不变量仍恰一次: " + head);
    }
}
