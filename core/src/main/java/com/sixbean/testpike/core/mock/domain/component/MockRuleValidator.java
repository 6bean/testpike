package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Save-chain validation single point for mock rules (AG red line: one gate for
 * UI, snapshot loader and executor alike — validation lives in core, never a
 * second copy downstream).
 * <p>Scope of the shell : the structural minimum every later segment
 * depends on — legal verb set, path match type spectrum with regex compiling at
 * save time (a broken pattern must fail the editor, not the traffic port),
 * jsonb payload shapes matching the table CHECK twins but with plain-word
 * errors, routing-handle shape (it enters the URL path), and the enum columns
 * of both tables. The production-env ban needs tp_project_env and therefore
 * lives in MockSpaceServiceImpl (a CHECK cannot join).
 */
@Component
public class MockRuleValidator {

    /** Verbs the traffic plane routes; ANY is the wildcard stored as ANY. */
    static final List<String> METHODS =
            List.of("ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    static final List<String> PATH_MATCH_TYPES = List.of("exact", "prefix", "regex", "template");
    static final List<String> BASE_PATH_MODES = List.of("prefix", "strict");
    static final List<String> AUTH_MODES = List.of("none", "token", "cred");
    /**
 * The three-rung miss ladder from the design , single source
 * = common MockMissPolicies (the save chain, the policy door and the
 * traffic plane's tolerant read consume the same list — red line 6).
 */
    public static final List<String> MISS_POLICIES =
            com.sixbean.testpike.common.mock.MockMissPolicies.ALL;

    /** Public policy gate reused by the standalone policy door (no second spectrum). */
    public void validateMissPolicy(String missPolicy) {
        oneOf(missPolicy == null ? com.sixbean.testpike.common.mock.MockMissPolicies.DEFAULT_POLICY
                        : missPolicy, MISS_POLICIES, "未命中策略");
    }
    static final List<String> STUB_SOURCES =
            List.of("manual", "openapi", "record", "nl-ai", "case-flip");

    /** Routing handle shape: lowercase letters/digits/hyphen/underscore, starts
     * alphanumeric, 1-60 chars — it is spliced into /mocks/{spaceKey}/*. */
    private static final Pattern SPACE_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,59}");

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Matching kernel reference for matcher-row legality: the save chain and
     * the traffic plane share one frozen key set (no second validator).
     */
    private final MockMatchEngine matchEngine;

    public MockRuleValidator(MockMatchEngine matchEngine) {
        this.matchEngine = matchEngine;
    }

