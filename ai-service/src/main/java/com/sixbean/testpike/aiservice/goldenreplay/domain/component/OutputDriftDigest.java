package com.sixbean.testpike.aiservice.goldenreplay.domain.component;

import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Normalized digests for golden-set drift comparison. A model output drifts
 * when its normalized form changes: surrounding and repeated whitespace is
 * noise the comparison must not count, everything else is content. Digests
 * are lowercase SHA-256 hex — the exact shape the core drift ledger validates
 * for pair forms and pack snapshots.
 */
public final class OutputDriftDigest {

    private OutputDriftDigest() {
    }

    /** Collapse whitespace runs to single spaces and trim; null reads as empty. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    /** Lowercase sha-256 hex of the normalized output ("" for null/blank). */
    public static String digestOf(String raw) {
        return sha256Hex(normalize(raw));
    }

    /**
     * Content fingerprint of one golden pack version: sorted by sample id so
     * the digest pins WHICH samples (and their input/expected texts), never
     * the order they happened to be read in.
     */
    public static String packDigest(List<JudgeSampleBrief> samples) {
        String canonical = samples.stream()
                .sorted((a, b) -> Long.compare(a.id() == null ? 0L : a.id(),
                        b.id() == null ? 0L : b.id()))
                .map(s -> s.id() + "|" + normalize(s.input()) + "|" + normalize(s.expected()))
                .collect(Collectors.joining("\n"));
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must exist in every JDK", impossible);
        }
    }
}
