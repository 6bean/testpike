package com.sixbean.testpike.aiservice.aichat.infrastructure.mq;

import com.sixbean.testpike.aiservice.aichat.domain.service.ChatRunOrchestrator;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.AiChatRunMessage;
import org.springframework.stereotype.Component;

/**
 * Idempotent execution seam of the dialog consumer ( step C;
 * MockGenIdempotentRunner precedent).
 * <p>Dedup key = the assistant message id: core opens exactly one streaming
 * placeholder per run, so a container requeue or a DLQ replay of the same
 * message id folds here instead of streaming a second answer into the same
 * ledger block (the ledger would happily journal both — the seal would then
 * reject the second close, leaving a confusing half-run).
 */
@Component
public class ChatRunIdempotentRunner {

    private final ChatRunOrchestrator orchestrator;

    public ChatRunIdempotentRunner(ChatRunOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** Run the dialog turn and echo the delivery token (a replay returns the first-seen token → DUPLICATE upstream). */
    @Idempotent(key = "'ai-chat-run:' + #message.assistantMessageId()")
    public String run(AiChatRunMessage message, String token) {
        orchestrator.run(message);
        return token;
    }
}
