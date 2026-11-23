package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the miss-time fallback answering leg
 * : one shared-head assembly, no
 * job-type branching. Assertion bodies live in BehaviorKernelHeadProbe
 * (R40 — the three-path contract is proven once, consumed per leg).
 */
class MockLlmFallbackGenDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是 API Mock 兜底应答器";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final MockLlmFallbackGenServiceImpl service = new MockLlmFallbackGenServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            new ObjectMapper());

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, MockLlmFallbackGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                MockLlmFallbackGenServiceImpl.TEMPLATE_NAME,
                MockLlmFallbackGenServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, MockLlmFallbackGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, MockLlmFallbackGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
