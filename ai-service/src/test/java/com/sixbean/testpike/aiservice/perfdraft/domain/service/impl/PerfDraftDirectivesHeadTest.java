package com.sixbean.testpike.aiservice.perfdraft.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.perfdraft.domain.component.PerfDraftPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the load-test drafting leg (
 * per-domain upgrade, group 1); three-path assertions consumed from the
 * shared BehaviorKernelHeadProbe single source.
 */
class PerfDraftDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是接口压测的场景设计助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final PerfDraftServiceImpl service = new PerfDraftServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(),
            new PerfDraftPromptTemplate(new ObjectMapper()));

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, PerfDraftServiceImpl.KEY_DIRECTIVES_ENABLED,
                PerfDraftServiceImpl.TEMPLATE_NAME, PerfDraftServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, PerfDraftServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, PerfDraftServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
