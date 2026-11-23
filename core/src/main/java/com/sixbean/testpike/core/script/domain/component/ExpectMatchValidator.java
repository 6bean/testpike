package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ExpectMatchContract;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.stereotype.Component;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class ExpectMatchValidator {

    private final ProjectRoleResolver projectRoleResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExpectMatchValidator(ProjectRoleResolver projectRoleResolver) {
        this.projectRoleResolver = projectRoleResolver;
    }

    
    public void check(String actionJson, long projectId, Long operatorId) {
        JsonNode root;
        try {
            root = objectMapper.readTree(actionJson);
        } catch (Exception e) {
            throw new ServiceException("动作 JSON 格式非法，无法保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (root == null || !root.isObject()) {
            return;
        }
        walkSteps(root.path("steps"), projectId, operatorId);
        walkSteps(root.path("setup"), projectId, operatorId);
    }

    
    private void walkSteps(JsonNode stepArray, long projectId, Long operatorId) {
        if (!stepArray.isArray()) {
            return;
        }
        for (JsonNode step : stepArray) {
            if (!step.isObject()) {
                continue;
            }
            if ("expect".equals(step.path("action").asText())) {
                validateExpectStep(step, projectId, operatorId);
            }
            walkSteps(step.path("steps"), projectId, operatorId);
            walkSteps(step.path("elseSteps"), projectId, operatorId);
        }
    }

    
    private void validateExpectStep(JsonNode step, long projectId, Long operatorId) {
        JsonNode matchType = step.get("matchType");
        if (matchType == null || matchType.isNull() || matchType.asText().isBlank()) {
            return;
        }
        String violation = ExpectMatchContract.matchTypeViolation(matchType.asText());
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
        switch (matchType.asText()) {
            case "range" -> throwIfViolation(ExpectMatchContract.rangeViolation(step));
            case "regex" -> {
                requireProjectOwner(projectId, operatorId);
                throwIfViolation(ExpectMatchContract.regexPatternViolation(step));
            }
            default -> {  }
        }
    }

    private void throwIfViolation(String violation) {
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireProjectOwner(long projectId, Long operatorId) {
        if (operatorId == null) {
            throw new ServiceException("正则断言仅项目负责人可保存：缺少操作人上下文，请从页面重新提交",
                    BizErrorCode.FORBIDDEN);
        }
        if (!projectRoleResolver.isOwner(projectId, operatorId)) {
            throw new ServiceException("正则断言（matchType=regex）仅项目负责人可保存"
                    + "（防重正则拖垮执行器），区间断言不受限", BizErrorCode.FORBIDDEN);
        }
    }
}
