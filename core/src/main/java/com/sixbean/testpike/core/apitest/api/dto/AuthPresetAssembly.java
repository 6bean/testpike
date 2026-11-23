package com.sixbean.testpike.core.apitest.api.dto;

/**
 * One assembly row of a signature auth preset (api-test W1c preset catalogue).
 * <p>The row is a template: {@code value} carries the ${gen.*} function text and
 * optional {{CRED:key}} vault references, resolved downstream (executor single
 * parser + dispatch-time credential injection) — never evaluated here.
 *
 * @param into  placement slot, header or query
 * @param name  header/query key name
 * @param value template text (non-blank, validated at request save time)
 */
public record AuthPresetAssembly(String into, String name, String value) {
}
