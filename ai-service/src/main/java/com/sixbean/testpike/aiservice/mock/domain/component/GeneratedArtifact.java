package com.sixbean.testpike.aiservice.mock.domain.component;

import java.util.Map;

/**
 * The store-ready shape of one generated fallback answer after structural
 * validation : status/headers/body exactly as the core read
 * side's isObject gate expects — writers and readers pair on one shape.
 *
 * @param status validated HTTP status (100..599 by the time this exists)
 * @param headers response headers of the generated answer
 * @param body response body as text (never null; "" allowed)
 */
public record GeneratedArtifact(int status, Map<String, String> headers, String body) {
}
