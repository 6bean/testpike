package com.sixbean.testpike.core.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared R63 three-element checker for refusal-wording contract tests. One
 * implementation for every domain's refusal L2 (promotion doors, mock save
 * gate, ...): the offending value is echoed, the repair entry is quoted with
 * its on-screen label, and an action word tells the user what to do next.
 *
 * <p>Single source by design — a second copy in another test class would let
 * the contract drift apart per domain while the gate still reported green.
 */
public final class RefusalGuidanceAssertions {

    private RefusalGuidanceAssertions() {
    }

    /**
     * Fails unless the sentence echoes every offending value, quotes at least
     * one on-screen entry label and carries an action word for the next step.
     */
    public static void assertThreeElements(String sentence, String... echoedValues) {
        assertFalse(sentence.isBlank(), "refusal sentence must not be blank");
        for (String value : echoedValues) {
            assertTrue(sentence.contains(value),
                    "offending value not echoed: " + value + " in [" + sentence + "]");
        }
        assertTrue(sentence.contains("「"),
                "repair entry label must be quoted: [" + sentence + "]");
        assertTrue(sentence.contains("请") || sentence.contains("可点") || sentence.contains("建议"),
                "action word missing: [" + sentence + "]");
    }
}
