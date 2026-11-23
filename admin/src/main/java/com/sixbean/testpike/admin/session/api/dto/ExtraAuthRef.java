package com.sixbean.testpike.admin.session.api.dto;

/**
 * Admin mirror of the core ExtraAuthRef record (same component names so the
 * JSON rides the bridge untouched). One combined-session header row: it
 * references a credential already registered in the project vault by
 * {@code credKey} and never carries a value; {@code valueTemplate} is an
 * optional prefix template in the shared {@code {{CRED:key}}} grammar.
 * Validation (unknown reference, primary-header clash, in-list duplicate,
 * non-header destination, per-state cap) lives in core — this layer maps.
 */
public record ExtraAuthRef(String credKey, String into, String headerName, String valueTemplate) {
}
