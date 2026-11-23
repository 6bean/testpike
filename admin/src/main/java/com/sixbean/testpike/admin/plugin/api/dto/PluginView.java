package com.sixbean.testpike.admin.plugin.api.dto;

import java.util.Map;

/**
 *
 * @param id tp_plugin.id
 */
public record PluginView(Long id, String name, String industry, String version,
                         boolean enabled, int source, Map<String, Object> manifest,
                         int revision) {
}
