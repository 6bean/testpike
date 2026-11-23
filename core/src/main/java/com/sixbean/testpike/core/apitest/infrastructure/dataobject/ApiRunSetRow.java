package com.sixbean.testpike.core.apitest.infrastructure.dataobject;

/**
 * Collection-run ledger row (tp_apitest_run_set; BeanPropertyRowMapper carrier,
 * DO never leaves the infrastructure layer). jsonb columns read via ::text.
 */
public class ApiRunSetRow {

    private long id;
    private long collectionId;
    private long projectId;
    private Long envId;
    private String name;
    private String interruptionMode;
    private int status;
    private String pinnedJson;
    private String initialVarsJson;
    private int totalSteps;
    private int passedCount;
    private int failedCount;
    private int skippedCount;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getCollectionId() {
        return collectionId;
    }

    public void setCollectionId(long collectionId) {
        this.collectionId = collectionId;
    }

    public long getProjectId() {
        return projectId;
    }

    public void setProjectId(long projectId) {
        this.projectId = projectId;
    }

    public Long getEnvId() {
        return envId;
    }

    public void setEnvId(Long envId) {
        this.envId = envId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInterruptionMode() {
        return interruptionMode;
    }

    public void setInterruptionMode(String interruptionMode) {
        this.interruptionMode = interruptionMode;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getPinnedJson() {
        return pinnedJson;
    }

    public void setPinnedJson(String pinnedJson) {
        this.pinnedJson = pinnedJson;
    }

    public String getInitialVarsJson() {
        return initialVarsJson;
    }

    public void setInitialVarsJson(String initialVarsJson) {
        this.initialVarsJson = initialVarsJson;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public void setTotalSteps(int totalSteps) {
        this.totalSteps = totalSteps;
    }

    public int getPassedCount() {
        return passedCount;
    }

    public void setPassedCount(int passedCount) {
        this.passedCount = passedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }
}
