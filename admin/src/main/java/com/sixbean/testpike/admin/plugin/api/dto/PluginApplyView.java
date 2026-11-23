package com.sixbean.testpike.admin.plugin.api.dto;

import java.util.List;
import java.util.Map;


public record PluginApplyView(
        Long projectId,
        Long pluginId,
        String pluginName,
        Map<String, Object> paramSnapshot,
        List<String> mountedSkills,
        List<String> notes) {
}
