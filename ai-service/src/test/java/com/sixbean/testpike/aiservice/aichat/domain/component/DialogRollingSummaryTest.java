package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ChatMessageRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * b (AP-1 ②): the rolling-summary executor for the dialog
 * request. Same compressor discipline as the document chain (one LLM call per
 * older-history segment, length contract + &lt;input&gt; isolation inside the
 * prompt), but the OVERALL posture is fail-soft — a summary that cannot be
 * produced must render no section, never take the answer turn down.
 */
class DialogRollingSummaryTest {

    private static ChatMessageRemote row(long id, String role, String content) {
        return new ChatMessageRemote(id, 9L, role, content, null, "done",
                id, id, 1, 2, null, null, null);
    }

    private static LlmChatResult reply(String text) {
        return new LlmChatResult(text, "p", "m", 0, 0, 0L);
    }

    @Test
    void olderTurnsRideOneSummaryCallWithIsolationAndLengthContract() {
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        when(chain.chat(any(ChatCompletionRequest.class), eq(3L), any()))
                .thenReturn(reply("  早前两轮谈了订单超时与退款规则  "));
        ConversationSummarySource source = new DialogRollingSummary(chain, mock(CoreGateway.class)).forTurn("m-test", 3L);

        String summary = source.summarize(List.of(
                row(1, "user", "订单页超时怎么测"),
                row(2, "assistant", "先造一条失败执行"),
                row(3, "user", "退款规则呢")));

        assertEquals("早前两轮谈了订单超时与退款规则", summary.strip());
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, times(1)).chat(captor.capture(), eq(3L), any());
        List<ChatMessage> messages = captor.getValue().messages();
        assertEquals(2, messages.size(), "one system contract + one isolated transcript");
        String system = messages.get(0).content();
        assertTrue(system.contains("长度契约"), system);
        assertTrue(system.contains("输入隔离"), system);
        String user = messages.get(1).content();
        assertTrue(user.contains("<input>") && user.contains("</input>"), user);
        assertTrue(user.contains("订单页超时怎么测") && user.contains("退款规则呢"), user);
    }

    @Test
    void emptyProviderReplyIsRetriedOnceAndSettlesToNoSummary() {
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        when(chain.chat(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(reply(""));
        ConversationSummarySource source = new DialogRollingSummary(chain, mock(CoreGateway.class)).forTurn("m", 3L);

        String summary = source.summarize(List.of(row(1, "user", "问题一"), row(2, "user", "问题二")));

        assertEquals("", summary);
        verify(chain, times(2)).chat(any(ChatCompletionRequest.class), anyLong(), any());
    }

    @Test
    void chainFailureFailsSoftToNoSummaryInsteadOfBreakingTheTurn() {
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        when(chain.chat(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenThrow(new RuntimeException("provider 41301 over-budget"));
        ConversationSummarySource source = new DialogRollingSummary(chain, mock(CoreGateway.class)).forTurn("m", 3L);

        assertEquals("", source.summarize(List.of(row(1, "user", "问题一"))));
    }

    @Test
    void blankHistoryConsumesNoProviderCall() {
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        ConversationSummarySource source = new DialogRollingSummary(chain, mock(CoreGateway.class)).forTurn("m", 3L);

        assertEquals("", source.summarize(List.of()));
        verify(chain, never()).chat(any(ChatCompletionRequest.class), anyLong(), any());
    }
}
