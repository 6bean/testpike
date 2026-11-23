package com.sixbean.testpike.aiservice.wxmock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.wxmock.domain.component.WxMockPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the mini-program mock-generation leg
 * : the system head must come from the
 * shared L0 assembly single source, keeping the three-scenario mocks JSON
 * contract and the genkit placeholder rules on the built-in fallback side.
 * Assertion bodies live in BehaviorKernelHeadProbe (R40 — the three-path
 * contract is proven once, consumed per leg).
 */
class WxMockDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是微信小程序自动化测试的接口 mock 数据生成助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final WxMockServiceImpl service = new WxMockServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(), mock(WxMockPromptTemplate.class));

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, WxMockServiceImpl.KEY_DIRECTIVES_ENABLED,
                WxMockServiceImpl.TEMPLATE_NAME, WxMockServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, WxMockServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, WxMockServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
