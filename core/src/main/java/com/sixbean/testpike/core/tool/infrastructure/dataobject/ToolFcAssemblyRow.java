package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;
import com.sixbean.testpike.core.tool.domain.component.ToolWriteFlag;


public class ToolFcAssemblyRow {

    private Long toolId;
    private String name;
    private Integer type;
    private Integer execKind;
    private String inputSchemaJson;
    private Integer timeoutMs;
    private String functionName;

 
    public ToolFcAssemblyItem toItem() {
        return new ToolFcAssemblyItem(
                toolId == null ? 0L : toolId,
                name,
                type == null ? 0 : type,
                execKind,
                inputSchemaJson,
                timeoutMs == null ? 0 : timeoutMs,
                ToolWriteFlag.resolve(inputSchemaJson),
                functionName);
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
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

    public Integer getExecKind() {
        return execKind;
    }

    public void setExecKind(Integer execKind) {
        this.execKind = execKind;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public void setInputSchemaJson(String inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }
}
