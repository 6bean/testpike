package com.sixbean.testpike.core.session.api.dto;

/**
 * Request-header pair for reading one login-state ledger row over plain HTTP
 * (design doc M1 form two). The name/value pair is produced only by
 * SessionStateSupport so no consumer re-derives the transport grammar.
 * The value is secret material: callers must never log or echo it.
 */
public record SessionAuthHeader(String name, String value) {
}
