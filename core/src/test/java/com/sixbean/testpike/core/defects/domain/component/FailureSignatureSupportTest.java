package com.sixbean.testpike.core.defects.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


class FailureSignatureSupportTest {

    private final FailureSignatureSupport support = new FailureSignatureSupport();

    @Test
    void 动态数字耗时差异_归一同签名() {
        String a = support.signature(100L, 3, 2, "元素 .btn-submit 等待超时 12345ms 未出现");
        String b = support.signature(100L, 3, 2, "元素 .btn-submit 等待超时 987ms 未出现");
        assertEquals(a, b, "仅耗时数字不同必须归一同签名（防夜炸 50 单）");
    }

    @Test
    void 时间戳与UUID差异_归一同签名() {
        String a = support.signature(100L, 1, 1, "HTTP 500 at 2026-09-20T03:14:15.123 trace=550e8400-e29b-41d4-a716-446655440000");
        String b = support.signature(100L, 1, 1, "HTTP 500 at 2026-08-01T11:22:33.999 trace=9f8e7d6c-1b2a-4c3d-9e8f-0a1b2c3d4e5f");
        assertEquals(a, b, "时间戳/UUID 属动态成分，归一后同签名");
    }

    @Test
    void 错误本质不同_异签名() {
        String a = support.signature(100L, 3, 2, "元素 .btn-submit 等待超时");
        String b = support.signature(100L, 3, 2, "元素 .btn-submit 被遮挡无法点击");
        assertNotEquals(a, b, "错误文本本质不同必须分单");
    }

    @Test
    void 用例或失败步或错误类别不同_异签名() {
        String base = support.signature(100L, 3, 2, "断言失败 期望A 实际B");
        assertNotEquals(base, support.signature(101L, 3, 2, "断言失败 期望A 实际B"), "case 维度独立去重");
        assertNotEquals(base, support.signature(100L, 4, 2, "断言失败 期望A 实际B"), "failStep 入签");
        assertNotEquals(base, support.signature(100L, 3, 1, "断言失败 期望A 实际B"), "errorType 入签");
    }

    @Test
    void 空详情与超长详情_签名稳定不抛() {
        assertNotNull(support.signature(1L, 0, null, null));
        assertNotNull(support.signature(1L, 0, null, ""));
        String long1 = "超时".repeat(500);
        assertEquals(support.signature(1L, 0, 3, long1), support.signature(1L, 0, 3, long1));
    }

    @Test
    void normalize_数字归一单字符保长度无关() {
        assertEquals("abc#def#", support.normalize("abc123def4567"));
        assertEquals(support.normalize("took 1.5s"), support.normalize("took 99.99s"));
    }
}
