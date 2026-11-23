package com.sixbean.testpike.core.cluster.domain.component;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generate and digest pool-entry tokens (frozen-document security red line: plaintext appears only once in the issuance response).
 * Persist to database and compare using SHA-256 digests, and prohibit logging.
 */
@Component
public class EnrollTokenCodec {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generate a one-time plaintext token from 32 bytes of secure random encoded as base64url without padding. */
    public String newPlaintextToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

 /** Use a SHA-256 hex digest (lowercase 64 characters) consistent with the migration artifact token_digest column specification. */
    public String digest(String plaintext) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用（JDK 必备算法）", e);
        }
    }
}
