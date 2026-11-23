package com.sixbean.testpike.core.explore.api.dto;


public class ExploreSessionView {

    
    public static final int STATUS_RUNNING = 1;
    
    public static final int STATUS_DONE = 2;
    
    public static final int STATUS_BUDGET_STOP = 3;
    
    public static final int STATUS_PARTIAL = 4;
 
    public static final int STATUS_CANCELLED = 5;

    private Long id;
    private Long projectId;
    private String goalText;
    private String startUrl;
    private Integer status;
    private Integer stepsUsed;
    private Integer pagesVisited;
    private Long costCents;
    private String reportMd;
    private Long createdBy;
    private String budgetSnapshot;
 
    private Long envId;
 
    private String credRef;
 /** Login-state ledger handle (tp_login_state.state_name) declared at creation. */
    private String loginStateName;

    public boolean isTerminal() {
        return status != null && status != STATUS_RUNNING;
    }

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

    public String getGoalText() {
        return goalText;
    }

    public void setGoalText(String goalText) {
        this.goalText = goalText;
    }

    public String getStartUrl() {
        return startUrl;
    }

    public void setStartUrl(String startUrl) {
        this.startUrl = startUrl;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getStepsUsed() {
        return stepsUsed;
    }

    public void setStepsUsed(Integer stepsUsed) {
        this.stepsUsed = stepsUsed;
    }

    public Integer getPagesVisited() {
        return pagesVisited;
    }

    public void setPagesVisited(Integer pagesVisited) {
        this.pagesVisited = pagesVisited;
    }

    public Long getCostCents() {
        return costCents;
    }

    public void setCostCents(Long costCents) {
        this.costCents = costCents;
    }

    public String getReportMd() {
        return reportMd;
    }

    public void setReportMd(String reportMd) {
        this.reportMd = reportMd;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public String getBudgetSnapshot() {
        return budgetSnapshot;
    }

    public void setBudgetSnapshot(String budgetSnapshot) {
        this.budgetSnapshot = budgetSnapshot;
    }

    public Long getEnvId() {
        return envId;
    }

    public void setEnvId(Long envId) {
        this.envId = envId;
    }

    public String getCredRef() {
        return credRef;
    }

    public void setCredRef(String credRef) {
        this.credRef = credRef;
    }

    public String getLoginStateName() {
        return loginStateName;
    }

    public void setLoginStateName(String loginStateName) {
        this.loginStateName = loginStateName;
    }
}
