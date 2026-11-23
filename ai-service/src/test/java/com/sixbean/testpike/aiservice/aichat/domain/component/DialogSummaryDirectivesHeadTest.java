package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the dialog rolling summariser (
 * per-domain upgrade, group 4a): the aichat compression leg resolves its
 * system head through the shared L0 assembly — published row of
 * {@code aichat-dialog-summary-directives} when the governance gate is open,
 * the built-in compression contract otherwise, kernel invariant appended
 * exactly once either way. Assertion bodies live in BehaviorKernelHeadProbe
 * (R40 single source); this class only feeds each probe call the leg's
 * constant triple.
 */
class DialogSummaryDirectivesHeadTest {

    private static final String CONTRACT_PREFIX = "你是对话压缩助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final DialogRollingSummary summariser =
            new DialogRollingSummary(mock(LlmFallbackChain.class), coreGateway);

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, DialogRollingSummary.KEY_DIRECTIVES_ENABLED,
                DialogRollingSummary.TEMPLATE_NAME, DialogRollingSummary.TEMPLATE_DOMAIN,
                summariser::behaviorDirectivesHead, CONTRACT_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, DialogRollingSummary.KEY_DIRECTIVES_ENABLED,
                summariser::behaviorDirectivesHead, CONTRACT_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, DialogRollingSummary.KEY_DIRECTIVES_ENABLED,
                summariser::behaviorDirectivesHead, CONTRACT_PREFIX);
    }
}
