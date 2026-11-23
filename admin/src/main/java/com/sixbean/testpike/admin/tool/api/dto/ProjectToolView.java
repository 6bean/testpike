package com.sixbean.testpike.admin.tool.api.dto;

import java.util.List;


public record ProjectToolView(
        List<ProjectToolItem> list,
        long version) {
}
