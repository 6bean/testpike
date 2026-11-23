package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.stereotype.Component;

/**
 * Shared body-size bound for every mock-plane payload landing in core
 * .
 * <p>One gate serves the hit ledger AND the recording frame staging so the
 * truncation semantics can never fork (red line 6): oversized bodies keep
 * the cap-sized head and gain an honest tail note naming the original
 * length — the inspector must never silently display a partial document as
 * if it were complete. Bodies within the bound land verbatim.
 */
@Component
public class MockBodyTruncator {

    /** K8 hot-update key: max characters kept per body facet. */
    public static final String MAX_BODY_CHARS_KEY = "mock.hit.max-body-chars";

    /** Fallback cap when the key is missing (dev DB without a seeded row). */
    static final int DEFAULT_MAX_CHARS = 8192;

    private final SystemConfigReader configReader;

    public MockBodyTruncator(SystemConfigReader configReader) {
        this.configReader = configReader;
    }

    /** Cap a body text to the configured bound, appending a size-honest marker. */
    public String cap(String body) {
        if (body == null) {
            return null;
        }
        int max = configReader.getInt(MAX_BODY_CHARS_KEY, DEFAULT_MAX_CHARS);
        if (max <= 0 || body.length() <= max) {
            return body;
        }
        return body.substring(0, max) + " [truncated, " + body.length() + " chars]";
    }
}
