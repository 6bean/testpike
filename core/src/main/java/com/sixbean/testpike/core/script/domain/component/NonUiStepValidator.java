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

    /** Business label for a database step shown in validation hints: the raw
     *  action key stays searchable, the label says what the step does. */
    private static String sqlStepLabel(String action) {
        return switch (action) {
            case "sql.assert" -> "数据库校验步骤";
            case "sql.query" -> "数据库查询步骤";
            case "sql.exec" -> "数据写入步骤";
            default -> "该步骤";
        };
    }

    private void validateSqlStep(String action, JsonNode step) {
        JsonNode conn = step.get("conn");
        if (conn == null || conn.isNull() || conn.asText().isBlank()) {
            throw new ServiceException(sqlStepLabel(action) + "还差数据库连接名：请在该步骤填写 conn"
                    + "（连接需先在「项目→环境管理」配置好，也可用占位符引用，例 {{ENV:DB_SIT}}）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        throwIfViolation(SqlStatementContract.violation(action,
                step.path("sql").asText(null), step.get("params")));
    }

    
    private void validateHttpStep(JsonNode step) {
        JsonNode url = step.get("url");
        if (url == null || url.isNull() || url.asText().isBlank()) {
            throw new ServiceException("HTTP 请求步骤还差访问地址：请在该步骤的地址栏填写完整网址"
                    + "（格式：以 http:// 或 https:// 开头，例如 https://api.example.com/orders，"
                    + "也可用 {{ENV:变量名}} 占位符引用「项目→环境管理」中已配置的环境变量）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String value = url.asText();
        if (!value.contains("{{") && prodBlacklistGuard.matches(value)) {
            throw new ServiceException("HTTP 请求步骤的地址属于生产环境（" + value
                    + "）——测试脚本不允许访问生产环境，请改用测试环境地址"
                    + "（拦截清单读自配置项「testpike.prod-blacklist」（环境变量 TESTPIKE_PROD_BLACKLIST），"
                    + "调整需联系管理员修改部署配置并重启 core 服务）", BizErrorCode.FORBIDDEN);
        }
        String allowHosts = systemConfigReader.getString(ALLOW_HOSTS_KEY, "");
        if (!allowHosts.isBlank() && !value.contains("{{")) {
            String host = ProdBlacklistGuard.hostOf(value);
            boolean allowed = host != null && java.util.Arrays.stream(allowHosts.split(","))
                    .anyMatch(pattern -> ProdBlacklistGuard.hostMatches(host, pattern));
            if (!allowed) {
                throw new ServiceException("HTTP 请求步骤的主机「"
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
            throw new ServiceException("数据写入步骤（sql.exec）仅项目负责人可保存：本次请求缺少登录身份，"
                    + "请从「用例管理」进入该用例的「用例详情」页，重新编辑并保存提交",
                    BizErrorCode.FORBIDDEN);
        }
        if (!projectRoleResolver.isOwner(projectId, operatorId)) {
            throw new ServiceException("数据写入步骤（sql.exec）仅项目负责人可保存"
                    + "（AI 不自动产出，需人工显式编写）：请在「项目管理」的「成员管理」页确认你的角色，"
                    + "若非负责人请联系负责人添加该步骤", BizErrorCode.FORBIDDEN);
        }
    }
}
