package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 * Admin mirror of core ApiRunSetDetail: ledger plus child steps in seq_no order.
 * The wire shape nests exactly like the core record ({@code ledger} + {@code steps}).
 */
public record RunSetDetailView(RunSetView ledger, List<RunSetStepView> steps) {
}
