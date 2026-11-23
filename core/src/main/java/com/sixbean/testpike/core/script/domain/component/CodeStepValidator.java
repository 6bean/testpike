package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.CodeStepContract;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.stereotype.Component;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class CodeStepValidator {

    private final ProjectRoleResolver projectRoleResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodeStepValidator(ProjectRoleResolver projectRoleResolver) {
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
            if ("code.step".equals(step.path("action").asText(""))) {
                requireProjectOwner(projectId, operatorId);
                String violation = CodeStepContract.violation(step);
                if (violation != null) {
                    throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
                }
            }
            walkSteps(step.path("steps"), projectId, operatorId);
            walkSteps(step.path("elseSteps"), projectId, operatorId);
        }
    }

    private void requireProjectOwner(long projectId, Long operatorId) {
        if (operatorId == null) {
            throw new ServiceException("自定义代码步骤（code.step）仅项目负责人可保存："
                    + "缺少操作人上下文，请从页面重新提交", BizErrorCode.FORBIDDEN);
        }
        if (!projectRoleResolver.isOwner(projectId, operatorId)) {
            throw new ServiceException("自定义代码步骤（code.step）仅项目负责人可保存"
                    + "（perm:script:code-step，沙箱执行风险面、AI 禁自动产）", BizErrorCode.FORBIDDEN);
        }
    }
}
