package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.AiScriptLimitsReader;
import org.springframework.stereotype.Component;


@Component
public class ScriptLimitGuard {

    private final AiScriptLimitsReader limitsReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScriptLimitGuard(AiScriptLimitsReader limitsReader) {
        this.limitsReader = limitsReader;
    }

    
    public void check(String actionJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(actionJson);
        } catch (Exception e) {
            throw new ServiceException("动作 JSON 格式非法，无法保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (root == null || !root.isObject()) {
            throw new ServiceException("动作 JSON 格式非法，无法保存", BizErrorCode.VALIDATION_ERROR);
        }
        JsonNode steps = root.path("steps");
        if (!steps.isArray()) {
            return;
        }
        ScriptWalkCounts counts = new ScriptWalkCounts();
        walk(steps, 1, counts);
        int maxSteps = limitsReader.maxSteps();
        if (counts.nodes > maxSteps) {
            throw new ServiceException("脚本步骤超限：" + counts.nodes + " 步，上限 " + maxSteps
                    + "（ai.script.max-steps，请拆分用例或调整系统参数）", BizErrorCode.SCRIPT_LIMIT_EXCEEDED);
        }
        int maxLoop = limitsReader.maxLoop();
        if (counts.loops > maxLoop) {
            throw new ServiceException("脚本循环超限：" + counts.loops + " 个 loop，上限 " + maxLoop
                    + "（ai.script.max-loop）", BizErrorCode.SCRIPT_LIMIT_EXCEEDED);
        }
        int maxDepth = limitsReader.maxNestDepth();
        if (counts.maxDepth > maxDepth) {
            throw new ServiceException("脚本嵌套超限：深度 " + counts.maxDepth + "，上限 " + maxDepth
                    + "（ai.script.max-nest-depth，可在「系统设置 > 参数配置」调整）", BizErrorCode.SCRIPT_LIMIT_EXCEEDED);
        }
    }

 
    private void walk(JsonNode stepArray, int depth, ScriptWalkCounts counts) {
        if (depth > 1000) {
            throw new ServiceException("脚本嵌套超限：结构异常加深", BizErrorCode.SCRIPT_LIMIT_EXCEEDED);
        }
        for (JsonNode step : stepArray) {
            if (!step.isObject()) {
                continue;
            }
            counts.nodes++;
            counts.maxDepth = Math.max(counts.maxDepth, depth);
            if ("loop".equals(step.path("action").asText())) {
                counts.loops++;
            }
            walkChildren(step.path("steps"), depth, counts);
            walkChildren(step.path("elseSteps"), depth, counts);
        }
    }

    private void walkChildren(JsonNode child, int depth, ScriptWalkCounts counts) {
        if (child.isArray()) {
            walk(child, depth + 1, counts);
        }
    }
}
