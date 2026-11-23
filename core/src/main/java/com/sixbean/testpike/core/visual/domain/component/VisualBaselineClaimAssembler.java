package com.sixbean.testpike.core.visual.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.service.VisualBaselineService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class VisualBaselineClaimAssembler {

    private final VisualBaselineService visualBaselineService;
    private final ObjectMapper objectMapper;

    public VisualBaselineClaimAssembler(VisualBaselineService visualBaselineService,
                                        ObjectMapper objectMapper) {
        this.visualBaselineService = visualBaselineService;
        this.objectMapper = objectMapper;
    }

    
    public Map<String, Object> assemble(long projectId, long caseId, String actionJson) {
        Map<String, Object> issued = new LinkedHashMap<>();
        if (actionJson == null || actionJson.isBlank()) {
            return issued;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(actionJson);
        } catch (Exception parseFailure) {
            return issued;
        }
        long seq = 0;
        seq = collectArray(root.path("setup"), projectId, caseId, seq, issued);
        collectArray(root.path("steps"), projectId, caseId, seq, issued);
        return issued;
    }

    
    public Map<String, Double> toleranceByStepKey(long caseId, String actionJson) {
        Map<String, Double> tolerances = new LinkedHashMap<>();
        if (actionJson == null || actionJson.isBlank()) {
            return tolerances;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(actionJson);
        } catch (Exception parseFailure) {
            return tolerances;
        }
        long seq = 0;
        seq = collectTolerances(root.path("setup"), caseId, seq, tolerances);
        collectTolerances(root.path("steps"), caseId, seq, tolerances);
        return tolerances;
    }

    private long collectTolerances(JsonNode array, long caseId, long seqStart,
                                   Map<String, Double> tolerances) {
        if (array == null || !array.isArray()) {
            return seqStart;
        }
        long seq = seqStart;
        for (JsonNode step : array) {
            seq++;
            if (step == null || !step.isObject()
                    || !"visual.assert".equals(step.path("action").asText())) {
                continue;
            }
            String stepKey = stepKeyOf(caseId, seq, step);
            JsonNode tolerance = step.path("tolerance");
            tolerances.put(stepKey, tolerance.isNumber() ? tolerance.asDouble() : null);
        }
        return seq;
    }

    
    private long collectArray(JsonNode array, long projectId, long caseId,
                              long seqStart, Map<String, Object> issued) {
        if (array == null || !array.isArray()) {
            return seqStart;
        }
        long seq = seqStart;
        for (JsonNode step : array) {
            seq++;
            if (step == null || !step.isObject()
                    || !"visual.assert".equals(step.path("action").asText())) {
                continue;
            }
            String stepKey = stepKeyOf(caseId, seq, step);
            VisualBaselineInfo confirmed =
                    visualBaselineService.findConfirmed(projectId, stepKey);
            if (confirmed == null) {
                continue;
            }
            String explicitKey = step.path("baselineKey").asText("").trim();
            if (!explicitKey.isEmpty() && !explicitKey.equals(confirmed.baselineKey())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("baselineKey", confirmed.baselineKey());
            entry.put("baselineUrl",
                    visualBaselineService.grantBaselineViewUrl(confirmed.baselineKey()));
            if (confirmed.diffTolerance() != null) {
                entry.put("tolerance", confirmed.diffTolerance());
            }
            issued.put(stepKey, entry);
        }
        return seq;
    }

    
    private static String stepKeyOf(long caseId, long seq, JsonNode step) {
        String raw = step.path("name").asText("");
        String name = raw.isBlank() ? "shot" : raw.trim();
        return "case:" + caseId + ":step:" + seq + ":" + name;
    }
}
