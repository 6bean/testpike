package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class DesensitizeUtilsTest {

    @Test
    void maskAccount_keepsHeadTail() {
        assertEquals("ab***ef", DesensitizeUtils.maskAccount("abcdef"));
        assertEquals("****", DesensitizeUtils.maskAccount("abcd"));
    }

    @Test
    void maskPhone_elevenDigits() {
        assertEquals("138****5678", DesensitizeUtils.maskPhone("13812345678"));
    }

    @Test
    void maskEmail_keepsFirstCharAndDomain() {
        assertEquals("z***@example.com", DesensitizeUtils.maskEmail("zhangsan@example.com"));
    }

    @Test
    void maskText_masksPhoneAndEmailInsideText() {
        assertEquals("联系 138****5678 或 z***@example.com 咨询",
                DesensitizeUtils.maskText("联系 13812345678 或 zhangsan@example.com 咨询"));
        assertEquals("无敏感信息正文", DesensitizeUtils.maskText("无敏感信息正文"),
                "无命中=原样返回（入库零损）");
    }

    @Test
    void maskText_nullSafe() {
        assertNull(DesensitizeUtils.maskText(null));
        assertEquals("", DesensitizeUtils.maskText(""));
    }

    /**
     * Long runs of characters that cannot complete any rule used to force the
     * engine to backtrack the whole run once per start position (quadratic on
     * ~21K-character template bodies: the mock hit ledger froze servlet
     * threads for seconds per request). The guards must skip provably
     * unmatchable text in linear time while real matches still mask.
     */
    @Test
    void maskText_skipsUnmatchableLongTextInLinearTime() {
        String pad = "A".repeat(21000);
        long startedAt = System.nanoTime();
        assertEquals(pad, DesensitizeUtils.maskText(pad),
                "no digit and no '@': content must pass through unchanged");
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertTrue(elapsedMs < 500,
                "masking 21K unmatchable characters must not take " + elapsedMs + "ms");
        // A bounded pathological shape (one '@' at the very tail) must also stay fast.
        String tailAt = pad + "@";
        startedAt = System.nanoTime();
        assertEquals(tailAt, DesensitizeUtils.maskText(tailAt));
        elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertTrue(elapsedMs < 500,
                "lone trailing '@' must not re-trigger quadratic scanning: " + elapsedMs + "ms");
    }

    /**
     * Same fast-path discipline for the secret-shape scanner: without any
     * facet word and without '=' / ':' the regex provably cannot match, so
     * long bodies must not pay for per-position backtracking.
     */
    @Test
    void maskSecrets_skipsTextWithoutAnySecretFacetInLinearTime() {
        String pad = "A".repeat(21000);
        long startedAt = System.nanoTime();
        assertEquals(pad, DesensitizeUtils.maskSecrets(pad));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertTrue(elapsedMs < 500,
                "maskSecrets on 21K facet-free characters took " + elapsedMs + "ms");
    }

    @Test
    void isSensitiveName_matchesAuthFacetMarkersCaseInsensitively() {
        assertTrue(DesensitizeUtils.isSensitiveName("Authorization"));
        assertTrue(DesensitizeUtils.isSensitiveName("X-Api-Key"));
        assertTrue(DesensitizeUtils.isSensitiveName("cookie"));
        assertTrue(DesensitizeUtils.isSensitiveName("access_token"));
        assertTrue(DesensitizeUtils.isSensitiveName("client-secret"));
        assertFalse(DesensitizeUtils.isSensitiveName("username"));
        assertFalse(DesensitizeUtils.isSensitiveName("order_id"));
        assertFalse(DesensitizeUtils.isSensitiveName(null));
    }

    @Test
    void maskSensitiveCell_starsByColumnName_andMasksValueShapesOtherwise() {
        // column-name facet: an auth-shaped column name values the secret wholesale
        assertEquals("***", DesensitizeUtils.maskSensitiveCell("password", "hunter2"));
        assertEquals("***", DesensitizeUtils.maskSensitiveCell("API_KEY", "sk-live-123"));
        // value-shape facet: ordinary columns keep shape, phone/email still masked
        assertEquals("138****5678", DesensitizeUtils.maskSensitiveCell("user_phone", "13812345678"));
        assertEquals("联系 z***@example.com 咨询",
                DesensitizeUtils.maskSensitiveCell("note", "联系 zhangsan@example.com 咨询"));
        // honest pass-through for safe cells (preview must stay useful for debugging)
        assertEquals("ORD-1", DesensitizeUtils.maskSensitiveCell("orderId", "ORD-1"));
        assertNull(DesensitizeUtils.maskSensitiveCell("password", null));
    }

    /** Bare secret-facet names (no leading extra letter) must collapse too . */
    @Test
    void maskSecrets_coversBareFacetNames() {
        assertEquals("api_key=***", DesensitizeUtils.maskSecrets("api_key=ghpk1234567890"));
        assertEquals("token: ***", DesensitizeUtils.maskSecrets("token: abcdef123456"));
        assertEquals("password=***", DesensitizeUtils.maskSecrets("password=hi"));
        // names without any secret facet stay untouched
        assertEquals("orderId=ORD-1", DesensitizeUtils.maskSecrets("orderId=ORD-1"));
    }
}
