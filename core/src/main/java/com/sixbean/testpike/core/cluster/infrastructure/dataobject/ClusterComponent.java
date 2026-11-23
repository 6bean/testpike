package com.sixbean.testpike.core.cluster.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Component version ledger data object (tp_cluster_component, migration artifact; one row per node and component family).
 * <p>DO does not leave the API layer (service layering rule); the support_state value domain is GREEN/YELLOW/RED/UNKNOWN.
 * The DB CHECK constraint uses the same set.
 */
public class ClusterComponent {

    private Long id;
    private String nodeId;
    private String componentFamily;
    private String currentVersion;
    private String targetVersion;
    private String requirement;
    private String supportState;
    private String stateReason;
    private String source;
    private LocalDateTime reportedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getComponentFamily() {
        return componentFamily;
    }

    public void setComponentFamily(String componentFamily) {
        this.componentFamily = componentFamily;
    }

    public String getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public void setTargetVersion(String targetVersion) {
        this.targetVersion = targetVersion;
    }

    public String getRequirement() {
        return requirement;
    }

    public void setRequirement(String requirement) {
        this.requirement = requirement;
    }

    public String getSupportState() {
        return supportState;
    }

    public void setSupportState(String supportState) {
        this.supportState = supportState;
    }

    public String getStateReason() {
        return stateReason;
    }

    public void setStateReason(String stateReason) {
        this.stateReason = stateReason;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(LocalDateTime reportedAt) {
        this.reportedAt = reportedAt;
    }
}
