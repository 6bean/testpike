package com.sixbean.testpike.core.cost.api.dto;


public record BudgetView(Long projectId, long budgetCents, int alertPct, boolean overridden) {
}
