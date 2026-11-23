package com.sixbean.testpike.core.tool.api.dto;

/**
 * One equality condition of a {@link QuerySpec} (registered dimension column,
 * ANDed with the rest). Value binding is always a named parameter — the field
 * name is validated against the target registry, the value never concatenates
 * into SQL.
 */
public record QueryFilter(String field, Object value) {
}
