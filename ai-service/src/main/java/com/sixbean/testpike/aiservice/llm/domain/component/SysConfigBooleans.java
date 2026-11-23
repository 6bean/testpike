package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 * K8 system-config boolean reader, single source for the whole ai-service side
 * (posture of {@link SysConfigInts}: pure stateless function; the read itself
 * still rides each caller's {@code CoreGateway.getSysConfig} per call — K8 hot
 * updates mean read-fresh, never cache).
 *
 * <p>Why both shapes: seed rows land as {@code '1'/'0'} (the core-side
 * {@code SystemConfigReader.getBoolean} domain) while hand-edited config-page
 * rows often carry {@code true/false}. A gate that compares one spelling
 * literally is a switch the user can flip on and stay off — the interface
 * pass-through write card died exactly that way (seed '1' vs a "true"
 * comparison). Value domain judgment is by shape, never by spelling, so every
 * consumer of a switch speaks the same verdict: "1"/"true" (case-insensitive,
 * trimmed) is on; null/blank/"0"/"false"/anything unrecognized is off.
 */
public final class SysConfigBooleans {

    private SysConfigBooleans() {
    }

    /** On only for "1" or "true" (trimmed, case-insensitive); everything else — including absent rows — is off. */
    public static boolean on(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /**
     * True only for the four honest spellings ("1"/"0"/true/false, trimmed,
     * case-insensitive). A reader that degrades loudly uses this to tell
     * "row absent or blank" (silent fallback — nobody touched it) apart from
     * "row present but I could not understand it" (warn line — the operator's
     * override was lost). Single source so no consumer re-spells the rule.
     */
    public static boolean isLegal(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim();
        return "1".equals(v) || "0".equals(v)
                || "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
    }

    /**
     * Tri-state read for keys that ship an explicit default (on/off/fallback):
     * "1"/"true" → on, "0"/"false" → off, null/blank/dirty value → the caller's
     * fallback. Same value-shape rule as {@link #on}, so a hand-edited dirty
     * row still cannot flip a switch — it only loses its override.
     */
    public static boolean parse(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String v = raw.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
            return false;
        }
        return fallback;
    }
}
