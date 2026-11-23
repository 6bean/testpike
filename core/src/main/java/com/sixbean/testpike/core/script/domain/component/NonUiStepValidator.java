package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.NonUiActionContract;
import com.sixbean.testpike.common.util.SqlStatementContract;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import com.sixbean.testpike.core.project.domain.component.ProjectRoleResolver;
import org.springframework.stereotype.Component;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class NonUiStepValidator {

    static final String ALLOW_HOSTS_KEY = "exec.http.allow-hosts";

    private final ProdBlacklistGuard prodBlacklistGuard;
    private final ProjectRoleResolver projectRoleResolver;
    private final SystemConfigReader systemConfigReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NonUiStepValidator(ProdBlacklistGuard prodBlacklistGuard,
                              ProjectRoleResolver projectRoleResolver,
                              SystemConfigReader systemConfigReader) {
        this.prodBlacklistGuard = prodBlacklistGuard;
        this.projectRoleResolver = projectRoleResolver;
        this.systemConfigReader = systemConfigReader;
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
            String action = step.path("action").asText("");
            if (NonUiActionContract.isNonUiAction(action)) {
                validateStep(action, step, projectId, operatorId);
            }
            walkSteps(step.path("steps"), projectId, operatorId);
            walkSteps(step.path("elseSteps"), projectId, operatorId);
        }
    }

    private void validateStep(String action, JsonNode step, long projectId, Long operatorId) {
        switch (action) {
            case "sql.assert", "sql.query" -> validateSqlStep(action, step);
            case "sql.exec" -> {
                requireProjectOwner(projectId, operatorId);
                validateSqlStep(action, step);
            }
            case "http.request" -> validateHttpStep(step);
            case "net.assert" -> throwIfViolation(NonUiActionContract.netAssertViolation(step));
            case "console.assert" -> throwIfViolation(NonUiActionContract.consoleAssertViolation(step));
            default -> {  }
        }
    }

    
    private void validateSqlStep(String action, JsonNode step) {
        JsonNode conn = step.get("conn");
        if (conn == null || conn.isNull() || conn.asText().isBlank()) {
            throw new ServiceException(action + " 必须给定 conn（连接名，经项目×环境挂接解析，"
                    + "占位符写法例 {{ENV:DB_SIT}}）", BizErrorCode.VALIDATION_ERROR);
        }
        throwIfViolation(SqlStatementContract.violation(action,
                step.path("sql").asText(null), step.get("params")));
    }

    
    private void validateHttpStep(JsonNode step) {
        JsonNode url = step.get("url");
        if (url == null || url.isNull() || url.asText().isBlank()) {
            throw new ServiceException("http.request 必须给定 url", BizErrorCode.VALIDATION_ERROR);
        }
        String value = url.asText();
        if (!value.contains("{{") && prodBlacklistGuard.matches(value)) {
            throw new ServiceException("http.request 的 url 命中生产域名黑名单（" + value
                    + "）——测试脚本禁打生产，请改用测试环境地址（黑名单="
                    + "TESTPIKE_PROD_BLACKLIST 配置）", BizErrorCode.FORBIDDEN);
        }
        String allowHosts = systemConfigReader.getString(ALLOW_HOSTS_KEY, "");
        if (!allowHosts.isBlank() && !value.contains("{{")) {
            String host = ProdBlacklistGuard.hostOf(value);
            boolean allowed = host != null && java.util.Arrays.stream(allowHosts.split(","))
                    .anyMatch(pattern -> ProdBlacklistGuard.hostMatches(host, pattern));
            if (!allowed) {
                throw new ServiceException("http.request 的 url 主机「"
                        + (host == null ? value : host) + "」不在 exec.http.allow-hosts 白名单内（"
                        + allowHosts + "）——请联系管理员补充允许域名", BizErrorCode.VALIDATION_ERROR);
            }
        }
        throwIfViolation(NonUiActionContract.httpRequestViolation(step));
    }

    private void throwIfViolation(String violation) {
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireProjectOwner(long projectId, Long operatorId) {
        if (operatorId == null) {
            throw new ServiceException("受控 DML（sql.exec）仅项目负责人可保存：缺少操作人上下文，请从页面重新提交",
                    BizErrorCode.FORBIDDEN);
        }
        if (!projectRoleResolver.isOwner(projectId, operatorId)) {
            throw new ServiceException("受控 DML（sql.exec）仅项目负责人可保存"
                    + "（AI 禁自动产、只许人工显式写）", BizErrorCode.FORBIDDEN);
        }
    }
}
