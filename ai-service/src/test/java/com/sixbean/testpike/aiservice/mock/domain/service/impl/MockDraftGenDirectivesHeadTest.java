package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelHeadProbe;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Behaviour-kernel wiring proof for the mock-draft leg ( per-domain
 * upgrade, group 2b). The template row carries only the shared role+shape
 * header; job-type clauses stay on the code side, so this leg proves both
 * the shared three-path contract and that the job-type instruction survives
 * whichever path the head assembly takes. Assertion bodies live in
 * BehaviorKernelHeadProbe (R40 — proven once, consumed per leg).
 */
class MockDraftGenDirectivesHeadTest {

    private static final String BUILTIN_HEAD_PREFIX = "你是 API Mock 桩规则起草器";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final MockDraftGenServiceImpl service = new MockDraftGenServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(MockDraftArtifactGateway.class), new ObjectMapper(), null);

    @Test
    void publishedTemplateRowReplacesBuiltinContractAndKernelClauseAppearsOnce() {
        BehaviorKernelHeadProbe.publishedRowBecomesHeadAndClauseAppearsOnce(
                coreGateway, MockDraftGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                MockDraftGenServiceImpl.TEMPLATE_NAME, MockDraftGenServiceImpl.TEMPLATE_DOMAIN,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void governanceKeyClosedSkipsTemplateReadAndKeepsBuiltinContract() {
        BehaviorKernelHeadProbe.closedGateSkipsTemplateReadAndKeepsContract(
                coreGateway, MockDraftGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void templateReadFailureDegradesToBuiltinContractWithoutBreaking() {
        BehaviorKernelHeadProbe.failingReadDegradesToContractWithoutBreaking(
                coreGateway, MockDraftGenServiceImpl.KEY_DIRECTIVES_ENABLED,
                service::behaviorDirectivesHead, BUILTIN_HEAD_PREFIX);
    }

    @Test
    void jobTypeClauseStaysOnTheCodeSideUnderEveryHeadPath() {
        // Gate closed (raw value null on a fresh mock): the built-in shared
        // header plus the job-type clause must both survive in the prompt.
        String prompt = service.buildSystemPrompt("edge-scenarios");
        assertTrue(prompt.contains("契约边缘场景包"), "边缘场景条款随拼装进最终提示词: " + prompt);
        assertTrue(prompt.contains(BUILTIN_HEAD_PREFIX), "关态=内置共享头正文: " + prompt);
    }
}
