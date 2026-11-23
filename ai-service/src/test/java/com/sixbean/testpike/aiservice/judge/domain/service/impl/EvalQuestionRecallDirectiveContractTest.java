package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.ExhaustiveRecallDirectives;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * contract for the exhaustive-recall switch on the judge question
 * generation face (same posture as the spec leg: block appended at the tail
 * of the assembled system head, fail-soft ON default, byte-identical OFF).
 */
class EvalQuestionRecallDirectiveContractTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final EvalQuestionGenServiceImpl service = new EvalQuestionGenServiceImpl(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class),
            new ObjectMapper(), null, null);

    @Test
    void absentKeyDefaultsOnAndHeadCarriesTheSharedBlockBytesExactlyOnce() {
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
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
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("false");
        String off = service.behaviorDirectivesHead();

        assertFalse(off.contains("【穷尽召回】"), "关态必须零注入");

        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("1");
        String on = service.behaviorDirectivesHead();

        assertEquals(off + ExhaustiveRecallDirectives.block(), on,
                "开态=关态逐字节+公共段尾追加（'1' 与 'true' 同判，值域按形状判定）");
    }

    @Test
    void illegalValueDegradesToFactoryOnLikeEveryFailSoftSwitch() {
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("maybe");

        assertTrue(service.behaviorDirectivesHead().contains(ExhaustiveRecallDirectives.block()),
                "AI 功能出厂开启：不合规值按开启处理（warn 点名键，行为面仍为 ON）");
    }
}
