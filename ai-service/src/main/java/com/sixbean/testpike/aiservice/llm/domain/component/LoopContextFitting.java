package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;

/**
 * Pre-send budget fitting seam for multi-round dialog loops (AP batch
 * , AP-2). A loop rebuilds the WHOLE transcript every round; without a
 * budget step the packet grows quadratically and the oversized tail fails the
 * entire order at the provider. Consumers (ReActRunner, ExploreLoopKernel)
 * call {@link #fit} right before each provider round; the aichat-side gate
 * folds older exchange units into the rolling-summary section when the packet
 * exceeds the routed endpoint's prompt limit.
 *
 * <p>The seam lives in the llm domain so the loop components keep the
 * existing downward dependency direction (no llm/aichat import cycle); the
 * NONE identity keeps legacy fixtures byte-compatible — an unfitting pass is
 * exactly "send as built".
 */
public interface LoopContextFitting {

    /** Identity fitting (unit fixtures / disabled wiring): send as built. */
    LoopContextFitting NONE = (request, projectId) -> request;

    /**
 * Context-overflow safety net : asked with the rejection the
 * vendor actually returned. Fittings without a heal capability rethrow the
 * same error instance — the caller's legacy posture is byte-identical.
 */
    default com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest
            healAfterOverflow(
            com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest sent, long projectId,
            com.sixbean.testpike.common.exception.ServiceException cause) {
        throw cause;
    }

    /**
     * Return the packet to send this round: the request itself when it fits
     * (or cannot be safely folded), a rebuilt request with the older units
     * folded into a summary section when it exceeds the endpoint budget.
     */
    ChatCompletionRequest fit(ChatCompletionRequest request, long projectId);

    /**
 * Send seam : fit right before the call and,
 * when the vendor still rejects the packet as a context overflow, retry
 * ONCE with the healed fold. ReAct rounds and generation single calls
 * share this one posture — a fitting without a heal capability rethrows
 * the same error instance here, so the legacy pass is byte-identical.
 * {@code relay == null} is the blocking completion, any relay consumes the
 * streaming events (the ReAct stream sink).
 */
    static com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle send(
            LoopContextFitting fitting,
            com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain chain,
            ChatCompletionRequest request, long projectId,
            com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice notice,
            java.util.function.Consumer<
                    com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent> relay) {
        ChatCompletionRequest sent = fitting.fit(request, projectId);
        try {
            return relay == null
                    ? chain.chatHandle(sent, projectId, notice)
                    : chain.chatStreamHandle(sent, projectId, notice, relay);
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            ChatCompletionRequest healed = fitting.healAfterOverflow(sent, projectId, e);
            return relay == null
                    ? chain.chatHandle(healed, projectId, notice)
                    : chain.chatStreamHandle(healed, projectId, notice, relay);
        }
    }
}
