package com.sixbean.testpike.core.config.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 for the script-slice key registration : the three new
 * ai.script.* int keys must be members of the write-side range table, or the
 * value-range guard would silently skip them (an unregistered key reads as
 * "not part of this group" and no bound is enforced on save).
 */
class AiScriptKeyRangesSliceKeysTest {

    @Test
    void sliceKeysHaveRegisteredRanges() {
        assertArrayEquals(new int[]{0, 100},
                AiScriptKeyRanges.rangeOf("ai.script.slice-steps"),
                "slice width range must be 0~100 (0 = whole run, legacy shape)");
        assertArrayEquals(new int[]{800, 3600},
                AiScriptKeyRanges.rangeOf("ai.script.slice-timeout-seconds"),
                "slice timeout range must be 800~3600 (R56 per-hop floor)");
        assertArrayEquals(new int[]{0, 3},
                AiScriptKeyRanges.rangeOf("ai.script.slice-retry"),
                "slice retry range must be 0~3");
    }

    @Test
    void existingIntKeyRangesStayUnchanged() {
        assertArrayEquals(new int[]{1, 100}, AiScriptKeyRanges.rangeOf("ai.script.max-loop"));
        assertArrayEquals(new int[]{1, 500}, AiScriptKeyRanges.rangeOf("ai.script.max-steps"));
        assertArrayEquals(new int[]{1, 20}, AiScriptKeyRanges.rangeOf("ai.script.max-nest-depth"));
        assertArrayEquals(new int[]{0, 30}, AiScriptKeyRanges.rangeOf("ai.script.selfheal-rounds"));
        assertArrayEquals(new int[]{1, 300}, AiScriptKeyRanges.rangeOf("ai.script.assert-timeout-sec"));
        assertArrayEquals(new int[]{0, 60}, AiScriptKeyRanges.rangeOf("ai.script.default-wait-sec"));
        assertArrayEquals(new int[]{1, 3}, AiScriptKeyRanges.rangeOf("ai.script.screenshot-mode"));
        assertArrayEquals(new int[]{1, 2}, AiScriptKeyRanges.rangeOf("ai.script.fail-policy"));
        // bool keys stay outside the int table (parsed by the bool surface)
        assertNull(AiScriptKeyRanges.rangeOf("ai.script.smart-wait"));
    }

    @Test
    void sliceKeysBelongToScriptKeyGroup() {
        assertTrue(AiScriptKeyRanges.isScriptKey("ai.script.slice-steps"));
        assertTrue(AiScriptKeyRanges.isScriptKey("ai.script.slice-timeout-seconds"));
        assertTrue(AiScriptKeyRanges.isScriptKey("ai.script.slice-retry"));
    }
}
