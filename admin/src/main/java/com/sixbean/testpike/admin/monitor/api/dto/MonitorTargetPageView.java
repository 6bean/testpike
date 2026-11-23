package com.sixbean.testpike.admin.monitor.api.dto;

import java.util.List;

/**
 * admin mirror of core MonitorTargetPageView (bridge DTO; DO never leaves core).
 * The whole filtered {@code total} travels with the window so the wall's pagination
 * never counts only the rows on the page it happens to show.
 */
public record MonitorTargetPageView(List<MonitorTargetView> list, long total, int page, int pageSize) {
}
