package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.ExpectMatchContract;
import com.sixbean.testpike.common.util.NonUiActionContract;
import com.sixbean.testpike.common.util.SqlStatementContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


public class ActionJsonValidator {

    private static final Logger log = LoggerFactory.getLogger(ActionJsonValidator.class);

 
    public static final String ACTION_JSON_VERSION = "v2";

 
    private static final Set<String> BASE_ACTIONS;
    static {
        Set<String> actions = new java.util.HashSet<>(Set.of(
                "goto", "click", "input", "select", "expect", "wait", "screenshot",
                "loop", "if", "code.step", "visual.assert"));
        actions.addAll(NonUiActionContract.NON_UI_ACTIONS);
        BASE_ACTIONS = Set.copyOf(actions);
    }
    private static final List<String> NAMESPACES = List.of("pos.", "erp.", "data.", "mobile.");

    private static final int MAX_STEPS = 50;
    private static final int MAX_DEPTH = 5;

    
    private static final java.util.regex.Pattern PLACEHOLDER_TOKEN =
            java.util.regex.Pattern.compile("\\{\\{([^{}]*)}}");
    
    // WE S5 (found by the acceptance spectrum): {{ELEM:name}} is the
    // page-element reference placeholder of generated scripts — contract rule 6
    // joins the existing {{CRED:key}} reference family. The steps-gen artefact
    // carries this textual form; WebElementDispatchSnapshot owns the single
    // normalisation point, the whitelist here only admits the lexical shape.
    private static final java.util.regex.Pattern PLACEHOLDER_ALLOWED =
            java.util.regex.Pattern.compile(
                    "(ENV|DATA|CRED):[A-Za-z_][A-Za-z0-9_]*|PACK:[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*"
                            + "|VAR:[A-Za-z0-9_][A-Za-z0-9_.\\-]*"
                            + "|ELEM:\\S.*");

    
    static final java.util.regex.Pattern SCOPED_REF_KEY =
            java.util.regex.Pattern.compile("[A-Za-z0-9_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5._\\-]*");
    
