package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import com.sixbean.testpike.core.aichat.api.dto.ChatRunInfo;

import java.util.List;

/**
 * Opening a dialog run : one transaction that lands
 * both ledger rows, then one MQ dispatch. Kept separate from the frame ledger
 * on purpose — the ledger knows nothing about brokers, this service knows
 * nothing about seq allocation.
 */
public interface ChatRunService {

    /**
 * Open a run inside {@code sessionId} for {@code userId}: ownership is
 * re-checked at this access point (AF-g), the user echo is written sealed
 * as done, the assistant placeholder is opened streaming, and the run
 * request (with canonicalised @-mention chips, knife 4b) is published
 * after commit.
 *
 * @param refObjects raw chips from the wire — canonicalised here, the one
 * single validation point of the whole chain
 * @param attachments raw user-image attachments from the wire — likewise
 * canonicalised here , including the
 * project-slice check of every storage key
 * @param systemManager vertical verdict decided once on the admin session
 * face — forwarded verbatim
 * onto the run message; core never re-reads the session
 * @param questionContextJson per-question page anchor (BE batch
 * — validated at the ONE whitelist door the
 * session shell owns, journalled on the user ledger row
 * and folded into the run pointer before the
 * session-level value; null/blank keeps the bare shape
 */
    ChatRunInfo openRun(long sessionId, long userId, String prompt,
                        List<ChatObjectRef> refObjects,
                        List<ChatImageAttachment> attachments, boolean systemManager,
                        String questionContextJson);

    /** Pre-question-context door shape (old callers): no per-question anchor. */
    default ChatRunInfo openRun(long sessionId, long userId, String prompt,
                                List<ChatObjectRef> refObjects,
                                List<ChatImageAttachment> attachments, boolean systemManager) {
        return openRun(sessionId, userId, prompt, refObjects, attachments, systemManager, null);
    }

    /** Pre-identity-channel door shape (old wire bodies): member view default. */
    default ChatRunInfo openRun(long sessionId, long userId, String prompt,
                                List<ChatObjectRef> refObjects,
                                List<ChatImageAttachment> attachments) {
        return openRun(sessionId, userId, prompt, refObjects, attachments, false);
    }
}
