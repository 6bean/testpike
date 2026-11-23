package com.sixbean.testpike.core.aichat.domain.component;

import java.util.List;

/**
 * Result of folding one question's page anchor into the session context
 * . {@code changed=false} means the session row
 * already carries exactly these anchors, so the service door skips the
 * UPDATE (R28: an empty replay must never touch rows); in that case
 * {@code mergedJson} is null — there is nothing to write.
 */
public record ChatSessionContextMergeOutcome(boolean changed, String mergedJson) {

    private static final ChatSessionContextMergeOutcome NO_CHANGE =
            new ChatSessionContextMergeOutcome(false, null);

    /** Shared "nothing moved" verdict — the merge door returns it for no-ops. */
    public static ChatSessionContextMergeOutcome noChange() {
        return NO_CHANGE;
    }

    /** The anchor keys a question may move on the session context. */
    public static final List<String> OVERRIDE_KEYS =
            List.of("route", "projectId", "objectId", "executionId", "analysisId", "documentId");
}
