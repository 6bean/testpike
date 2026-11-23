package com.sixbean.testpike.core.tool.api.dto;

import java.util.List;

/**
 *
 * @param version revision
 */
public record ProjectToolView(
        List<ProjectToolItem> list,
        long version) {
}
