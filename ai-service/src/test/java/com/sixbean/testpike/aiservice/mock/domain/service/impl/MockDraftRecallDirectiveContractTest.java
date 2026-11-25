package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.ExhaustiveRecallDirectives;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * contract for the exhaustive-recall switch on the mock-draft
 * generation face: both queue consumers share {@code buildSystemPrompt},
 * so proving the switch at that single point covers the whole face. The
 * job-type clause stays on the code side and must survive either switch
 * state untouched.
 */
class MockDraftRecallDirectiveContractTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final MockDraftGenServiceImpl service = new MockDraftGenServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            mock(MockDraftArtifactGateway.class), new ObjectMapper(), null);

    @Test
    void absentKeyDefaultsOnAndPromptCarriesTheSharedBlockBytes() {
        when(coreGateway.getSysConfig(MockDraftGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn(null);

        String prompt = service.buildSystemPrompt("edge-scenarios");

        for (String clause : ExhaustiveRecallDirectives.CLAUSES) {
            assertTrue(prompt.contains(clause), "四句字面逐枚入 prompt，缺: " + clause);
        }
        assertTrue(prompt.contains(ExhaustiveRecallDirectives.block()),
                "注入段字节必须等于公共件 block，禁另一份拼写");
        assertTrue(prompt.contains("应用点：edge-scenarios"), "应用点条款两态都必须在位");
    }

    @Test
    void switchOffKeepsThePromptByteIdenticalWithZeroInjectedBytes() {
        when(coreGateway.getSysConfig(MockDraftGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("false");
        String off = service.buildSystemPrompt("spec-sync");

        assertFalse(off.contains("【穷尽召回】"), "关态必须零注入");

        when(coreGateway.getSysConfig(MockDraftGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("true");
        String on = service.buildSystemPrompt("spec-sync");

        assertEquals(off + ExhaustiveRecallDirectives.block(), on,
                "开态=关态逐字节+公共段尾追加，禁改动其它字节");
    }

    @Test
    void illegalValueDegradesToFactoryOnLikeEveryFailSoftSwitch() {
        when(coreGateway.getSysConfig(MockDraftGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("maybe");

        assertTrue(service.buildSystemPrompt(null).contains(ExhaustiveRecallDirectives.block()),
                "AI 功能出厂开启：不合规值按开启处理（warn 点名键，行为面仍为 ON）");
    }
}
