package com.sixbean.testpike.core.defects.domain.component;

import java.util.List;


public record DefectFilteredWhere(String sql, List<Object> args) {
}
