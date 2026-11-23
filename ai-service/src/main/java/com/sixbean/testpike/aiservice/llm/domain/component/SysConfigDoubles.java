package com.sixbean.testpike.aiservice.llm.domain.component;

/**
 * K8 system-config decimal reader, single source for the whole ai-service side
 * (posture of {@link SysConfigInts}: pure stateless function; the read itself
 * rides each caller's {@code CoreGateway.getSysConfig} per call — K8 hot
 * updates mean read-fresh, never cache).
 * <p>A decimal row is only trusted inside its declared interval: a blank row,
 * an unparseable value or an out-of-domain number all degrade to the caller's
 * seeded default, so one hand-typed bad value can never drag a whole feature
 * off its adjudicated range.
 */
public final class SysConfigDoubles {

    private SysConfigDoubles() {
    }

    /**
     * Parse a hot-config decimal; anything blank / non-numeric / outside
     * {@code [min, max]} degrades to the caller's fallback.
     */
    public static double parse(String raw, double fallback, double min, double max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // degrade to the seeded default — the flow must survive a bad config value
        }
        return fallback;
    }
}
