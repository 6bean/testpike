package com.sixbean.testpike.mockserver.domain.component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Request identity for the A-3 fallback cache .
 * <p>Two honesty jobs in one small kernel: numeric path segments collapse to
 * {@code {n}} so one paid answer serves the whole endpoint family (an order
 * detail endpoint asked twice with different ids must not buy the model
 * twice), and the body is masked through the shared {@link MaskingGate}
 * BEFORE it enters the digest — raw PII never leaves this process, not even
 * as hash pre-image, because the fingerprint travels to core in the answer
 * door payload.
 * <p>The digest is sha256 (not the fingerprint string itself) so the outbound
 * body carries a fixed-width opaque token; method and normalized path stay
 * readable fields — the cache key core builds pairs with the reader side on
 * exactly that shape.
 */
public class MockMissFingerprint {

    /** Normalize a path: every all-digits segment becomes {n} (family key). */
    public String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(segments[i].matches("\\d+") ? "{n}" : segments[i]);
        }
        return out.toString();
    }

    /**
     * Opaque digest of the masked request identity:
     * METHOD|normalized-path|sha256(masked-body). Deterministic per family.
     */
    public String fingerprint(String method, String path, String body) {
        String masked = MaskingGate.text(body == null ? "" : body);
        String joined = (method == null ? "GET" : method.trim().toUpperCase())
                + "|" + normalizePath(path) + "|" + masked;
        return sha256Hex(joined);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            // SHA-256 is a JDK-guaranteed algorithm; this cannot honestly
            // happen, and a fake digest must never be built out of nothing
            throw new IllegalStateException("sha-256 unavailable", ex);
        }
    }
}
