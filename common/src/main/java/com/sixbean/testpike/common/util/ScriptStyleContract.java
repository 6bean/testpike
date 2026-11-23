package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <ul>
 * </ul>
 */
public final class ScriptStyleContract {

    
    public static final String CONFIG_KEY_ENABLED = "script.style-gate.enabled";

    
    public static boolean isSwitchOn(String raw) {
        return raw != null && ("1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()));
    }

    
    public static String describe(List<ScriptStyleViolation> violations) {
        StringBuilder sb = new StringBuilder();
        violations.stream().limit(10).forEach(v -> {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("第 ").append(v.stepNo()).append(" 步[").append(v.category()).append("]：")
                    .append(v.problem()).append("；整改：").append(v.suggestion());
        });
        if (violations.size() > 10) {
            sb.append("；另有 ").append(violations.size() - 10).append(" 条同类违例未列出");
        }
        return sb.toString();
    }

    
    public static final String CATEGORY_FRAGILE_SELECTOR = "FRAGILE_SELECTOR";
    
    public static final String CATEGORY_HARD_WAIT = "HARD_WAIT";
    
    public static final String CATEGORY_NO_ASSERTION = "NO_ASSERTION";
    
    public static final String CATEGORY_PLAINTEXT_CREDENTIAL = "PLAINTEXT_CREDENTIAL";
    
    public static final String CATEGORY_STEP_DESC = "STEP_DESC";
    
    public static final String CATEGORY_MISSING_CHAIN = "MISSING_CHAIN";
    
    public static final String CATEGORY_PARSE_ERROR = "PARSE_ERROR";

    
    private static final Pattern CSS_POSITIONAL =
            Pattern.compile(":nth-(child|of-type)\\(", Pattern.CASE_INSENSITIVE);

    
    private static final Pattern XPATH_POSITIONAL = Pattern.compile("\\[\\d+]");

    
    private static final Pattern CREDENTIAL_FIELD =
            Pattern.compile("password|passwd|pwd|token|secret|api[-_]?key", Pattern.CASE_INSENSITIVE);

    
    private static final Pattern PLACEHOLDER_VALUE = Pattern.compile("^\\{\\{\\w+:.*}}$", Pattern.DOTALL);

    
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4e00-\\u9fff]");

    
    private static final List<String> ASSERTION_PREFIXES =
            List.of("expect", "net.assert", "console.assert", "sql.assert", "visual.assert", "erp.assert");

    
    private static final List<String> CHAIN_REQUIRED_ACTIONS = List.of("click", "input", "select");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScriptStyleContract() {
    }

    
    public static List<ScriptStyleViolation> check(String actionJson) {
        List<ScriptStyleViolation> violations = new ArrayList<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(actionJson);
        } catch (Exception e) {
            violations.add(new ScriptStyleViolation(0, CATEGORY_PARSE_ERROR,
                    "动作 JSON 非法，无法进行姿势判定",
                    "请确认顶层为含 steps 动作数组的动作 JSON 结构后重新提交"));
            return violations;
        }
        if (root == null || !root.isObject()) {
            violations.add(new ScriptStyleViolation(0, CATEGORY_PARSE_ERROR,
                    "动作 JSON 非法，无法进行姿势判定",
                    "请确认顶层为含 steps 动作数组的动作 JSON 结构后重新提交"));
            return violations;
        }
        ScriptStyleScanCounter counter = new ScriptStyleScanCounter();
        walk(root.path("setup"), violations, counter);
        walk(root.path("steps"), violations, counter);
        if (counter.hasOperation && !counter.hasAssertion) {
            violations.add(new ScriptStyleViolation(0, CATEGORY_NO_ASSERTION,
                    "整条脚本没有任何断言步（共 " + counter.operationSteps + " 个操作步只操作不验证）",
                    "在关键结果处补充 expect（或 net.assert/sql.assert 断言原语），明确「验证什么才算通过」"));
        }
        return violations;
    }

    
    private static void walk(JsonNode stepArray, List<ScriptStyleViolation> violations, ScriptStyleScanCounter counter) {
        if (!stepArray.isArray()) {
            return;
        }
        for (JsonNode step : stepArray) {
            if (!step.isObject()) {
                continue;
            }
            int stepNo = ++counter.next;
            inspectStep(step, stepNo, violations, counter);
            walk(step.path("steps"), violations, counter);
            walk(step.path("elseSteps"), violations, counter);
        }
    }

    
    private static void inspectStep(JsonNode step, int stepNo,
                                    List<ScriptStyleViolation> violations, ScriptStyleScanCounter counter) {
        String action = step.path("action").asText("");
        checkDescription(step, stepNo, action, violations);
        if (isAssertionAction(action)) {
            counter.hasAssertion = true;
        }
        if ("wait".equals(action)) {
            checkHardWait(step, stepNo, violations);
            return;
        }
        if (!step.path("target").isObject() && !step.has("value")) {
            return;
        }
        counter.hasOperation = counter.hasOperation || !action.isEmpty();
        if (step.path("target").isObject()) {
            counter.operationSteps++;
            checkFragileSelector(step, stepNo, violations);
            checkPlaintextCredential(step, stepNo, violations);
            checkMissingChain(step, stepNo, violations);
        }
    }

    
    private static void checkDescription(JsonNode step, int stepNo, String action,
                                         List<ScriptStyleViolation> violations) {
        String desc = step.path("description").asText("");
        if (desc.isBlank()) {
            violations.add(new ScriptStyleViolation(stepNo, CATEGORY_STEP_DESC,
                    "第 " + stepNo + " 步（" + action + "）缺少步骤描述",
                    "为该步补充中文描述，写清这一步要做什么、验证什么意图"));
        } else if (!CJK_CHAR.matcher(desc).find()) {
            violations.add(new ScriptStyleViolation(stepNo, CATEGORY_STEP_DESC,
                    "第 " + stepNo + " 步（" + action + "）步骤描述非中文：「" + desc + "」",
                    "步骤描述请使用中文（评审工作台直接展示，写清操作内容）"));
        }
    }

    
    private static void checkHardWait(JsonNode step, int stepNo,
                                      List<ScriptStyleViolation> violations) {
        if (step.has("ms") && !step.path("target").isObject()) {
            violations.add(new ScriptStyleViolation(stepNo, CATEGORY_HARD_WAIT,
                    "第 " + stepNo + " 步用固定 " + step.path("ms").asInt() + " 毫秒硬等待",
                    "改用条件等待：wait 步给出 target 定位符与 state（visible/attached/detached/hidden），等待页面真实状态而非时间"));
        }
    }

    
    private static void checkFragileSelector(JsonNode step, int stepNo,
                                             List<ScriptStyleViolation> violations) {
        JsonNode target = step.path("target");
        String css = target.path("css").asText("");
        String xpath = target.path("xpath").asText("");
        boolean fragile = CSS_POSITIONAL.matcher(css).find()
                || XPATH_POSITIONAL.matcher(xpath).find();
        if (fragile) {
            violations.add(new ScriptStyleViolation(stepNo, CATEGORY_FRAGILE_SELECTOR,
                    "第 " + stepNo + " 步定位依赖元素位置序号（nth-child/位置谓词）："
                            + (!css.isEmpty() ? css : xpath),
                    "改用稳定语义定位：id、text、role 或业务属性选择器（如 [name=...]），避免 DOM 顺序变化导致脆断"));
        }
    }

    
    private static void checkPlaintextCredential(JsonNode step, int stepNo,
                                                 List<ScriptStyleViolation> violations) {
        if (!"input".equals(step.path("action").asText(""))) {
            return;
        }
        String value = step.path("value").asText("");
        if (value.isBlank() || PLACEHOLDER_VALUE.matcher(value.trim()).matches()) {
            return;
        }
        JsonNode target = step.path("target");
        String haystack = target.path("css").asText("") + " "
                + target.path("xpath").asText("") + " "
                + target.path("id").asText("") + " "
                + target.path("name").asText("");
        if (CREDENTIAL_FIELD.matcher(haystack.toUpperCase(Locale.ROOT)).find()) {
            violations.add(new ScriptStyleViolation(stepNo, CATEGORY_PLAINTEXT_CREDENTIAL,
                    "第 " + stepNo + " 步向口令/凭据类字段直接写入明文值",
                    "凭据与敏感数据改用占位符注入：{{ENV:键名}}（环境）或 {{DATA:键名}}（数据池），禁止字面量入库入日志"));
        }
    }

    
    private static void checkMissingChain(JsonNode step, int stepNo,
                                          List<ScriptStyleViolation> violations) {
        if (!CHAIN_REQUIRED_ACTIONS.contains(step.path("action").asText(""))) {
            return;
        }
        JsonNode chain = step.path("target").path("chain");
        if (!chain.isArray() || chain.isEmpty()) {
            violations.add(new ScriptStyleViolation(stepNo, CATEGORY_MISSING_CHAIN,
                    "第 " + stepNo + " 步（" + step.path("action").asText("") + "）定位只有单一策略，无降级链",
                    "在 target.chain 里补充 ≥1 条降级定位策略（css/xpath/text/role 组合），主定位失效时自动降级"));
        }
    }

    
    private static boolean isAssertionAction(String action) {
        return ASSERTION_PREFIXES.stream().anyMatch(action::startsWith);
    }
}
