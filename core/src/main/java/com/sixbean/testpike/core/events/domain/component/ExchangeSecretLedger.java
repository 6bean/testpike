package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Single-source vocabulary and shapes for the secret material that may show
 * up in an exchange trail (login-state probe / credential test doors).
 *
 * <p>Red line held: plaintext credentials never enter the process-event
 * stream. What the trail may carry for a sensitive value is a masked display
 * form (eight-character prefix + total length) plus, where the caller wants a
 * later reveal, the value sealed as {@code ENC:}-prefixed ciphertext through
 * the shared ConfigCipher. The vocabulary lives here only - no door may keep
 * its own copy of the word list.
 */
@Component
public class ExchangeSecretLedger {

    /** Storage marker of a sealed value (AES-GCM ciphertext body follows). */
    public static final String SEAL_PREFIX = "ENC:";
    /** Prefix budget shown in the masked display form. */
    static final int MASK_PREFIX_CHARS = 8;

    /** Closed header family - exact names, case-insensitive. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization", "www-authenticate");

    /** Body field word stems - a field name containing one is treated as secret. */
    private static final Set<String> SENSITIVE_BODY_STEMS = Set.of(
            "password", "token", "secret", "accesskey", "refresh");

    private final ConfigCipher cipher;

    public ExchangeSecretLedger(ConfigCipher cipher) {
        this.cipher = cipher;
    }

    /** True for the five secret-carrying response/request header families. */
    public static boolean isSensitiveHeader(String name) {
        return name != null && SENSITIVE_HEADERS.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * True when a body field name matches a secret stem. Names are compared
     * with non-alphanumeric characters dropped so access_key and accessKey
     * hit the same stem (better over-masked than leaked).
     */
    public static boolean isSensitiveBodyField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String normalized = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String stem : SENSITIVE_BODY_STEMS) {
            if (normalized.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Masked display form: first eight characters + total length. Values at
     * or below the prefix budget lose the readable prefix entirely.
     */
    public String mask(String value) {
        int length = value == null ? 0 : value.length();
        String head = length > MASK_PREFIX_CHARS
                ? value.substring(0, MASK_PREFIX_CHARS) + "…"
                : "***";
        return head + "（总长度 " + length + "）";
    }

    /**
     * Ciphertext form of a sensitive value for the sealed column. Without a
     * usable key the value is not stored at all and the marker says why -
     * plaintext never rides along as a fallback.
     */
    public String seal(String value) {
        if (!cipher.isEnabled()) {
            return "（加密服务未配置，密文未留存）";
        }
        return SEAL_PREFIX + cipher.encrypt(value);
    }

    /** True when the stored token is this ledger's ciphertext shape. */
    public boolean isSealed(String token) {
        return token != null && token.startsWith(SEAL_PREFIX);
    }

    /**
     * Turns a sealed token back into its value (the reveal door uses this at
     * read time, gated by login + project membership + an audit row).
     */
    public String unseal(String sealedToken) {
        if (!isSealed(sealedToken)) {
            throw new ServiceException("该字段不是密文留存形态（" + SEAL_PREFIX + " 开头），无法解显。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return cipher.decrypt(sealedToken.substring(SEAL_PREFIX.length()));
    }
}
