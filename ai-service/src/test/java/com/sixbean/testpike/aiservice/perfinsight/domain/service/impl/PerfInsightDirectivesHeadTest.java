package com.sixbean.testpike.aiservice.perfinsight.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.perfinsight.domain.component.PerfInsightPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the load-test run-insight read-out head
 * . The assembly step delegates
 * the gate/read/degrade sequence to the single-source kernel piece, proven
 * through the shared three-path probe: a published template row replaces the
 * built-in contract, an explicitly closed gate performs zero template reads,
 * and a failing read degrades to the built-in contract without taking the
 * read-out down. The kernel invariant is appended exactly once on every path.
 */
class PerfInsightDirectivesHeadTest {

    /** Built-in contract prefix used by the probe's three-path assertions. */
    private static final String BUILTIN_PREFIX = "你是接口压测结果的归因解读助手";

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final PerfInsightServiceImpl service = new PerfInsightServiceImpl(
            gateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(), new PerfInsightPromptTemplate());

    @Test
    void publishedTemplateRowBecomesHeadAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                gateway,
                PerfInsightServiceImpl.KEY_DIRECTIVES_ENABLED,
                PerfInsightServiceImpl.TEMPLATE_NAME,
                PerfInsightServiceImpl.TEMPLATE_DOMAIN,
                service::directivesHead,
                BUILTIN_PREFIX);
    }

    @Test
    void closedGovernanceGateSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                gateway,
                PerfInsightServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::directivesHead,
                BUILTIN_PREFIX);
    }

    @Test
    void failingTemplateReadDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                gateway,
                PerfInsightServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::directivesHead,
                BUILTIN_PREFIX);
    }
}
