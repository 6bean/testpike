package com.sixbean.testpike.common.util;

import java.util.Locale;
import java.util.Set;

/**
 * Transport-protocol vocabulary for API test requests (single source for the
 * tp_apitest_request.protocol column and the ws dispatch line of the envelope).
 * <p> (W1c fix-3): WebSocket requests need a first-class transport
 * discriminator so the executor can branch on protocol=ws instead of users
 * typing ws:// into an HTTP form and hoping. Only two transports exist today;
 * anything else fails closed at the save gate.
 * <p>Mirror consumers, never a second verdict copy: the core save gate
 * normalizes and refuses via this class, the front-end dropdown mirrors the
 * same vocabulary, and the envelope assembler reads the stored value as-is.
 * <p>Pure static and stateless: normalize returns null for an unknown value
 * (the caller wraps the human error message with error code).
 */
public final class ApiWsContract {

    /** Transport domain: plain HTTP requests or WebSocket sessions. */
    public static final Set<String> PROTOCOLS = Set.of("http", "ws");

    /** Default transport for legacy rows and absent input (zero-drift backfill). */
    public static final String DEFAULT_PROTOCOL = "http";

    /** WebSocket assertion families (received / order / frequency). */
    public static final Set<String> WS_ASSERTION_TYPES =
            Set.of("ws.receive", "ws.order", "ws.frequency");

    private ApiWsContract() {
        // static contract class, never instantiated
    }

    /**
     * Normalize a transport value: blank/null becomes the http default, a known
     * value is lowercased, anything else returns null (caller names the offender).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PROTOCOL;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return PROTOCOLS.contains(value) ? value : null;
    }
}
