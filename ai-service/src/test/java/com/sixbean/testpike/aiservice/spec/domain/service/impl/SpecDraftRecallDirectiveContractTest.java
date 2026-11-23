package com.sixbean.testpike.aiservice.spec.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.ExhaustiveRecallDirectives;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * contract for the exhaustive-recall switch on the spec-contract
 * extraction face: the clause block rides the built-in behaviour contract
 * (the published template row owns its own text), the read is fail-soft ON
 * through the shared switch piece, and the OFF state stays byte-identical
 * to the pre- head so flipping the key can never rewrite the rest of
 * the prompt.
 */
class SpecDraftRecallDirectiveContractTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final SpecDraftExtractServiceImpl service = new SpecDraftExtractServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            new ObjectMapper(), null);

    @Test
    void absentKeyDefaultsOnAndHeadCarriesTheSharedBlockBytesExactlyOnce() {
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn(null);

        String head = service.behaviorDirectivesHead();

        for (String clause : ExhaustiveRecallDirectives.CLAUSES) {
            assertTrue(head.contains(clause), "四句字面逐枚入 head，缺: " + clause);
        }
        assertTrue(head.contains(ExhaustiveRecallDirectives.block()),
                "注入段字节必须等于公共件 block，禁另一份拼写");
    }

    @Test
    void switchOffKeepsTheHeadByteIdenticalWithZeroInjectedBytes() {
        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("false");
        String off = service.behaviorDirectivesHead();

        assertFalse(off.contains("【穷尽召回】"), "关态必须零注入");

        when(coreGateway.getSysConfig(SpecDraftExtractServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("true");
        String on = service.behaviorDirectivesHead();

        assertEquals(off + ExhaustiveRecallDirectives.block(), on,
                "开态=关态逐字节+公共段尾追加，禁改动其它字节");
    }

    @Test
    void illegalValueDegradesToFactoryOnLikeEveryFailSoftSwitch() {
        when(coreGateway.getSysConfig(eq(SpecDraftExtractServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED)))
                .thenReturn("maybe");

        assertTrue(service.behaviorDirectivesHead().contains(ExhaustiveRecallDirectives.block()),
                "AI 功能出厂开启：不合规值按开启处理（ warn 点名键，行为面仍为 ON）");
    }
}