    public void validateSpace(MockSpaceInput input) {
        requireText(input.name(), "空间名称必填", 120);
        String key = trimToNull(input.spaceKey());
        if (key == null || !SPACE_KEY.matcher(key).matches()) {
            throw new ServiceException(
                    "路由标识只能用小写字母、数字、下划线和连字符，且以字母或数字开头（它会进入访问路径 /mocks/ 之下）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.alias() != null && !input.alias().isBlank()
                && !SPACE_KEY.matcher(input.alias().trim()).matches()) {
            throw new ServiceException("空间别名的字符规则与路由标识相同", BizErrorCode.VALIDATION_ERROR);
        }
        oneOf(input.basePathMode() == null ? "prefix" : input.basePathMode(),
                BASE_PATH_MODES, "路径基准模式");
        oneOf(input.authMode() == null ? "none" : input.authMode(), AUTH_MODES, "鉴权模式");
        // Miss ladder choice : omitted = the derived ladder every
        // space had before the policy column existed, never a guessed rung.
        oneOf(input.missPolicy() == null
                        ? com.sixbean.testpike.common.mock.MockMissPolicies.DEFAULT_POLICY
                        : input.missPolicy(),
                MISS_POLICIES, "未命中策略");
        if (input.rateLimitRps() != null && input.rateLimitRps() <= 0) {
            throw new ServiceException("限流 RPS 必须为正数，或留空走系统默认", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.quotaDaily() != null && input.quotaDaily() <= 0) {
            throw new ServiceException("日配额必须为正数，或留空走系统默认", BizErrorCode.VALIDATION_ERROR);
        }
        // Chaos dial: null = no injection; anything set must sit inside the
        // honest percentage range (the DB CHECK only guards side-door writes).
        if (input.chaosPercent() != null
                && (input.chaosPercent() < 0 || input.chaosPercent() > 100)) {
            throw new ServiceException("故障注入比例（chaos）必须在 0-100 之间（百分比），"
                    + "当前值 " + input.chaosPercent() + " 无法诚实地解释", BizErrorCode.VALIDATION_ERROR);
        }
    }

    public void validateStub(MockStubInput input) {
        requireText(input.name(), "规则名称必填", 120);
        oneOf(input.method() == null ? null : input.method().trim().toUpperCase(),
                METHODS, "HTTP 方法");
        oneOf(input.pathMatchType() == null ? "exact" : input.pathMatchType(),
                PATH_MATCH_TYPES, "路径匹配类型");
        String pattern = requireText(input.pathPattern(), "路径模式必填", 300);
        if (!pattern.startsWith("/")) {
            throw new ServiceException("路径模式必须以 / 开头（相对空间路由根）", BizErrorCode.VALIDATION_ERROR);
        }
        if ("regex".equals(input.pathMatchType())) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new ServiceException("正则路径模式无法编译：" + e.getDescription(),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("template".equals(input.pathMatchType()) && pattern.contains("{")
                != pattern.contains("}")) {
            throw new ServiceException("模板路径的花括号捕获必须成对出现", BizErrorCode.VALIDATION_ERROR);
        }
        oneOf(input.source() == null ? "manual" : input.source(), STUB_SOURCES, "规则来源");
        requireJsonShape(input.matchersJson(), true, "匹配器集合（matchers）");
        String rowViolation = matchEngine.matcherRowViolation(input.matchersJson());
        if (rowViolation != null) {
            throw new ServiceException(rowViolation, BizErrorCode.VALIDATION_ERROR);
        }
        requireJsonShape(input.responseJson(), false, "响应构造（response）");
        // Fault parameter-domain gate: the response builder may carry a
        // `fault` key — its shape must be legal BEFORE the row lands, sharing
        // one spectrum with the data-plane injector (MockFaultRules).
        String faultViolation = faultViolationIn(input.responseJson());
        if (faultViolation != null) {
            throw new ServiceException(faultViolation, BizErrorCode.VALIDATION_ERROR);
        }
        if (input.scenarioName() != null && input.scenarioName().length() > 80) {
            throw new ServiceException("场景名超长（上限 80 字符）", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Parse the response-builder payload (already shape-checked) and hand the
     * {@code fault} key to the shared parameter-domain spectrum; returns the
     * violation message or null when legal/unparseable (the shape gate owns
     * unparseable JSON — this gate only judges fault keys).
     */
    private String faultViolationIn(String responseJson) {
        String text = trimToNull(responseJson);
        if (text == null) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(text);
            return com.sixbean.testpike.common.mock.MockFaultRules
                    .faultViolation(root.get("fault"), root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** jsonb payload gate: parseable and of the expected JSON kind, with a
     * plain-word error instead of the database CHECK 500. */
    private void requireJsonShape(String json, boolean wantArray, String label) {
        String text = trimToNull(json);
        if (text == null) {
            return; // column default applies
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(text);
            if (wantArray ? !node.isArray() : !node.isObject()) {
                throw new ServiceException(label + (wantArray
                        ? "必须是 JSON 数组（每条匹配器一行）"
                        : "必须是 JSON 对象（status/headers/body 等键）"), BizErrorCode.VALIDATION_ERROR);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(label + "不是合法 JSON：" + e.getOriginalMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String requireText(String value, String plainWord, int maxLen) {
        String text = trimToNull(value);
        if (text == null) {
            throw new ServiceException(plainWord + "不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (text.length() > maxLen) {
            throw new ServiceException(plainWord + "超长（上限 " + maxLen + " 字符）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return text;
    }

    private void oneOf(String value, List<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) {
            throw new ServiceException(label + "不支持「" + value + "」，可选值：" + String.join(" / ", allowed),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
