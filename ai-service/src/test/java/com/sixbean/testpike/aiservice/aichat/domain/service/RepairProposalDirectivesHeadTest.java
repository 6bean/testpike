package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.repair.domain.component.RepairPromptTemplate;
import com.sixbean.testpike.aiservice.repair.domain.service.impl.ScriptRepairLoopServiceImpl;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the dialog repair-draft head (
 * per-domain upgrade, group 3b half-leg). The dialog leg shares the B4
 * vocabulary — same governance key, same template family — and delegates the
 * gate/read/degrade sequence to the single-source assembly, so the contract is
 * proven through the shared probe instead of per-leg assertion bodies:
 * a published template row replaces the built-in contract, an explicitly
 * closed gate performs zero template reads, and a failing read degrades to
 * the built-in contract without breaking the draft run. The kernel invariant
 * is appended exactly once on every path.
 */
class RepairProposalDirectivesHeadTest {

    /** Built-in contract prefix used by the probe's three-path assertions. */
    private static final String BUILTIN_PREFIX = "你是 UI 自动化测试脚本修复助手";

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final RepairProposalGenerator generator = new RepairProposalGenerator(
            mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class), gateway,
            new RepairPromptTemplate(), mock(ActionJsonValidator.class),
            mock(AiCallLogWriter.class), new ObjectMapper());

    @Test
    void publishedTemplateRowBecomesHeadAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                gateway,
                ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                ScriptRepairLoopServiceImpl.TEMPLATE_NAME,
                ScriptRepairLoopServiceImpl.TEMPLATE_DOMAIN,
                generator::behaviorDirectivesHead,
                BUILTIN_PREFIX);
    }

    @Test
    void closedGovernanceGateSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                gateway,
                ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                generator::behaviorDirectivesHead,
                BUILTIN_PREFIX);
    }

    @Test
    void failingTemplateReadDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                gateway,
                ScriptRepairLoopServiceImpl.KEY_DIRECTIVES_ENABLED,
                generator::behaviorDirectivesHead,
                BUILTIN_PREFIX);
    }
}
