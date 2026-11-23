package com.sixbean.testpike.common.notify;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;


public final class ReadTokenSigner {

    private ReadTokenSigner() {
    }

    
    public static String sign(String content, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("已读令牌密钥未配置（notify.feishu.callback.encrypt-key），拒签发");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("已读令牌签发失败: " + e.getMessage(), e);
        }
    }

    
    public static boolean verify(String content, String sig, String key) {
        if (content == null || sig == null || key == null
                || content.isBlank() || sig.isBlank() || key.isBlank()) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(sign(content, key));
            byte[] actual = Base64.getDecoder().decode(sig);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }
}
