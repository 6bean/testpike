package com.sixbean.testpike.common.exception;

/**
 * Entity-gone failure vocabulary (AJ batch noise-reduction segment,
 * business failure means "the target row no longer exists".
 * <p>Two consumers of this one vocabulary: the core dead-letter classifier
 * (rows that did reach the DLQ get the ENTITY_GONE class) and the MQ consume
 * guard in ai-service (runner-side noise reduction: such failures are acked
 * with a skip audit instead of burning retry quota and re-entering the
 * ledger). Before this class existed the token list lived only in
 * DeadLetterFailureClassifier; keeping a second copy in ai-service would be
 * the classic double-write (R40) — a drifted copy either mis-acks real
 * failures or mis-dead-letters already-gone entities.
 * <p>Vocabulary mirrors the real core rejection messages harvested on
 * 2026-11-06: Chinese business wording only. The loose English "not found"
 * form was removed the same day after the L4 dead-letter spectrum proved a
 * provider error body (HTTP 404 from the AI gateway) also carries that
 * wording, silently swallowing real failures into an entity-gone ack —
 * dead letters must never be swallowed. English-form core rejections now
 * match structurally via {@link BizErrorCode#NOT_FOUND} instead of text.
 * Matching is case-insensitive over each message of the cause chain.
 */
public final class EntityGoneSignals {

    
    private static final String[] ENTITY_GONE_TOKENS = {
            "不存在", "已被删除", "已删除"
    };

    private EntityGoneSignals() {
    }

    /** True when the (already lower-cased or raw) text carries an entity-gone token. */
    public static boolean matchesText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String token : ENTITY_GONE_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when any message in the throwable's cause chain carries an
     * entity-gone token, or any {@link ServiceException} in the chain is
     * structurally a core not-found rejection (Feign wraps core rejections
     * one level deeper; the code signal survives English wording without
     * relying on loose substrings).
     */
    public static boolean matches(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ServiceException e && e.getCode() != null
                    && e.getCode() == BizErrorCode.NOT_FOUND) {
                return true;
            }
            if (t != throwable && t.getMessage() == null) {
                continue;
            }
            if (matchesText(t.getMessage())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
