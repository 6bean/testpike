package com.sixbean.testpike.core.apitest.infrastructure.dataobject;

/**
 * Flow-template row (tp_apitest_flow_template; BeanPropertyRowMapper carrier,
 * DO never leaves the infrastructure layer). jsonb column read via ::text.
 */
public class FlowTemplateRow {

    private long id;
    private long projectId;
    private String name;
    private String description;
    private Long sourceCollectionId;
    private String stepsSnapshotJson;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getProjectId() {
        return projectId;
    }

    public void setProjectId(long projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getSourceCollectionId() {
        return sourceCollectionId;
    }

    public void setSourceCollectionId(Long sourceCollectionId) {
        this.sourceCollectionId = sourceCollectionId;
    }

    public String getStepsSnapshotJson() {
        return stepsSnapshotJson;
    }

    public void setStepsSnapshotJson(String stepsSnapshotJson) {
        this.stepsSnapshotJson = stepsSnapshotJson;
    }
}
