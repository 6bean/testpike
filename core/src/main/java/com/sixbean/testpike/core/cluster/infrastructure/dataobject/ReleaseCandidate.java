package com.sixbean.testpike.core.cluster.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_release_candidate (awareness-surface ledger, one row per
 * detected version). The jsonb payload column is read back via a ::text alias;
 * validation/codec work lives in the service layer (same pattern as the
 * ReleaseManifest DO). DO never leaks past the api layer (layering law).
 */
public class ReleaseCandidate {

    private Long id;
    private String version;
    private String channel;
    private String detectedSource;
    private LocalDateTime detectedAt;
    private String payload;
    private String status;
    private Long confirmedManifestId;

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

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getDetectedSource() {
        return detectedSource;
    }

    public void setDetectedSource(String detectedSource) {
        this.detectedSource = detectedSource;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(LocalDateTime detectedAt) {
        this.detectedAt = detectedAt;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getConfirmedManifestId() {
        return confirmedManifestId;
    }

    public void setConfirmedManifestId(Long confirmedManifestId) {
        this.confirmedManifestId = confirmedManifestId;
    }
}
