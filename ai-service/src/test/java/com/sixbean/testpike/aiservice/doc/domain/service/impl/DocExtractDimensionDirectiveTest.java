package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AX batch (suggestion hand-off channel): when the extraction
 * for one focused pass — the first-pass user prompt gains a targeted directive
 * naming that dimension, rendered through the very same prompt builder the
 * reconcile rounds use (single render source, no second engine). A hint-less
 * message (every legacy producer, old payloads included) keeps the prompt
 * free of any dimension wording = zero-drift baseline.
 * <p>L2 posture: CoreGateway / LlmFallbackChain / router are infrastructure
 * mocks (same isolation face as DocExtractReconcileTest); real extraction
 * quality of the focused pass lives at L4.
 */
class DocExtractDimensionDirectiveTest {

    private static final long DOC_ID = 9L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "dim-token-001";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocExtractServiceImpl service = new DocExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper());

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 2000);
    }

    private static Map<String, Object> chunk(long id, int seq, String content) {
        return Map.of("id", id, "seq", seq, "chapter", "安全", "content", content);
    }

    private static final String LOGIN_TEXT = "用户连续输错密码五次后账号锁定十分钟";
    private static final String RESP_OK =
            "{\"requirements\":[{\"title\":\"登录失败锁定\",\"acceptanceCriteria\":\"五次锁定\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + LOGIN_TEXT + "\"}]}";

    private void stub() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(
                List.of(chunk(101L, 1, LOGIN_TEXT)));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any()))
                .thenReturn(new LlmChatResult(RESP_OK, "openai", "routed-model", 100, 50, 20L));
    }

    private String firstPassUserPrompt() {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(1)).chat(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        return captor.getValue().messages().get(1).content();
    }

    @Test
    void hintedMessageRendersTargetedDirectiveInFirstPassPrompt() {
        stub();
        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN, "边界"));
        String prompt = firstPassUserPrompt();
        assertTrue(prompt.contains("定向补充提取"), prompt);
        assertTrue(prompt.contains("「边界」"), prompt);
    }

    @Test
    void blankHintKeepsThePromptFreeOfDimensionWording() {
        stub();
        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN, "  "));
        assertFalse(firstPassUserPrompt().contains("定向补充提取"));
    }

    @Test
    void legacyFiveArgumentMessageKeepsTheZeroInjectionBaseline() {
        stub();
        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));
        assertFalse(firstPassUserPrompt().contains("定向补充提取"));
    }
}
