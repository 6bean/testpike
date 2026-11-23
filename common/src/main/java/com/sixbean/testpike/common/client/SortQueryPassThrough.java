package com.sixbean.testpike.common.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Single-source pass-through of sort parameters into internal HTTP query strings.
 * <p>Every bridge client that forwards {@code sortBy}/{@code sortDir} (or any optional
 * query value) must consume this helper — copying the encode-and-append block is
 * prohibited (shared-logic single source; a second consumer triggers the extraction).
 * Blank or null values append nothing; present values are trimmed and URL-encoded
 * as UTF-8.
 */
public final class SortQueryPassThrough {

    private SortQueryPassThrough() {
    }

    /** Appends {@code &sortBy=} / {@code &sortDir=} for whichever values are present. */
    public static void append(StringBuilder url, String sortBy, String sortDir) {
        appendParam(url, "sortBy", sortBy);
        appendParam(url, "sortDir", sortDir);
    }

    /** Appends one optional {@code &key=value} pair with URL-encoded value (blank/null adds nothing). */
    public static void appendParam(StringBuilder url, String key, String value) {
        if (value != null && !value.isBlank()) {
            url.append('&').append(key).append('=')
                    .append(URLEncoder.encode(value.trim(), StandardCharsets.UTF_8));
        }
    }
}
