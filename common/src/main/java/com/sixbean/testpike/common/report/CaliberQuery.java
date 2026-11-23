package com.sixbean.testpike.common.report;

import java.util.Map;


public record CaliberQuery(String code, String sql, Map<String, Object> params) {
}
