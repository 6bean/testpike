package com.sixbean.testpike.aiservice.recordflow.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.recordflow.domain.component.RecordFlowPromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the recording-reverse leg (
 * per-domain upgrade, group 1); three-path assertions consumed from the
 * shared BehaviorKernelHeadProbe single source.
 */
class RecordFlowDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是移动端自动化测试的录制反推助手";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final RecordFlowServiceImpl service = new RecordFlowServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(),
            new RecordFlowPromptTemplate(new ObjectMapper()));

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, RecordFlowServiceImpl.KEY_DIRECTIVES_ENABLED,
                RecordFlowServiceImpl.TEMPLATE_NAME, RecordFlowServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, RecordFlowServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, RecordFlowServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
