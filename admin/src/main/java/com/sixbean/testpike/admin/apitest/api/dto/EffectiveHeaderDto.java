package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * One merged header row of the effective-defaults preview (admin face; mirrors
 * the core ApiEffectiveHeader wire shape — field-name drift = silent loss).
 *
 * @param source which face the row came from: request / collection
 */
public record EffectiveHeaderDto(String name, String value, String source) {
}
