package com.sixbean.testpike.core.visual.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineCandidateInput;
import com.sixbean.testpike.core.visual.domain.service.VisualBaselineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;


@Component
public class VisualBaselineCaptureSupport {

    private static final Logger log = LoggerFactory.getLogger(VisualBaselineCaptureSupport.class);

    
    static final String STATUS_BASELINE_PENDING = "baseline_pending";

    private final VisualBaselineService visualBaselineService;
    private final VisualBaselineClaimAssembler claimAssembler;
    private final ObjectMapper objectMapper;

    public VisualBaselineCaptureSupport(VisualBaselineService visualBaselineService,
                                        VisualBaselineClaimAssembler claimAssembler,
                                        ObjectMapper objectMapper) {
        this.visualBaselineService = visualBaselineService;
        this.claimAssembler = claimAssembler;
        this.objectMapper = objectMapper;
    }

    
    public void onResultSubmitted(ExecTaskInfo task, Object stepsTimeline) {
        JsonNode timeline = toNode(stepsTimeline);
        if (timeline == null || !timeline.isArray() || !containsPending(timeline)) {
            return;
        }
        Map<String, Double> tolerances =
                claimAssembler.toleranceByStepKey(task.caseId(), task.actionJson());
        for (JsonNode row : timeline) {
            captureRow(task, row, tolerances);
        }
    }

    private void captureRow(ExecTaskInfo task, JsonNode row, Map<String, Double> tolerances) {
        if (row == null || !row.isObject()
                || !STATUS_BASELINE_PENDING.equals(row.path("status").asText(""))) {
            return;
        }
        String stepKey = row.path("stepKey").asText("").trim();
        String artifactKey = row.path("artifactKey").asText("").trim();
        if (stepKey.isEmpty() || artifactKey.isEmpty()) {
            log.warn("视觉候选回传行缺 stepKey 或 artifactKey，跳过登记（taskId={}, stepNo={}）",
                    task.id(), row.path("stepNo").asInt());
            return;
        }
        try {
            visualBaselineService.recordCandidate(new VisualBaselineCandidateInput(
                    task.projectId(), stepKey, task.id(), artifactKey,
                    tolerances.get(stepKey)));
        } catch (ServiceException e) {
            log.warn("视觉候选登记被拒（taskId={}, stepKey={}）：{}", task.id(), stepKey, e.getMessage());
        }
    }

    
    private boolean containsPending(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsPending(item)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isObject()) {
            return false;
        }
        if (STATUS_BASELINE_PENDING.equals(node.path("status").asText(""))) {
            return true;
        }
        return containsPending(node.path("children"));
    }

    private JsonNode toNode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof String s
                    ? (s.isBlank() ? null : objectMapper.readTree(s))
                    : objectMapper.valueToTree(value);
        } catch (Exception e) {
            return null;
        }
    }
}
