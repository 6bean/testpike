package com.sixbean.testpike.core.datafactory.api.dto;

import java.util.List;


public record GenFunctionInfo(String name, String description, List<String> params, String example) {
}
