package com.sixbean.testpike.core.cost.infrastructure.dataobject;

import java.time.LocalDateTime;


public class CostBudgetRow {

    private Long id;
    private Long projectId;
    private Long budgetCents;
    private Integer alertPct;
    private LocalDateTime breakOverrideUntil;
    private String breakOverrideReason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public Long getBudgetCents() {
        return budgetCents;
    }

    public void setBudgetCents(Long budgetCents) {
        this.budgetCents = budgetCents;
    }

    public Integer getAlertPct() {
        return alertPct;
    }

    public void setAlertPct(Integer alertPct) {
        this.alertPct = alertPct;
    }

    public LocalDateTime getBreakOverrideUntil() {
        return breakOverrideUntil;
    }

    public void setBreakOverrideUntil(LocalDateTime breakOverrideUntil) {
        this.breakOverrideUntil = breakOverrideUntil;
    }

    public String getBreakOverrideReason() {
        return breakOverrideReason;
    }

    public void setBreakOverrideReason(String breakOverrideReason) {
        this.breakOverrideReason = breakOverrideReason;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
