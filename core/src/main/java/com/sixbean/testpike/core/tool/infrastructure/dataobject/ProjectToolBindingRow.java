package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolItem;
import com.sixbean.testpike.core.tool.domain.component.ToolType;

import java.sql.Timestamp;


public class ProjectToolBindingRow {

    private Long toolId;
    private Integer enabled;
    private Timestamp boundAt;
    private String name;
    private Integer type;

 
    public ProjectToolItem toItem() {
        int typeCode = type == null ? 0 : type;
        return new ProjectToolItem(toolId, enabled, ViewFormats.formatTs(boundAt),
                name, type, ToolType.labelOf(typeCode));
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Timestamp getBoundAt() {
        return boundAt;
    }

    public void setBoundAt(Timestamp boundAt) {
        this.boundAt = boundAt;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }
}
