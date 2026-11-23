package com.sixbean.testpike.core.audit.domain.component;

import com.sixbean.testpike.core.audit.domain.component.AuditHashChain;


import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AuditHashChainTest {

    @Test
    void canonical_同输入输出稳定() {
        String a = AuditHashChain.canonical(1L, 2L, "case", "10", "review", "{}", "{}");
        String b = AuditHashChain.canonical(1L, 2L, "case", "10", "review", "{}", "{}");
        assertEquals(a, b, "同输入必须同 canonical 输出");
    }

    @Test
    void canonical_字段边界防歧义_字段值含分隔符不碰撞() {
        String s1 = AuditHashChain.canonical(1L, null, "a|b", "c", "x", null, null);
        String s2 = AuditHashChain.canonical(1L, null, "a", "b|c", "x", null, null);
        assertNotEquals(s1, s2, "字段值内含分隔符不得与其他字段组合碰撞");
    }

    @Test
    void canonical_空值统一为空串_不产生歧义() {
        String s1 = AuditHashChain.canonical(null, null, "case", "1", "create", null, null);
        String s2 = AuditHashChain.canonical(0L, 0L, "case", "1", "create", "", "");
        assertNotEquals(s1, s2, "null 与显式 0/空串必须可区分");
    }

    @Test
    void hash_与标准sha256一致() throws NoSuchAlgorithmException {
        String prev = "0".repeat(64);
        String content = "1\u00012\u0001case\u000110\u0001review\u0001{}\u0001{}";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest((prev + content).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, AuditHashChain.hash(prev, content), "hash 必须等于标准 SHA-256 hex");
    }

    @Test
    void hash_内容变更哈希必变() {
        String prev = "0".repeat(64);
        String h1 = AuditHashChain.hash(prev, "content-A");
        String h2 = AuditHashChain.hash(prev, "content-B");
        assertNotEquals(h1, h2, "内容变更哈希必须变化");
    }

    @Test
    void hash_输出为64位hex() {
        String h = AuditHashChain.hash("0".repeat(64), "任意内容");
        assertEquals(64, h.length(), "SHA-256 hex 必须 64 字符");
        assertTrue(h.matches("[0-9a-f]{64}"), "必须为小写 hex");
    }

    @Test
    void CHAIN_HEAD_为64个零() {
        assertEquals(64, AuditHashChain.CHAIN_HEAD.length());
        assertTrue(AuditHashChain.CHAIN_HEAD.matches("0{64}"));
    }

    @Test
    void hash_入参null抛异常() {
        assertThrows(NullPointerException.class, () -> AuditHashChain.hash(null, "x"));
        assertThrows(NullPointerException.class, () -> AuditHashChain.hash("0".repeat(64), null));
    }
}
