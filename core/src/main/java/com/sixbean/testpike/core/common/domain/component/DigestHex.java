package com.sixbean.testpike.core.common.domain.component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-256 hex digest shared component is for the migration artifact ruling that normalizes digest_hash CHAR(64) at the service layer.
 * This is the general entry point for the same algorithm used by the audit chain, idempotent, and handshake digest surfaces, and new code must not copy MessageDigest boilerplate.
 * <p>The output is lowercase 64-character hex, consistent with the baseline of {@code HexFormat.of}.
 *
 */
public final class DigestHex {

    private DigestHex() {
    }

    /** The SHA-256 of the UTF-8 source text is lowercase hex with 64 characters. */
    public static String sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    /** The SHA-256 of the raw bytes is lowercase hex with 64 characters for the contract file fingerprint surface. */
    public static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
