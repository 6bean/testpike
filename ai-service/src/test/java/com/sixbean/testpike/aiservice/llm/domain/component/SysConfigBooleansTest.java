package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Value-domain tests of the single-source switch reader: a gate must open for
 * every honest "on" spelling the config table can carry ('1' and 'true', any
 * case, padded) and stay closed for '0'/'false'/blank/absent. The interface
 * pass-through write card was suppressed exactly because a gate compared one
 * spelling literally while the seed row used the other — never regress to
 * spelling-bound switches again.
 */
class SysConfigBooleansTest {

    @Test
    void numericOnShapeOpens() {
        assertTrue(SysConfigBooleans.on("1"));
        assertTrue(SysConfigBooleans.on(" 1 "));
    }

    @Test
    void wordOnShapeOpens() {
        assertTrue(SysConfigBooleans.on("true"));
        assertTrue(SysConfigBooleans.on("TRUE"));
        assertTrue(SysConfigBooleans.on(" True "));
    }

    @Test
    void offAndAbsentShapesStayClosed() {
        assertFalse(SysConfigBooleans.on("0"));
        assertFalse(SysConfigBooleans.on("false"));
        assertFalse(SysConfigBooleans.on("FALSE"));
        assertFalse(SysConfigBooleans.on(""));
        assertFalse(SysConfigBooleans.on("  "));
        assertFalse(SysConfigBooleans.on(null));
        assertFalse(SysConfigBooleans.on("yes"));
        assertFalse(SysConfigBooleans.on("2"));
    }

    /**
 * Legality shape used by warn-on-dirty readers :
 * the four honest spellings are legal in any case/padding; blank, absent
 * and anything else are dirty — so a caller can distinguish "no override"
 * from "override I could not understand" without re-spelling the rule.
 */
    @Test
    void isLegalAcceptsOnlyTheFourHonestSpellings() {
        assertTrue(SysConfigBooleans.isLegal("1"));
        assertTrue(SysConfigBooleans.isLegal("0"));
        assertTrue(SysConfigBooleans.isLegal("true"));
        assertTrue(SysConfigBooleans.isLegal(" FALSE "));
        assertTrue(SysConfigBooleans.isLegal("True"));
        assertFalse(SysConfigBooleans.isLegal("yes"));
        assertFalse(SysConfigBooleans.isLegal("2"));
        assertFalse(SysConfigBooleans.isLegal(""));
        assertFalse(SysConfigBooleans.isLegal("   "));
        assertFalse(SysConfigBooleans.isLegal(null));
    }
}
