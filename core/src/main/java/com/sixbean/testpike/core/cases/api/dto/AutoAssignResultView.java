package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Automatic assignment result view: preview state is the dispatch plan, formal state is the actual dispatch result, and both have the same structure for comparison.
 *
 * @param assignedCount the number of items dispatched in this run (preview: planned count; formal: actual count of records that won the conditional update).
 * @param byAssignee per-assignee dispatch rows (count descending, userId ascending; an empty result is an empty list).
 */
public record AutoAssignResultView(int assignedCount, List<AutoAssignAssigneeRow> byAssignee) {
}
