package com.sixbean.testpike.common.mock;

import java.util.List;
import java.util.Map;

/**
 * Immutable view of one incoming request reduced to what the matching kernel
 * compares against . The traffic plane builds it from the servlet
 * request; the kernel stays free of any transport type so the same adjudication
 * can be replayed in tests and in the request inspector.
 *
 * @param method HTTP verb as seen on the wire
 * @param path request path below the space routing root
 * @param queryParams query keys to their (possibly repeated) values
 * @param headers request headers; lookup is case-insensitive by contract
 * @param cookies parsed Cookie header by name
 * @param body raw payload (null when absent)
 * @param contentType Content-Type header value or null
 */
public record MockMatchRequest(
        String method,
        String path,
        Map<String, List<String>> queryParams,
        Map<String, String> headers,
        Map<String, String> cookies,
        String body,
        String contentType) {

    /** Case-insensitive header lookup on both sides of the comparison. */
    public String header(String name) {
        if (headers == null || name == null) {
            return null;
        }
        String wanted = name.toLowerCase();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase().equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
