package com.sixbean.testpike.aiservice.repair.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.repair.domain.component.RepairPromptTemplate;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the script-repair proposal leg (
 * per-domain upgrade, group 3): the system head must come from the shared L0
 * assembly single source, keeping the strict action-JSON output contract on
 * the built-in fallback side. Assertion bodies live in
 * BehaviorKernelHeadProbe (R40 — the three-path contract is proven once,
 * consumed per leg).
 */
class ScriptRepairLoopDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是 UI 自动化测试脚本修复助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ScriptRepairLoopServiceImpl service = new ScriptRepairLoopServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(RepairPromptTemplate.class), mock(AiCallLogWriter.class),
            mock(ActionJsonValidator.class), new ObjectMapper());

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                ScriptRepairLoopServiceImpl.TEMPLATE_NAME, ScriptRepairLoopServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
