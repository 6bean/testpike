package com.sixbean.testpike.aiservice.matricesample.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.matricesample.domain.component.MatrixSamplePromptTemplate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the matrix-sampling leg ( per-domain
 * upgrade, group 2): the system head must come from the shared L0 assembly
 * single source. Assertion bodies live in BehaviorKernelHeadProbe (R40 —
 * the three-path contract is proven once, consumed per leg).
 */
class MatrixSampleDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是移动端自动化测试平台的机型矩阵抽样顾问";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final MatrixSampleServiceImpl service = new MatrixSampleServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(AiCallLogWriter.class), new ObjectMapper(),
            new MatrixSamplePromptTemplate(new ObjectMapper()));

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, MatrixSampleServiceImpl.KEY_DIRECTIVES_ENABLED,
                MatrixSampleServiceImpl.TEMPLATE_NAME, MatrixSampleServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, MatrixSampleServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, MatrixSampleServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
