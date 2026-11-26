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
            throw new ServiceException(ScriptValidationGuidance.INVALID_ACTION_JSON, BizErrorCode.VALIDATION_ERROR);
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
                    + "缺少操作人上下文——请在「用例管理」打开该用例进入脚本编辑页重新保存",
                    BizErrorCode.FORBIDDEN);
        }
        if (!projectRoleResolver.isOwner(projectId, operatorId)) {
            throw new ServiceException("自定义代码步骤（code.step）仅项目负责人可保存"
                    + "（perm:script:code-step，沙箱执行风险面、AI 禁自动产）"
                    + "——请改由项目负责人在「用例管理」的脚本编辑页保存，"
                    + "或把该步骤换成内置动作（如 click/input）后重新保存", BizErrorCode.FORBIDDEN);
        }
    }
}
