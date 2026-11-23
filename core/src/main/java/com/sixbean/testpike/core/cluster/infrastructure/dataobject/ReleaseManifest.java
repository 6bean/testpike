package com.sixbean.testpike.core.cluster.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Release manifest data object (tp_release_manifest, migration artifact; one version-unique row per release).
 * <p>DO does not leave the api layer (iron rule of service layering); jsonb columns are read out with a ::text alias.
 * Serialization/deserialization is completed at a single point on the consumer side (following the AppautoEnvProfile convention).
 */
public class ReleaseManifest {

    private Long id;
    private String version;
    private String componentMatrix;
    private String breakingChanges;
    private String executorVersionMin;
    private String executorVersionMax;
    private String suggestedPath;
    private String source;
    private LocalDateTime registeredAt;
    private String artifacts;
    private String releaseNotesMd;
    private LocalDateTime publishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getComponentMatrix() {
        return componentMatrix;
    }

    public void setComponentMatrix(String componentMatrix) {
        this.componentMatrix = componentMatrix;
    }

    public String getBreakingChanges() {
        return breakingChanges;
    }

    public void setBreakingChanges(String breakingChanges) {
        this.breakingChanges = breakingChanges;
    }

    public String getExecutorVersionMin() {
        return executorVersionMin;
    }

    public void setExecutorVersionMin(String executorVersionMin) {
        this.executorVersionMin = executorVersionMin;
    }

    public String getExecutorVersionMax() {
        return executorVersionMax;
    }

    public void setExecutorVersionMax(String executorVersionMax) {
        this.executorVersionMax = executorVersionMax;
    }

    public String getSuggestedPath() {
        return suggestedPath;
    }

    public void setSuggestedPath(String suggestedPath) {
        this.suggestedPath = suggestedPath;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }

    public String getArtifacts() {
        return artifacts;
    }

    public void setArtifacts(String artifacts) {
        this.artifacts = artifacts;
    }

    public String getReleaseNotesMd() {
        return releaseNotesMd;
    }

    public void setReleaseNotesMd(String releaseNotesMd) {
        this.releaseNotesMd = releaseNotesMd;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }
}
