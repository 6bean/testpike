package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_mock_scenario (stateful machine declaration only — the
 * runtime session→state map lives in Redis with K8 TTL keys and never lands
 * here, per the D-c ruling). The jsonb column is read back via a ::text alias
 * so codec work stays in the service layer.
 */
public class MockScenarioDO {

    private Long id;
    private Long spaceId;
    private String name;
    private String definitionJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public void setDefinitionJson(String definitionJson) {
        this.definitionJson = definitionJson;
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
