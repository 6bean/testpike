package com.sixbean.testpike.api.notify.domain.component;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FeishuCallbackVerifierTest {

    private static final String KEY = "unit-test-encrypt-key";

    @Test
    void 合法签名验签通过_与官方算法一致() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = "nonce-1";
        String body = "{\"event_id\":\"e1\"}";
        String sig = FeishuCallbackVerifier.sign(ts, nonce, body, KEY);
        FeishuCallbackVerifier verifier = new FeishuCallbackVerifier(() -> KEY);
        assertTrue(verifier.verify(ts, nonce, body, sig));
        assertEquals(44, sig.length(), "HMAC-SHA256 的 Base64 长度=44");
    }

    @Test
    void 体被篡改_验签失败() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = FeishuCallbackVerifier.sign(ts, "n", "original", KEY);
        FeishuCallbackVerifier verifier = new FeishuCallbackVerifier(() -> KEY);
        assertFalse(verifier.verify(ts, "n", "tampered", sig), "body 参与签名，篡改必拒");
    }

    @Test
    void 密钥缺省或空白_failClosed拒绝一切() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sig = FeishuCallbackVerifier.sign(ts, "n", "b", KEY);
        assertFalse(new FeishuCallbackVerifier(() -> "").verify(ts, "n", "b", sig),
                "密钥未配置=拒绝一切回调（SSO 先例 fail-closed）");
        assertFalse(new FeishuCallbackVerifier(() -> null).verify(ts, "n", "b", sig));
    }

    @Test
    void 时间窗外拒绝_窗内与毫秒兼容() {
        FeishuCallbackVerifier verifier = new FeishuCallbackVerifier(() -> KEY);
        long now = System.currentTimeMillis() / 1000;
        assertTrue(verifier.verifyTimestamp(String.valueOf(now)), "当前秒级时间戳在窗内");
        assertTrue(verifier.verifyTimestamp(String.valueOf(now * 1000)), "毫秒单位兼容");
        assertFalse(verifier.verifyTimestamp(String.valueOf(now - 301)), "超 5min 容差=拒");
        assertFalse(verifier.verifyTimestamp(String.valueOf(now + Duration.ofMinutes(6).toSeconds())),
                "未来超容差=拒");
        assertFalse(verifier.verifyTimestamp("abc"), "非数字=拒");
        assertFalse(verifier.verifyTimestamp(""), "空=拒");
    }

    @Test
    void 不同密钥签名互不通用() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String sigOtherKey = FeishuCallbackVerifier.sign(ts, "n", "b", "other-key");
        FeishuCallbackVerifier verifier = new FeishuCallbackVerifier(() -> KEY);
        assertFalse(verifier.verify(ts, "n", "b", sigOtherKey));
        assertNotEquals(sigOtherKey, FeishuCallbackVerifier.sign(ts, "n", "b", KEY));
    }

    @Test
    void 非法Base64签名_拒放行不抛穿() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        FeishuCallbackVerifier verifier = new FeishuCallbackVerifier(() -> KEY);
        assertFalse(verifier.verify(ts, "n", "b", "!!!not-base64!!!"),
                "fail-closed：解码异常=验签失败，绝不抛穿透回调端点");
    }
}
