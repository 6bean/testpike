package com.sixbean.testpike.core.config.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;


@Component
public class ConfigCipher {

 
    public static final String MASK = "******";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;

    private final SecretKeySpec secretKey;
    private final boolean enabled;

    public ConfigCipher(@Value("${CONFIG_AES_KEY:}") String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            this.secretKey = null;
            this.enabled = false;
        } else {
            String trimmed = hexKey.trim();
            // Validate before parsing: a non-hex value used to die inside
            // Integer.parseInt as a bare NumberFormatException deep in the
            // Spring wiring, and a hex value of the wrong length was silently
            // demoted to "cipher disabled", making later refusals claim the key
            // is missing when it is present but malformed. Fail fast, name the
            // key and the expected shape, never echo the value.
            if (!trimmed.matches("(?i)[0-9a-f]{32}|[0-9a-f]{48}|[0-9a-f]{64}")) {
                throw new IllegalStateException("CONFIG_AES_KEY must be a hex string "
                        + "of exactly 32, 48 or 64 characters (a 16/24/32-byte AES key). "
                        + "The configured value is not valid hex of an accepted length. "
                        + "Generate one with: openssl rand -hex 32, set it in the "
                        + "deployment .env under the key CONFIG_AES_KEY, then restart "
                        + "the services. The key value itself is never logged.");
            }
            byte[] raw = hexToBytes(trimmed);
            this.secretKey = new SecretKeySpec(raw, "AES");
            this.enabled = raw.length == 16 || raw.length == 24 || raw.length == 32;
        }
    }

    
    public boolean isEnabled() {
        return enabled;
    }

    
    public String encrypt(String plain) {
        requireEnabled();
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(4 + IV_LENGTH + ct.length)
                            .putInt(IV_LENGTH).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new ServiceException("敏感配置加密失败", BizErrorCode.INTERNAL_ERROR);
        }
    }

    
    public String decrypt(String cipherText) {
        requireEnabled();
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(cipherText));
            int ivLen = buf.getInt();
            byte[] iv = new byte[ivLen];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ServiceException("敏感配置解密失败", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法处理敏感配置", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
