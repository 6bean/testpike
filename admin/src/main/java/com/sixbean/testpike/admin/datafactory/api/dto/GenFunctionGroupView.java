package com.sixbean.testpike.admin.datafactory.api.dto;

import java.util.List;


public record GenFunctionGroupView(String group, List<GenFunctionView> functions) {
}
