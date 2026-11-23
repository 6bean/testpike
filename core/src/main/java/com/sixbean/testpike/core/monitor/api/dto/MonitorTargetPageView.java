package com.sixbean.testpike.core.monitor.api.dto;

import java.util.List;

/**
 * One page of monitor targets (S-group worst-scale contract): the whole filtered
 * {@code total} travels with the window so the wall's pagination never counts
 * only the rows on the page it happens to show. Same shape as every other core
 * page view.
 */
public record MonitorTargetPageView(List<MonitorTargetInfo> list, long total, int page, int pageSize) {
}
