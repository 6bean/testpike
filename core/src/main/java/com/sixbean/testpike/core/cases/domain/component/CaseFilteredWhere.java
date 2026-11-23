package com.sixbean.testpike.core.cases.domain.component;

import java.util.List;

/**
 * Test case filtering WHERE build result ({@link CaseFilters} output):
 * WHERE fragment (including leading space, can be directly appended to "SELECT ... FROM tp_test_case") plus placeholder parameters.
 *
 * @param sql WHERE clause (" WHERE deleted = 0 AND ...")
 * @param args placeholder parameters
 */
public record CaseFilteredWhere(String sql, List<Object> args) {
}
