package com.sixbean.testpike.api.notify.domain.component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.function.Supplier;


public final class FeishuCallbackVerifier {

    
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300L;

    private final Supplier<String> keySupplier;

    public FeishuCallbackVerifier(Supplier<String> keySupplier) {
        this.keySupplier = keySupplier;
    }

    
    public String encryptKey() {
        String key = keySupplier.get();
        return key == null ? "" : key;
    }

    
    public boolean verifyTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp.trim());
            if (ts > 10_000_000_000L) {
                ts = ts / 1000;
            }
            return Math.abs(System.currentTimeMillis() / 1000 - ts) <= TIMESTAMP_TOLERANCE_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    
    public boolean verify(String timestamp, String nonce, String body, String signature) {
        String key = encryptKey();
        if (key.isBlank()) {
            return false;
        }
        if (timestamp == null || nonce == null || body == null || signature == null) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(sign(timestamp, nonce, body, key));
            byte[] actual = Base64.getDecoder().decode(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    
    public static String sign(String timestamp, String nonce, String body, String key) {
        try {
            String data = timestamp + "\n" + nonce + "\n" + key + "\n" + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("飞书回调签名计算失败: " + e.getMessage(), e);
        }
    }
}