    static final java.util.regex.Pattern ROLE_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_\u4e00-\u9fa5][A-Za-z0-9_\u4e00-\u9fa5\\-]*");
    
    static final java.util.regex.Pattern ROLE_BINDING =
            java.util.regex.Pattern.compile("per-vu|shared|none|pool\\([1-9][0-9]{0,3}\\)");
    
    static final java.util.Set<String> RESERVED_ROLE_NAMES =
            java.util.Set.of("ENV", "DATA", "PACK", "VAR", "SESSION", "RUN", "global", "CRED");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ActionCheckResult validate(String actionJson) {
        return validate(actionJson, java.util.Set.of());
    }

    
    public ActionCheckResult validate(String actionJson, java.util.Set<String> aliasNames) {
        List<String> errors = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(JsonOutputExtractor.extractJson(actionJson));
            if (!ACTION_JSON_VERSION.equals(root.path("version").asText())) {
                errors.add("version 必须为 v2");
            }
            if (root.path("caseId").asText("").isBlank()) {
                errors.add("caseId 必填");
            }
            if (root.path("env").isMissingNode()) {
                errors.add("env 必填");
            }
            JsonNode steps = root.path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                errors.add("steps 非空");
            } else if (steps.size() > MAX_STEPS) {
                errors.add("步骤数超过上限 50");
            }
            validateNode(steps, 1, errors);
            JsonNode setup = root.path("setup");
            if (!setup.isMissingNode() && !setup.isNull()) {
                if (!setup.isArray()) {
                    errors.add("setup 必须为步骤数组（不能是对象或字符串）");
                } else {
                    validateNode(setup, 1, errors);
                }
            }
            validatePlaceholders(root, errors, aliasNames);
            validateRoles(root, errors);
            // sessionState structural gate (04 contract AV extension)
            validateSessionState(root, errors);
            validateVarChain(root, errors);
        } catch (Exception e) {
            log.warn("动作 JSON 解析失败（容错提取后仍非法）: {}", e.getMessage());
            errors.add("动作 JSON 不是合法 JSON: " + e.getMessage());
        }
        return new ActionCheckResult(errors.isEmpty(), errors);
    }

    private void validateNode(JsonNode steps, int depth, List<String> errors) {
        if (!steps.isArray() || errors.size() >= 5) {
            return;
        }
        if (depth > MAX_DEPTH) {
            errors.add("嵌套深度超过 5 层");
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            String stepLabel = "第 " + (i + 1) + " 步";
            String action = step.path("action").asText("");
            if (action.isBlank()) {
                errors.add(stepLabel + "缺少 action");
                continue;
            }
            if (!isAllowedAction(action)) {
                String irViolation = UiActionContract.appActionViolation(action);
                errors.add(stepLabel + "：" + (irViolation != null ? irViolation
                        : "未知动作: " + action + "（04 契约白名单）"));
                continue;
            }
            if (NonUiActionContract.isNonUiAction(action)) {
                validateNonUiStep(stepLabel, action, step, errors);
            }
            JsonNode target = step.path("target");
            if (action.equals("goto")) {
                String url = firstNonBlank(step.path("url").asText(""),
                        target.path("url").asText(""), target.path("href").asText(""));
                if (url.isBlank()) {
                    errors.add(stepLabel + " goto 缺少 url（target.url 或顶层 url 二选一必填）");
                }
            }
            if (target.isObject() && !action.equals("goto") && !action.equals("if")
                    && !action.equals("loop") && !action.equals("screenshot")
                    && !action.equals("code.step") && !action.equals("visual.assert")) {
                JsonNode chain = target.path("chain");
                if (chain.isMissingNode() || chain.isEmpty()) {
                    if (!target.has("text") && !target.has("url")) {
                        errors.add(stepLabel + " " + action + " target 缺少 locator.chain（04 契约规则 3）");
                    }
                }
            }
            if (action.equals("if") || action.equals("loop")) {
                validateNode(step.path("steps"), depth + 1, errors);
                validateNode(step.path("elseSteps"), depth + 1, errors);
            }
            if (action.equals("expect")) {
                String violation = ExpectMatchContract.expectViolation(step);
                if (violation != null) {
                    errors.add(stepLabel + " expect 步 matchType 格式不符：" + violation
                            + "——无法精确判定时改用 contains 并在 description 标注「需人工精化」");
                }
            }
        }
    }

    
    private void validatePlaceholders(JsonNode root, List<String> errors,
                                      java.util.Set<String> aliasNames) {
        java.util.Set<String> roleNames = declaredRoleNames(root);
        java.util.Set<String> legalAliases = legalAliasNames(aliasNames);
        root.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if ("setup".equals(key) || "steps".equals(key)) {
                walkStepPlaceholders(entry.getValue(), java.util.Set.of(), roleNames, legalAliases, errors);
            } else {
                scanPlaceholders(entry.getValue(), java.util.Set.of(), roleNames, legalAliases, errors);
            }
        });
    }

    
    private java.util.Set<String> legalAliasNames(java.util.Set<String> aliasNames) {
        java.util.Set<String> legal = new java.util.HashSet<>();
        for (String alias : aliasNames) {
            if (StepAliasContract.violation(alias) == null) {
                legal.add(alias);
            }
        }
        return legal;
    }

    
    private java.util.Set<String> declaredRoleNames(JsonNode root) {
        java.util.Set<String> names = new java.util.HashSet<>();
        JsonNode roles = root.path("roles");
        if (roles.isArray()) {
            roles.forEach(role -> {
                String name = role.path("name").asText("");
                if (ROLE_NAME.matcher(name).matches()) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    
    private void walkStepPlaceholders(JsonNode steps, java.util.Set<String> scope,
                                      java.util.Set<String> roleNames, java.util.Set<String> aliasNames,
                                      List<String> errors) {
        if (!steps.isArray()) {
            return;
        }
        for (JsonNode step : steps) {
            if (!step.isObject()) {
                scanPlaceholders(step, scope, roleNames, aliasNames, errors);
                continue;
            }
            for (java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = step.fields(); it.hasNext(); ) {
                java.util.Map.Entry<String, JsonNode> field = it.next();
                if (!field.getKey().equals("steps") && !field.getKey().equals("elseSteps")) {
                    scanPlaceholders(field.getValue(), scope, roleNames, aliasNames, errors);
                }
            }
            walkStepPlaceholders(step.path("steps"), childScopeOf(step, scope), roleNames, aliasNames, errors);
            walkStepPlaceholders(step.path("elseSteps"), childScopeOf(step, scope), roleNames, aliasNames, errors);
        }
    }

    
    private java.util.Set<String> childScopeOf(JsonNode step, java.util.Set<String> scope) {
        if (!"loop".equals(step.path("action").asText(""))) {
            return scope;
        }
        java.util.Set<String> child = new java.util.HashSet<>(scope);
        String defaultVar = step.has("items") ? "item" : "index";
        String var = step.path("var").asText("").isBlank() ? defaultVar : step.path("var").asText();
        if (ITER_VAR_NAME.matcher(var).matches()) {
            child.add(var);
        }
        child.add("index");
        return child;
    }

    
    private static final java.util.regex.Pattern ITER_VAR_NAME =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    
    private void scanPlaceholders(JsonNode node, java.util.Set<String> scope,
                                  java.util.Set<String> roleNames, java.util.Set<String> aliasNames,
                                  List<String> errors) {
        if (node.isObject() || node.isArray()) {
            node.forEach(child -> scanPlaceholders(child, scope, roleNames, aliasNames, errors));
        } else if (node.isTextual()) {
            var matcher = PLACEHOLDER_TOKEN.matcher(node.asText());
            while (matcher.find()) {
                String token = matcher.group(1);
                if (scope.contains(token)) {
                    continue;
                }
                if (isScopedRefAllowed(token, roleNames, aliasNames)) {
                    continue;
                }
                if (!PLACEHOLDER_ALLOWED.matcher(token).matches()) {
                    errors.add("非法占位符 {{" + token + "}}（04 契约规则 6：{{ENV:键}}/"
                            + "{{DATA:键}}/{{CRED:键}}（键=字母下划线）、{{PACK:分层.name}}、{{VAR:名}} 冒号语法，"
                            + "{{global.键}}/{{RUN.键}}、roles 声明过的 {{角色.字段}}，"
                            + "或所在 loop 迭代变量 {{名}}）");
                }
            }
        }
    }

    
    private boolean isScopedRefAllowed(String token, java.util.Set<String> roleNames,
                                       java.util.Set<String> aliasNames) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String head = token.substring(0, dot);
        String key = token.substring(dot + 1);
        if (key.isBlank() || !SCOPED_REF_KEY.matcher(key).matches()) {
            return false;
        }
        if ("global".equals(head) || "RUN".equals(head)) {
            return true;
        }
        if (roleNames.contains(head)) {
            return true;
        }
        return aliasNames.contains(head);
    }

    
    private void validateRoles(JsonNode root, List<String> errors) {
        JsonNode roles = root.path("roles");
        if (roles.isMissingNode() || roles.isNull()) {
            return;
        }
        if (!roles.isArray()) {
            errors.add("roles 必须为角色数组（04 契约 X1：[{\"name\":..,\"binding\":..}]）");
            return;
        }
        List<String> seen = new ArrayList<>();
        for (JsonNode role : roles) {
            String name = role.path("name").asText("");
            String binding = role.path("binding").asText("");
            if (name.isBlank()) {
                errors.add("roles 角色缺少 name");
                continue;
            }
            if (!ROLE_NAME.matcher(name).matches()) {
                errors.add("角色名非法（须字母/数字/下划线/中文开头，不含点号）: " + name);
            }
            if (RESERVED_ROLE_NAMES.contains(name)) {
                errors.add("角色名撞保留命名空间（" + String.join("/", RESERVED_ROLE_NAMES)
                        + "）: " + name);
            }
            if (seen.contains(name)) {
                errors.add("角色重名: " + name);
            }
            seen.add(name);
            if (binding.isBlank()) {
                errors.add("角色 " + name + " 缺少 binding（per-vu/shared/pool(N)/none 四选一）");
            } else if (!ROLE_BINDING.matcher(binding).matches()) {
                errors.add("角色 " + name + " 绑定方式非法: " + binding
                        + "（仅 per-vu=每虚拟用户一份 / shared=批内共享 / pool(N)=N 份轮转池"
                        + "（N≥1 整数）/ none=不绑定）");
            }
        }
    }

    /**
     * sessionState structural gate (04 contract AV extension, top-level optional
     * = legacy action JSON zero drift): the segment must be an object carrying
     * exactly one of name / roleScope, as non-blank text without {{placeholders}}
     * — the ledger selector is a literal value, placeholder syntax here would
     * silently never resolve at claim time.
     */
    private void validateSessionState(JsonNode root, List<String> errors) {
        JsonNode segment = root.path("sessionState");
        if (segment.isMissingNode() || segment.isNull()) {
            return;
        }
        if (!segment.isObject()) {
            errors.add("sessionState 必须为对象（04 契约 AV：{\"name\":登录态名} 或 {\"roleScope\":角色名}）");
            return;
        }
        JsonNode name = segment.path("name");
        JsonNode role = segment.path("roleScope");
        boolean hasName = !name.isMissingNode() && !name.isNull();
        boolean hasRole = !role.isMissingNode() && !role.isNull();
        if (hasName && hasRole) {
            errors.add("sessionState 的 name 与 roleScope 只能二选一（name=登录态名精确指向，roleScope=按测试角色解析）");
            return;
        }
        if (!hasName && !hasRole) {
            errors.add("sessionState 必须给出 name（登录态名）或 roleScope（测试角色名）之一");
            return;
        }
        JsonNode chosen = hasName ? name : role;
        String label = hasName ? "name" : "roleScope";
        if (!chosen.isTextual() || chosen.asText().isBlank()) {
            errors.add("sessionState." + label + " 必须为非空文本（须为登录态账本中的字面值）");
            return;
        }
        if (chosen.asText().contains("{{")) {
            errors.add("sessionState." + label + " 不接受 {{占位符}}——登录态选择器必须是账本字面值: "
                    + chosen.asText());
        }
    }

    private boolean isAllowedAction(String action) {
        if (BASE_ACTIONS.contains(action)) {
            return true;
        }
        if (UiActionContract.isBaselineVerb(action)) {
            return true;
        }
        for (String ns : NAMESPACES) {
            if (action.startsWith(ns)) {
                return true;
            }
        }
        return false;
    }

    
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    
    private void validateNonUiStep(String stepLabel, String action, JsonNode step, List<String> errors) {
        if ("sql.exec".equals(action)) {
            errors.add(stepLabel + " sql.exec（受控 DML）AI 禁止自动生成——请在该步 description 生成"
                    + "注释建议「需人工添加清理/造数步骤」，由人工显式书写保存");
            return;
        }
        String violation = switch (action) {
            case "console.assert" -> NonUiActionContract.consoleAssertViolation(step);
            case "net.assert" -> NonUiActionContract.netAssertViolation(step);
            case "http.request" -> NonUiActionContract.httpRequestViolation(step);
            case "sql.assert", "sql.query" -> sqlStepViolation(action, step);
            default -> null;
        };
        if (violation != null) {
            errors.add(stepLabel + " " + action + " 步格式不符：" + violation);
        }
    }

    
    private String sqlStepViolation(String action, JsonNode step) {
        JsonNode conn = step.get("conn");
        if (conn == null || conn.isNull() || conn.asText().isBlank()) {
            return "还差数据库连接名：请在该步骤填写 conn（连接在项目×环境挂接中选择，占位符写法例 {{ENV:DB_SIT}}）";
        }
        return SqlStatementContract.violation(action,
                step.path("sql").asText(null), step.get("params"));
    }

    
    private void validateVarChain(JsonNode root, List<String> errors) {
        java.util.Set<String> declared = new java.util.HashSet<>();
        List<String> missing = new ArrayList<>();
        collectVarChain(root.path("setup"), declared, missing);
        collectVarChain(root.path("steps"), declared, missing);
        if (!missing.isEmpty()) {
            errors.add("未定义变量引用 "
                    + String.join("、", missing.stream().map(m -> "{{" + m + "}}").toList())
                    + "（v2.22 变量链：仅前序步 saveAs/capture 声明可引用——检查造数步是否遗漏）");
        }
    }

    private void collectVarChain(JsonNode steps, java.util.Set<String> declared, List<String> missing) {
        if (!steps.isArray()) {
            return;
        }
        for (JsonNode step : steps) {
            if (!step.isObject()) {
                continue;
            }
            step.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals("steps") && !entry.getKey().equals("elseSteps")) {
                    scanVarRefs(entry.getValue(), declared, missing);
                }
            });
            for (String field : new String[]{"saveAs", "capture"}) {
                JsonNode decl = step.get(field);
                if (decl != null && decl.isTextual() && !decl.asText().isBlank()) {
                    declared.add(decl.asText());
                }
            }
            collectVarChain(step.path("steps"), declared, missing);
            collectVarChain(step.path("elseSteps"), declared, missing);
        }
    }

    
    private static final java.util.regex.Pattern VAR_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*");

    
    private void scanVarRefs(JsonNode value, java.util.Set<String> declared, List<String> missing) {
        if (value.isTextual()) {
            java.util.regex.Matcher matcher = PLACEHOLDER_TOKEN.matcher(value.asText());
            while (matcher.find()) {
                String token = matcher.group(1);
                String name;
                boolean runForm;
                if (token.startsWith("VAR:")) {
                    name = token.substring(4);
                    runForm = false;
                } else if (token.startsWith("RUN.")) {
                    name = token.substring(4);
                    runForm = true;
                } else {
                    continue;
                }
                java.util.regex.Pattern nameGrammar = runForm ? SCOPED_REF_KEY : VAR_NAME;
                if (!nameGrammar.matcher(name).matches()) {
                    continue;
                }
                String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
                if (!declared.contains(head) && !missing.contains(token)) {
                    missing.add(token);
                }
            }
        } else if (value.isContainerNode()) {
            value.forEach(child -> scanVarRefs(child, declared, missing));
        }
    }
}
