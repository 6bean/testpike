package com.sixbean.testpike.common.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;


public final class DesensitizeUtils {

    
    private static final Pattern PHONE_IN_TEXT = Pattern.compile("\\d{11}");

    
    private static final Pattern EMAIL_IN_TEXT = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /**
     * Credential shapes in free text (U-10 posture extended to document faces):
     * an {@code Authorization: Bearer <value>} style prefix keeps its scheme
     * word so the reader still sees WHICH slot was secret, the value collapses
     * to {@code ***}. Covers bearer/basic schemes and key=value shapes whose
     * name carries a secret facet (token/api-key/secret/password).
     */
    private static final Pattern CREDENTIAL_IN_TEXT = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+([A-Za-z0-9._+/-]{6,})"
                    + "|\\b([A-Za-z0-9_-]*(?:token|secret|password|api[-_]?key)[A-Za-z0-9_-]*)"
                    + "(\\s*[=:]\\s*)\"?([^\"'\\s,;}&]+)\"?");

    /**
     * The one sensitive-name spectrum shared by every read-out face: header
     * facets of the mock traffic plane (MaskingGate) and column facets of the
     * perf dataset preview (U-10) answer through this list, never a local copy.
     * Matching is a lowercase contains, so compound names (X-Api-Key,
     * access_token, client-secret) are caught by their facet marker.
     */
    private static final List<String> SENSITIVE_NAME_MARKERS =
            List.of("authorization", "cookie", "password", "secret", "token", "api-key");

    private DesensitizeUtils() {
    }

    /**
 * <p>
 * Fast paths keep the scan linear on provably unmatchable runs. Without
 * them the engine walks every start position and backtracks the whole run
 * once per position: a 21K-character repetitive body cost ~1.8 seconds per
 * masked field and froze servlet threads in the mock hit ledger. The
 * guards only skip when no match is possible (email needs '@' and '.',
 * phone needs eleven digits), so the masked output is unchanged.
 */
    public static String maskText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String phones = hasDigitRun(value, 11)
                ? PHONE_IN_TEXT.matcher(value).replaceAll(m -> maskPhone(m.group()))
                : value;
        if (phones.indexOf('@') < 0 || phones.indexOf('.') < 0) {
            return phones;
        }
        return EMAIL_IN_TEXT.matcher(phones)
                .replaceAll(m -> maskEmail(m.group()));
    }

    /** Linear pre-scan: true once {@code needed} digits appear anywhere. */
    private static boolean hasDigitRun(String value, int needed) {
        int seen = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i)) && ++seen >= needed) {
                return true;
            }
        }
        return false;
    }

    /**
     * Credential shapes in free text (document/export faces): bearer/basic
     * scheme keeps its word (the reader sees which slot was secret), the value
     * collapses to {@code ***}; key=value shapes whose name carries a secret
     * facet collapse the same way. The facet word may start the name too
     * (bare "api_key=..." or "token: ..." shapes are secrets as well).
     */
    public static String maskSecrets(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return CREDENTIAL_IN_TEXT.matcher(value).replaceAll(m -> {
            if (m.group(1) != null) {
                return m.group(1) + " ***";
            }
            return m.group(3) + m.group(4) + "***";
        });
    }

    
    public static String maskAccount(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim();
        if (v.length() <= 4) {
            return "*".repeat(v.length());
        }
        return v.substring(0, 2) + "***" + v.substring(v.length() - 2);
    }

    
    public static String maskPhone(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim();
        if (v.matches("\\d{11}")) {
            return v.substring(0, 3) + "****" + v.substring(7);
        }
        return maskAccount(v);
    }

    
    public static String maskEmail(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim();
        int at = v.indexOf('@');
        if (at <= 1) {
            return maskAccount(v);
        }
        return v.charAt(0) + "***" + v.substring(at);
    }

    /**
     * True when a header/column name carries a secret facet. Matching is a
     * contains over the single marker list above after folding the name to
     * lowercase with its separators unified ("API_KEY" and "X-Api-Key" both
     * land on their api-key facet), never a local copy of the list.
     */
    public static boolean isSensitiveName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String folded = name.toLowerCase(Locale.ROOT).replace('_', '-');
        return SENSITIVE_NAME_MARKERS.stream().anyMatch(folded::contains);
    }

    /**
     * One preview cell under the two-facet rule (U-10): an auth-shaped column
     * name values the cell wholesale ("***" — its shape would only leak length),
     * every other column keeps its text but runs the shared phone/email mask so
     * PII living in ordinary columns is still hidden. Safe cells pass through —
     * a preview that hides everything is a preview nobody can debug with.
     */
    public static String maskSensitiveCell(String columnName, String value) {
        if (isSensitiveName(columnName)) {
            return value == null ? null : "***";
        }
        return maskText(value);
    }
}
