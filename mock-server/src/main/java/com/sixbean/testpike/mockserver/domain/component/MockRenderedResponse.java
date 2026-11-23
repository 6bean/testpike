package com.sixbean.testpike.mockserver.domain.component;

import java.util.Map;

/**
 * One served stub response after the response builder consumed its payload
 * (design F3): status, headers and body are the final wire values — template
 * rendering already happened, so nothing downstream re-reads the raw payload.
 * A plain top-level record (the codebase bans inner classes).
 *
 * @param status  HTTP status to serve
 * @param headers response headers, already rendered
 * @param body    response body text, already rendered
 */
public record MockRenderedResponse(
        int status,
        Map<String, String> headers,
        String body) {

    public MockRenderedResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
    }
}
