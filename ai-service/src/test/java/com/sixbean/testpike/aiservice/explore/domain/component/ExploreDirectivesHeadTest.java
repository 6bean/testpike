package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreEvidenceGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolDispatcher;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the autonomous exploration loop
 * : the exploration leg carries TWO
 * modalities, so the shared three-path contract is proven once per family —
 * web ({@code explore-web-directives}) and mobile
 * ({@code explore-mobile-directives}) — each behind its own governance gate.
 * Assertion bodies live in BehaviorKernelHeadProbe (R40 single source);
 * this class only feeds each probe call the leg's constant pair.
 */
class ExploreDirectivesHeadTest {

    private static final String WEB_CONTRACT_PREFIX = "你是自主测试探索代理。";
    private static final String MOBILE_CONTRACT_PREFIX = "你是 App 自主探索代理（移动形态）。";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ExploreLoopKernel kernel = new ExploreLoopKernel(
            mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(ExploreBuiltinToolCatalog.class), mock(LlmFunctionCalling.class),
            mock(ToolDispatcher.class), mock(ExploreEvidenceGateway.class),
            coreGateway, mock(AiCallLogWriter.class), new ObjectMapper(),
            LoopContextFitting.NONE, null);

    @Test
    void webPublishedTemplateRowReplacesBuiltinContractAndClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, ExploreLoopKernel.KEY_WEB_DIRECTIVES_ENABLED,
                ExploreLoopKernel.TEMPLATE_NAME_WEB, ExploreLoopKernel.TEMPLATE_DOMAIN_WEB,
                () -> kernel.behaviorDirectivesHead(false), WEB_CONTRACT_PREFIX);
    }

    @Test
    void webGovernanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, ExploreLoopKernel.KEY_WEB_DIRECTIVES_ENABLED,
                () -> kernel.behaviorDirectivesHead(false), WEB_CONTRACT_PREFIX);
    }

    @Test
    void webTemplateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, ExploreLoopKernel.KEY_WEB_DIRECTIVES_ENABLED,
                () -> kernel.behaviorDirectivesHead(false), WEB_CONTRACT_PREFIX);
    }

    @Test
    void mobilePublishedTemplateRowReplacesBuiltinContractAndClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, ExploreLoopKernel.KEY_MOBILE_DIRECTIVES_ENABLED,
                ExploreLoopKernel.TEMPLATE_NAME_MOBILE, ExploreLoopKernel.TEMPLATE_DOMAIN_MOBILE,
                () -> kernel.behaviorDirectivesHead(true), MOBILE_CONTRACT_PREFIX);
    }

    @Test
    void mobileGovernanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, ExploreLoopKernel.KEY_MOBILE_DIRECTIVES_ENABLED,
                () -> kernel.behaviorDirectivesHead(true), MOBILE_CONTRACT_PREFIX);
    }

    @Test
    void mobileTemplateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, ExploreLoopKernel.KEY_MOBILE_DIRECTIVES_ENABLED,
                () -> kernel.behaviorDirectivesHead(true), MOBILE_CONTRACT_PREFIX);
    }
}
