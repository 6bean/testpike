package com.sixbean.testpike.admin.perf.api.dto;

import java.util.Map;

/**
 * A shipped press-test scenario template as served to the wizard picker
 * (admin mirror of the core catalogue row).
 *
 * @param templateKey stable identifier below the config-key prefix
 * @param label       human-readable name for the picker
 * @param scenario    pre-fill payload shaped like the scenario save input
 */
public record PerfTemplateDto(String templateKey, String label,
                              Map<String, Object> scenario) {
}
