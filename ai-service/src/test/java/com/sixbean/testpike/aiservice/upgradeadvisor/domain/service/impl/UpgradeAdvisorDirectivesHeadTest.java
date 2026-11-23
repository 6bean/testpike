package com.sixbean.testpike.aiservice.upgradeadvisor.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.upgradeadvisor.domain.component.UpgradeAdvisorPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the upgrade-advisory leg ( per-domain
 * upgrade, group 3): the system head must come from the shared L0 assembly
 * single source, keeping the strict risks/summary JSON contract on the
 * built-in fallback side. Assertion bodies live in BehaviorKernelHeadProbe
 * (R40 — the three-path contract is proven once, consumed per leg).
 */
class UpgradeAdvisorDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是移动端自动化测试平台的升级顾问";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final UpgradeAdvisorServiceImpl service = new UpgradeAdvisorServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(),
            mock(UpgradeAdvisorPromptTemplate.class));

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, UpgradeAdvisorServiceImpl.KEY_DIRECTIVES_ENABLED,
                UpgradeAdvisorServiceImpl.TEMPLATE_NAME, UpgradeAdvisorServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, UpgradeAdvisorServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, UpgradeAdvisorServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
