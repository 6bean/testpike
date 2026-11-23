package com.sixbean.testpike.aiservice.nlstep.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.nlstep.domain.component.NlStepPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the natural-language step leg (
 * per-domain upgrade, group 1): the head must come from the shared L0
 * assembly single source. Assertion bodies live in BehaviorKernelHeadProbe
 * (R40 — the three-path contract is proven once, consumed per leg).
 */
class NlStepDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是移动端自动化测试的步骤生成助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final NlStepServiceImpl service = new NlStepServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(),
            new NlStepPromptTemplate(new ObjectMapper()));

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, NlStepServiceImpl.KEY_DIRECTIVES_ENABLED,
                NlStepServiceImpl.TEMPLATE_NAME, NlStepServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, NlStepServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, NlStepServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
