package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.net.SocketTimeoutException;

/**
 * single-source detector for the provider-timeout family,
 * shared by every budgeted group fan-out outside the doc plane (the spec
 * contract-extract loop and the judge question-generation job loop;
 * R40: one detector serves both, per-domain copies are forbidden).
 *
 * <p>Why a predicate and not a deadline clock: neither domain owns a
 * run-window component — the spec plane rides the global per-call ceiling
 * ({@code llm.timeout-seconds}) and the judge plane rides its own per-call
 * ceiling ({@code ai.judge.qa-gen.llm-timeout-seconds}) through
 * {@code LlmTimeoutResolver}. What both DO share is the shape of a spent
 * call: the fallback chain answers with an {@code AI_TIMEOUT} service
 * refusal, or a raw {@link SocketTimeoutException} surfaces from the
 * transport. That shape is what trips a {@link RunBudgetAbort}.
 *
 * <p>Fail-closed discipline: the detector only classifies, it never
 * swallows. Consumers trip the shared run signal on a match and rethrow
 * the original exception untouched, so the established abort vocabulary
 * propagates unchanged for every non-timeout failure.
 */
public final class LlmTimeouts {

    /** Cause-chain walk bound: a malformed self-referencing cause cycle
     *  must never spin the detector. */
    static final int MAX_CAUSE_DEPTH = 16;

    private LlmTimeouts() {
    }

    /** True when the failure — or any cause within the depth bound — is
     *  the provider-timeout family: an {@code AI_TIMEOUT} service refusal
     *  or a raw socket timeout surfacing from the transport layer. */
    public static boolean isTimeoutFamily(Throwable failure) {
        Throwable cursor = failure;
        for (int depth = 0; cursor != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cursor instanceof SocketTimeoutException) {
                return true;
            }
            if (cursor instanceof ServiceException refusal
                    && Integer.valueOf(BizErrorCode.AI_TIMEOUT).equals(refusal.getCode())) {
                return true;
            }
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        return false;
    }
}
