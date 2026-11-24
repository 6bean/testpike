package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Component;

/**
 * Single reader for the AV batch K8 governance keys (session.* family,
 * seeded by V504). Every capture/consumption window rides these hot keys —
 * callers must never hardcode a timeout, a lead window or the retention day
 * count. Parsing is fail-soft: blank, garbage or out-of-window values answer
 * the factory default mirrored here (the seed rows carry the same numbers).
 * Two keys pass an explicit zero through on purpose: the capture window
 * (0 = void the session immediately, the operator kill switch) and the
 * retention days (0 = the sweeper's "keep everything" refuse branch, the
 * single decision point — an explicit 0 must never widen a purge).
 *
 * <p>Values are read fresh on every call through ConfigService, so a save on
 * the configuration page takes effect without a restart.
 */
@Component
public class SessionGovernanceKeys {

    public static final String KEY_CAPTURE_TIMEOUT_MINUTES = "session.capture-timeout-minutes";
    public static final String KEY_VALIDATE_TIMEOUT_MS = "session.validate-timeout-ms";
    public static final String KEY_RENEW_LEAD_MINUTES = "session.renew-lead-minutes";
    public static final String KEY_RETENTION_DAYS = "session.retention-days";
    public static final String KEY_HEADLESS_HOST_CAPTURE_ENABLED = "session.headless-host-capture.enabled";

    /** Keep-alive family (XC batch), seeded by V655. */
    public static final String KEY_KEEPALIVE_ENABLED = "session.keepalive.enabled";
    public static final String KEY_KEEPALIVE_INTERVAL_MINUTES = "session.keepalive.interval-minutes";
    public static final String KEY_KEEPALIVE_MAX_PER_TICK = "session.keepalive.max-per-tick";
    public static final String KEY_KEEPALIVE_MAX_FAIL_STREAK = "session.keepalive.max-fail-streak";

    /** Factory defaults; must mirror the V504 seed rows (drift = seed contract red). */
    public static final int DEFAULT_CAPTURE_TIMEOUT_MINUTES = 10;
    public static final int DEFAULT_VALIDATE_TIMEOUT_MS = 8000;
    public static final int DEFAULT_RENEW_LEAD_MINUTES = 15;
    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_KEEPALIVE_INTERVAL_MINUTES = 10;
    public static final int DEFAULT_KEEPALIVE_MAX_PER_TICK = 50;
    public static final int DEFAULT_KEEPALIVE_MAX_FAIL_STREAK = 6;

    private final ConfigService configService;

    public SessionGovernanceKeys(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Capture session window upper bound in minutes (0..240). An explicit 0
     * passes through as "void immediately" (the hot flip is the operator's
     * kill switch, mirrored by the L3 state-B probe); blank, garbage or
     * out-of-window values answer the factory default.
     */
    public int captureTimeoutMinutes() {
        return parseInt(KEY_CAPTURE_TIMEOUT_MINUTES,
                configService.getValue(KEY_CAPTURE_TIMEOUT_MINUTES, null),
                DEFAULT_CAPTURE_TIMEOUT_MINUTES, 0, 240);
    }

    /** Executor-side validity probe timeout in milliseconds (500..60000). */
    public int validateTimeoutMs() {
        return parseInt(KEY_VALIDATE_TIMEOUT_MS,
                configService.getValue(KEY_VALIDATE_TIMEOUT_MS, null),
                DEFAULT_VALIDATE_TIMEOUT_MS, 500, 60000);
    }

    /** Renew reminder lead window in minutes (0..1440). */
    public int renewLeadMinutes() {
        return parseInt(KEY_RENEW_LEAD_MINUTES,
                configService.getValue(KEY_RENEW_LEAD_MINUTES, null),
                DEFAULT_RENEW_LEAD_MINUTES, 0, 1440);
    }

    /**
     * Retention window for swept-away invalid ledger rows, in days. Only
     * unparsable input answers the factory 30; an explicit 0 or negative
     * rides through so the sweeper can honour "keep everything".
     */
    public int retentionDays() {
        return parseRetentionDays(configService.getValue(KEY_RETENTION_DAYS, null));
    }

    /** Secondary-path capture window shell switch; only an explicit on reads true. */
    public boolean headlessHostCaptureEnabled() {
        return parseEnabled(configService.getValue(KEY_HEADLESS_HOST_CAPTURE_ENABLED, null));
    }

    /**
     * Keep-alive global gate. The seed ships an enabled default (a fresh
     * install keeps idle sessions alive out of the box), so anything but an
     * explicit off value reads true; blank/garbage fall back to the factory
     * on, only "0" or "false" park the whole round.
     */
    public boolean keepaliveEnabled() {
        String raw = configService.getValue(KEY_KEEPALIVE_ENABLED, null);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String v = raw.trim();
        return !("0".equals(v) || "false".equalsIgnoreCase(v));
    }

    /** Global keep-alive cadence in minutes (5..1440); a row override may beat it. */
    public int keepaliveIntervalMinutes() {
        return parseInt(KEY_KEEPALIVE_INTERVAL_MINUTES,
                configService.getValue(KEY_KEEPALIVE_INTERVAL_MINUTES, null),
                DEFAULT_KEEPALIVE_INTERVAL_MINUTES, 5, 1440);
    }

    /** Upper bound of probes one scheduler round may fire (1..500). */
    public int keepaliveMaxPerTick() {
        return parseInt(KEY_KEEPALIVE_MAX_PER_TICK,
                configService.getValue(KEY_KEEPALIVE_MAX_PER_TICK, null),
                DEFAULT_KEEPALIVE_MAX_PER_TICK, 1, 500);
    }

    /** Consecutive UNKNOWN/FAILED rounds that park a row's keep-alive (1..20). */
    public int keepaliveMaxFailStreak() {
        return parseInt(KEY_KEEPALIVE_MAX_FAIL_STREAK,
                configService.getValue(KEY_KEEPALIVE_MAX_FAIL_STREAK, null),
                DEFAULT_KEEPALIVE_MAX_FAIL_STREAK, 1, 20);
    }

    /** Hot value -> int with a legal window; anything else is the factory default. */
    public static int parseInt(String key, String raw, int factory, int min, int max) {
        Integer parsed = tryParse(raw);
        if (parsed == null || parsed < min || parsed > max) {
            return factory;
        }
        return parsed;
    }

    /** Retention-specific guard: fail-soft to the factory only when unparsable. */
    public static int parseRetentionDays(String raw) {
        Integer parsed = tryParse(raw);
        return parsed == null ? DEFAULT_RETENTION_DAYS : parsed;
    }

    /** Switch semantics shared by the 0/1 seeded flags. */
    public static boolean parseEnabled(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static Integer tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
