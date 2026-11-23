package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * Paged flow-template window (display-scale iron law: every management-plane
 * list door carries page/pageSize; clamp dialect = shared PageWindow).
 *
 * @param list     current page of templates, newest first
 * @param total    template count across all pages of this project
 * @param page     1-based page index actually served (after clamping)
 * @param pageSize window size actually served (after clamping)
 */
public record FlowTemplatePageView(
        List<FlowTemplateInfo> list,
        long total,
        int page,
        int pageSize) {
}
