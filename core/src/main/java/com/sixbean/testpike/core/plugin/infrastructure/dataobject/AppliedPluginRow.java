package com.sixbean.testpike.core.plugin.infrastructure.dataobject;


public class AppliedPluginRow {

    private Long pluginId;
    private String pluginName;
    private boolean enabled;
    private String paramSnapshot;
    private String appliedAt;

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long pluginId) {
        this.pluginId = pluginId;
    }

    public String getPluginName() {
        return pluginName;
    }

    public void setPluginName(String pluginName) {
        this.pluginName = pluginName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getParamSnapshot() {
        return paramSnapshot;
    }

    public void setParamSnapshot(String paramSnapshot) {
        this.paramSnapshot = paramSnapshot;
    }

    public String getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(String appliedAt) {
        this.appliedAt = appliedAt;
    }
}
