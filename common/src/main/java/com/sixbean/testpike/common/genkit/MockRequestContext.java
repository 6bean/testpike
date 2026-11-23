package com.sixbean.testpike.common.genkit;

import java.util.List;
import java.util.Map;

/**
 * Mock request snapshot a response template resolves against.
 * The traffic plane (mock-server) builds one per served request; the kernel
 * never mutates it. Header keys arrive lowercase-normalised; query values are
 * multi-valued and templates echo the first value of a key.
 *
 * @param method   HTTP method of the served request
 * @param path     full request path under the space prefix
 * @param pathVars {var} captures from the winning template path (echo source)
 * @param query    query parameters, key → values in arrival order
 * @param headers  request headers, lowercase key → single value
 * @param bodyJson raw request body (JSON text or null/blank when absent)
 */
public record MockRequestContext(
        String method,
        String path,
        Map<String, String> pathVars,
        Map<String, List<String>> query,
        Map<String, String> headers,
        String bodyJson) {

    public MockRequestContext {
        pathVars = pathVars == null ? Map.of() : Map.copyOf(pathVars);
        query = query == null ? Map.of() : Map.copyOf(query);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
