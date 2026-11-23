package com.sixbean.testpike.admin.cost.api.dto;


public record BudgetView(Long projectId, long budgetCents, int alertPct, boolean overridden) {
}
