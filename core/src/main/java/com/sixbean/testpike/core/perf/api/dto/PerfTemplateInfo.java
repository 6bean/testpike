package com.sixbean.testpike.core.perf.api.dto;

import java.util.Map;

/**
 * A shipped press-test scenario template served by the catalogue endpoint
 * (CF-4). Templates are K8 config rows under the perf.template. prefix, so the
 * picker grows by configuration alone — a new industry playbook is one config
 * row, hot-updated on the next read.
 *
 * @param templateKey key suffix below the prefix (e.g. ecom-checkout) — the
 *                    stable identifier the wizard pre-fill targets
 * @param label       human-readable template name for the wizard picker
 * @param scenario    pre-fill payload shaped like the scenario save input
 *                    (pressureModel/pressureConfig/steps/slaJson); served
 *                    verbatim, the save gate owns the final judgement
 */
public record PerfTemplateInfo(String templateKey, String label,
                               Map<String, Object> scenario) {
}
