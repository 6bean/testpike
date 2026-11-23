package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the evaluation question-generation leg
 * : the system head must come from the
 * shared L0 assembly single source, keeping the parser-facing marker and
 * output schema on the built-in contract side. Assertion bodies live in
 * BehaviorKernelHeadProbe (R40 — the three-path contract is proven once,
 * consumed per leg).
 */
class EvalQuestionGenDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是评测集造题器";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final EvalQuestionGenServiceImpl service = new EvalQuestionGenServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            new ObjectMapper(), null, null);

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, EvalQuestionGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                EvalQuestionGenServiceImpl.TEMPLATE_NAME, EvalQuestionGenServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, EvalQuestionGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, EvalQuestionGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }
}
