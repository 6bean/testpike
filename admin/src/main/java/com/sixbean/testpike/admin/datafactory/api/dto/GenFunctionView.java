package com.sixbean.testpike.admin.datafactory.api.dto;

import java.util.List;


public record GenFunctionView(String name, String description, List<String> params, String example) {
}
