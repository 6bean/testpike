package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfTemplateInfo;
import com.sixbean.testpike.core.perf.domain.service.impl.PerfScenarioServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Scenario-template catalogue (CF-4, data-driven K8 surface).
 * <p>Templates live as tp_sys_config rows under the {@code perf.template.}
 * prefix in the shared system-config group, so a new industry playbook is one
 * config row —
 * no code change, hot-updated on the next read (threshold-and-catalogue
 * configurability iron law). The catalogue reads through
 * {@link ConfigService#list(String)} (no second query path) and filters by
 * prefix.
 * <p>Every served row is pre-judged against the very single sources the
 * scenario save gate uses ({@link PerfScenarioServiceImpl} pressure-model and
 * required-key sets, {@link PerfStepActionGate} step domain): a template the
 * wizard pre-fills must never become a scenario the user then cannot save.
 * A malformed row is dropped honestly (logged by its config key only) instead
 * of hiding the whole catalogue behind one bad seed.
 * <p>Step payloads carry {@code {{ENV:}}}/{@code {{VAR:}}} placeholders as
 * template text — the catalogue resolves nothing (single resolver stays the
 * dispatch-time chain).
 */
@Component
public class PerfScenarioTemplateCatalog {

    /** Config-key prefix every shipped template lives under. */
    public static final String TEMPLATE_KEY_PREFIX = "perf.template.";

    /** Config group hosting the template rows (live-verified group name). */
    public static final String TEMPLATE_GROUP = "系统";

    private static final Logger log = LoggerFactory.getLogger(PerfScenarioTemplateCatalog.class);

    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final PerfStepActionGate stepActionGate;

    public PerfScenarioTemplateCatalog(ConfigService configService,
                                       ObjectMapper objectMapper,
                                       PerfStepActionGate stepActionGate) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.stepActionGate = stepActionGate;
    }

    /**
     * All structurally valid templates, ordered by config id (insertion order =
     * stable listing). Rows outside the prefix, non-JSON values or shapes
     * failing {@link #structuralVerdict(JsonNode)} are dropped with a logged
     * key — the picker never offers a half-broken template.
     */
    public List<PerfTemplateInfo> templates() {
        List<PerfTemplateInfo> out = new ArrayList<>();
        for (ConfigInfo row : configService.list(TEMPLATE_GROUP)) {
            String key = row.configKey();
            if (key == null || !key.startsWith(TEMPLATE_KEY_PREFIX)
                    || key.length() == TEMPLATE_KEY_PREFIX.length()) {
                continue;
            }
            try {
                JsonNode value = objectMapper.readTree(row.configValue());
                String violation = structuralVerdict(value);
                if (violation != null) {
                    log.warn("出厂压测场景模板配置行不可用已跳过 key={} 原因={}", key, violation);
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> scenario =
                        objectMapper.convertValue(value.get("scenario"), Map.class);
                out.add(new PerfTemplateInfo(key.substring(TEMPLATE_KEY_PREFIX.length()),
                        value.path("label").asText("").trim(), scenario));
            } catch (Exception e) {
                // Honest drop: template text carries placeholders and example
                // URLs only — never credentials — but log no payload anyway.
                log.warn("出厂压测场景模板配置行解析失败已跳过 key={} 原因={}", key, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Structural verdict for one template value: null = usable, otherwise a
     * reason naming the failed rule. Deliberately a subset of the full save
     * gate (ownership/schedule/data-pool checks need project context the
     * catalogue does not have) — enough that every rejection reason here would
     * also be a save-time rejection, so the picker stays honest.
     */
    public String structuralVerdict(JsonNode value) {
        if (value == null || !value.isObject()) {
            return "template value is not a JSON object";
        }
        if (value.path("label").asText("").trim().isEmpty()) {
            return "label missing";
        }
        JsonNode scenario = value.get("scenario");
        if (scenario == null || !scenario.isObject()) {
            return "scenario object missing";
        }
        String model = scenario.path("pressureModel").asText("");
        if (!PerfScenarioServiceImpl.PRESSURE_MODELS.contains(model)) {
            return "unknown pressure model: " + model;
        }
        JsonNode config = scenario.get("pressureConfig");
        if (config == null || !config.isObject()) {
            return "pressureConfig object missing";
        }
        for (String required : PerfScenarioServiceImpl.requiredKeysFor(model)) {
            JsonNode v = config.get(required);
            if (v == null || !v.isNumber() || v.asDouble() <= 0) {
                return "pressureConfig key missing or not positive: " + required;
            }
        }
        JsonNode steps = scenario.get("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            return "steps array missing or empty";
        }
        String mixViolation = PerfStepActionGate.mixStructureViolation(steps);
        if (mixViolation != null) {
            return "mix declaration invalid: " + mixViolation;
        }
        for (JsonNode step : steps) {
            String stepViolation = stepActionGate.stepViolation(step);
            if (stepViolation != null) {
                return "step invalid: " + stepViolation;
            }
        }
        JsonNode sla = scenario.get("slaJson");
        if (sla != null && !sla.isNull()) {
            if (!sla.isObject()) {
                return "slaJson must be an object";
            }
            Iterator<String> keys = sla.fieldNames();
            while (keys.hasNext()) {
                String slaKey = keys.next();
                if (!PerfScenarioServiceImpl.SLA_KEYS.contains(slaKey)) {
                    return "unknown slaJson key: " + slaKey;
                }
            }
        }
        return null;
    }
}
