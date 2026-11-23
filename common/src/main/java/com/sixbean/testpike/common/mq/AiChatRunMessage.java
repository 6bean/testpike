package com.sixbean.testpike.common.mq;

import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;

import java.util.List;

/**
 * Dialog run request crossing the MQ boundary :
 * core opens the ledger turn and publishes this identity-only message; the
 * ai-service consumer streams the answer back through the frame-ledger door.
 * <p>The consumer pulls everything else (session context, pinned model routing,
 * budget snapshot) over the internal endpoints — no bulk payload rides the
 * broker, same posture as MockGenMessage.
 *
 * @param sessionId dialog session the run belongs to
 * @param assistantMessageId the streaming placeholder the writer must fill
 * @param projectId project segment (H1 isolation carried through the run)
 * @param userId asking user (re-checked at every access point)
 * @param prompt the user's question text
 * @param refObjects canonicalised @-mention chips of this question
 *
 * @param executionId failure-badge deep-link pointer folded in by the
 * core run door from the session whitelist context
 * —
 * an id only, the diagnosis itself is fetched over
 * the internal door so nothing bulky rides the broker
 * @param attachments canonicalised user-image attachments of this
 * question (AO batch P4 ; empty = no
 * images) — storage keys only, the bytes stay in
 * object storage, so the broker still carries no
 * bulky payload
 * @param systemManager vertical RBAC verdict decided once on the admin
 * session face ({@code SystemManagerRoles} single
 * source, AO batch S4 — a DTO field,
 * never a session re-lookup downstream (cross-service
 * identity rides fields, not request context). The
 * grounding trim map obeys it: manager-only facts are
 * absent from a member run's citations and prompt
 */
public record AiChatRunMessage(long sessionId, long assistantMessageId, long projectId,
                               long userId, String prompt, List<ChatObjectRef> refObjects,
                               Long executionId, List<ChatImageAttachment> attachments,
                               boolean systemManager) {

    /** Pre-identity-channel shape (old wire payloads): member view by default. */
    public AiChatRunMessage(long sessionId, long assistantMessageId, long projectId,
                            long userId, String prompt, List<ChatObjectRef> refObjects,
                            Long executionId, List<ChatImageAttachment> attachments) {
        this(sessionId, assistantMessageId, projectId, userId, prompt, refObjects,
                executionId, attachments, false);
    }

    /** Route key on the {@code testpike.events} topic exchange. */
    public static final String ROUTING_KEY = "ai.chat.run";
}
