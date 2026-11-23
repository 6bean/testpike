package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.VisualAssertContract;
import org.springframework.stereotype.Component;


@Component
public class VisualAssertValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    
    public void check(String actionJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(actionJson);
        } catch (Exception e) {
            throw new ServiceException("动作 JSON 格式非法，无法保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (root == null || !root.isObject()) {
            return;
        }
        walkSteps(root.path("steps"));
        walkSteps(root.path("setup"));
    }

    
    private void walkSteps(JsonNode stepArray) {
        if (!stepArray.isArray()) {
            return;
        }
        for (JsonNode step : stepArray) {
            if (!step.isObject()) {
                continue;
            }
            if ("visual.assert".equals(step.path("action").asText(""))) {
                String violation = VisualAssertContract.violation(step);
                if (violation != null) {
                    throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
                }
            }
            walkSteps(step.path("steps"));
            walkSteps(step.path("elseSteps"));
        }
    }
}
