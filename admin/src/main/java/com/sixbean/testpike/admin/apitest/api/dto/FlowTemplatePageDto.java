package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/** Admin mirror of core FlowTemplatePageView (paged template window). */
public record FlowTemplatePageDto(List<FlowTemplateView> list, long total, int page, int pageSize) {
}
