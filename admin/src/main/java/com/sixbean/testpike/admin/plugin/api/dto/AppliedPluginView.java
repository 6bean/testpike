package com.sixbean.testpike.admin.plugin.api.dto;

import java.util.Map;


public record AppliedPluginView(
        Long pluginId,
        String pluginName,
        boolean enabled,
        Map<String, Object> paramSnapshot,
        String appliedAt) {
}
