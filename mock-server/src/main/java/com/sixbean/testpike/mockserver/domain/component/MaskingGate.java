package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.util.DesensitizeUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The single PII gate of the traffic plane : the hit ledger
 * and the recording frames buffer the exact same masked shape — sensitive
 * header names star-valued, free text run through the shared desensitizer.
 * Extracted because both recorders consume it (reuse rule: two call sites
 * share one implementation, never a copy).
 * <p>Since U-10 the sensitive-NAME spectrum itself lives in
 * {@link DesensitizeUtils#isSensitiveName} — the mock headers and the perf
 * dataset preview columns answer one shared list, never two that can drift.
 * <p>Masking is applied at record time, before anything is buffered, so an
 * unmasked value can never escape this process.
 */
public final class MaskingGate {

    private MaskingGate() {
    }

    /** Free-text facet (bodies, traces): phone/email desensitized, rest untouched. */
    public static String text(String value) {
        return DesensitizeUtils.maskText(value);
    }

    /** Header facet: lowercase names, sensitive values star-valued. */
    public static Map<String, String> headers(Map<String, String> headers) {
        if (headers == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            out.put(name, DesensitizeUtils.isSensitiveName(name) ? "***" : v);
        });
        return out;
    }

    /** Query facet: one masked value per key (multi-valued keys joined upstream). */
    public static Map<String, String> query(Map<String, String> query) {
        if (query == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        query.forEach((k, v) -> out.put(k, text(v)));
        return out;
    }
}
