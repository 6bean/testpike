package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioListParser;
import com.sixbean.testpike.aiservice.gen.domain.component.ScenarioStrategy;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop;
import com.sixbean.testpike.aiservice.llm.pipeline.ExhaustiveRecallDirectives;
import com.sixbean.testpike.aiservice.support.GenTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * contract for the exhaustive-recall switch on the stage-0 scenario
 * enumeration face: the block is appended to the enumeration system message
 * at the model-call site, the read is fail-soft ON, and the OFF state keeps
 * the system message byte-identical to the pre- contract header.
 */
class ScenarioEnumerationRecallDirectiveContractTest {

    private static final long PROJECT_ID = 7L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ScenarioEnumerationServiceImpl service = new ScenarioEnumerationServiceImpl(
            llmChain, llmRouter, coreGateway, new ScenarioListParser(new ObjectMapper()));

    private static String convergedDraft() {
        return "{\"scenarios\":["
                + "{\"kind\":\"main-flow\",\"title\":\"正确账号密码登录成功\",\"anchor\":\"正确的账号密码登录系统\"},"
                + "{\"kind\":\"equivalence\",\"title\":\"合法邮箱账号登录\",\"anchor\":\"账号\"},"
                + "{\"kind\":\"counter-example\",\"title\":\"错误密码被拒绝\",\"anchor\":\"正确的账号密码\"},"
                + "{\"kind\":\"boundary\",\"title\":\"密码长度取 8 与 20 边界\",\"anchor\":\"密码长度 8-20 位\"}"
                + "]}";
    }

    /** Runs one enumeration pass and returns the system message actually sent. */
    private String captureSystemMessage() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenTestFixtures.endpoint());
        when(llmChain.chat(any(ChatCompletionRequest.class), anyLong(), any(LlmFallbackNotice.class)))
                .thenReturn(new LlmChatResult(convergedDraft(), "mock", "mock-model", 10, 20, 5));
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_EXPAND_ENABLED)).thenReturn("1");
        when(coreGateway.getSysConfig(ScenarioStrategy.KEY_MAX_PER_REQUIREMENT)).thenReturn("8");
        when(coreGateway.getSysConfig(DeliberationLoop.GOVERNANCE_KEY)).thenReturn("2");

        service.enumerate("用户可以使用正确的账号密码登录系统，密码长度 8-20 位", 0, PROJECT_ID, 1L, 1L);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(llmChain).chat(captor.capture(), anyLong(),
                any(LlmFallbackNotice.class));
        return captor.getValue().messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(ChatMessage::textContent)
                .findFirst().orElseThrow();
    }

    @Test
    void absentKeyDefaultsOnAndSystemMessageCarriesTheSharedBlockBytes() {
        when(coreGateway.getSysConfig(ScenarioEnumerationServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn(null);

        String system = captureSystemMessage();

        for (String clause : ExhaustiveRecallDirectives.CLAUSES) {
            assertTrue(system.contains(clause), "四句字面逐枚入 system，缺: " + clause);
        }
        assertTrue(system.contains(ExhaustiveRecallDirectives.block()),
                "注入段字节必须等于公共件 block，禁另一份拼写");
    }

    @Test
    void switchOffKeepsSystemMessageByteIdenticalAndOnEqualsOffPlusBlock() {
        when(coreGateway.getSysConfig(ScenarioEnumerationServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("false");
        String off = captureSystemMessage();
        assertFalse(off.contains("【穷尽召回】"), "关态必须零注入");
        assertEquals(ScenarioEnumerationServiceImpl.ENUMERATION_SYSTEM, off,
                "关态=改版前逐字节契约头");

        reset(llmChain);
        when(coreGateway.getSysConfig(ScenarioEnumerationServiceImpl.KEY_RECALL_DIRECTIVE_ENABLED))
                .thenReturn("true");
        String on = captureSystemMessage();

        assertEquals(off + ExhaustiveRecallDirectives.block(), on,
                "开态=关态逐字节+公共段尾追加，禁改动其它字节");
    }
}
