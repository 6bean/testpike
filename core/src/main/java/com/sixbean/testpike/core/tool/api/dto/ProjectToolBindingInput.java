package com.sixbean.testpike.core.tool.api.dto;

import java.util.List;


public record ProjectToolBindingInput(
        List<ProjectToolBindingItem> tools,
        Long expectedVersion) {
}
