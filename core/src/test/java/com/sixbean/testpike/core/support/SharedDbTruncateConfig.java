package com.sixbean.testpike.core.support;

/**
 * Externalized configuration for the shared-dev truncate fixture : the session
 * lock-wait bound and the retry budget are resolved from configuration sources, never from
 * call-site literals. Resolution order = system property {@code testpike.truncate.lockTimeoutSeconds}
 * &gt; environment {@code TEST_TRUNCATE_LOCK_TIMEOUT_SECONDS} &gt; shipped default (30s).
 */
public final class SharedDbTruncateConfig {

    /** System-property key overriding the session lock wait bound, seconds. */
    public static final String LOCK_TIMEOUT_PROPERTY = "testpike.truncate.lockTimeoutSeconds";

    /** Environment key overriding the session lock wait bound, seconds. */
    public static final String LOCK_TIMEOUT_ENV = "TEST_TRUNCATE_LOCK_TIMEOUT_SECONDS";

    /** Shipped fallback when neither override is present, seconds. */
    private static final int DEFAULT_LOCK_TIMEOUT_SECONDS = 30;

    private SharedDbTruncateConfig() {
    }

    /** Session lock-wait bound in seconds, honoring the system property &gt; env &gt; default order. */
    public static int resolveLockTimeoutSeconds() {
        String fromProperty = System.getProperty(LOCK_TIMEOUT_PROPERTY);
        Integer parsed = parsePositiveOrNull(fromProperty);
        if (parsed != null) {
            return parsed;
        }
        Integer fromEnv = parsePositiveOrNull(System.getenv(LOCK_TIMEOUT_ENV));
        return fromEnv != null ? fromEnv : DEFAULT_LOCK_TIMEOUT_SECONDS;
    }

    private static Integer parsePositiveOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }
}
