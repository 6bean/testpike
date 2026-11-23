package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.genkit.GenKit;

import java.util.Locale;
import java.util.Map;

/**
 * Shared format→genkit synthesis rules (single source for every deterministic
 * sample-value consumer — the request import mapper and the mock stub contract
 * mapper alike read this table; a second copy would let the two channels drift).
 * <p>Only formats with a verified genkit function are listed: an unknown
 * format yields no example (leave the value blank rather than invent one).
 * Seeds derive from a stable identity string, never the clock — same input,
 * same sample on every replay (channel-A determinism floor).
 */
public final class FormatGenSample {

    /** format (lowercase)→genkit function name; table is the whitelist. */
    private static final Map<String, String> FORMAT_TO_GEN_FUNCTION = Map.of(
            "phone", "phone",
            "id-card", "idCard",
            "date-time", "dateTime");

    private FormatGenSample() {
    }

    /** genkit function for a declared format, or null = no synthesis. */
    public static String functionFor(String format) {
        return format == null ? null
                : FORMAT_TO_GEN_FUNCTION.get(format.toLowerCase(Locale.ROOT));
    }

    /**
     * Deterministic sample value: seeded genkit call keyed on the field
     * identity (same field, same sample forever). Any synthesis failure
     * returns null — the caller leaves the value blank, never guesses.
     */
    public static String sample(String format, String identity) {
        String fn = functionFor(format);
        if (fn == null) {
            return null;
        }
        try {
            return GenKit.invoke(fn, Map.of("seed", seedFrom(identity)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Stable long seed from an identity string (SHA-256 head, order-independent). */
    public static long seedFrom(String identity) {
        return DigestHex.sha256(identity).substring(0, 15)
                .chars().reduce(0, (a, c) -> a * 31 + c);
    }
}
