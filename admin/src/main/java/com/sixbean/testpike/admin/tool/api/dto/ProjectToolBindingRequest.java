package com.sixbean.testpike.admin.tool.api.dto;

import java.util.List;


public record ProjectToolBindingRequest(
        List<ProjectToolBindingItem> tools,
        Long expectedVersion) {
}
