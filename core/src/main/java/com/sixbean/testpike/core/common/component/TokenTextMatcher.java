package com.sixbean.testpike.core.common.component;

/**
 * Shared token-matching helper for failure classifiers.
 *
 * <p>External-call and dead-letter classifiers both classify exception text by
 * scanning for marker tokens; the loop lived twice, so R40 (one logic, one
 * source) pulls it here. Matching is case-sensitive on purpose: every token
 * list was calibrated against raw exception/log text.
 */
public final class TokenTextMatcher {

    private TokenTextMatcher() {
    }

    /** True when the haystack contains any of the marker tokens. */
    public static boolean containsAny(String haystack, String[] tokens) {
        for (String token : tokens) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
