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
                        : missPolicy,
                MISS_POLICIES, "「未命中策略（miss 阶梯）」", MockRuleRefusals.SPACE_ENTRY);
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
        requireText(input.name(), "「空间名称」", 120, MockRuleRefusals.SPACE_ENTRY);
        String key = trimToNull(input.spaceKey());
        if (key == null || !SPACE_KEY.matcher(key).matches()) {
            throw new ServiceException(MockRuleRefusals.spaceKeyIllegal(key == null ? "（留空）" : key),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.alias() != null && !input.alias().isBlank()
                && !SPACE_KEY.matcher(input.alias().trim()).matches()) {
            throw new ServiceException(MockRuleRefusals.spaceAliasIllegal(input.alias().trim()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        oneOf(input.basePathMode() == null ? "prefix" : input.basePathMode(),
                BASE_PATH_MODES, "「路径基准模式」", MockRuleRefusals.SPACE_ENTRY);
        oneOf(input.authMode() == null ? "none" : input.authMode(),
                AUTH_MODES, "「鉴权模式」", MockRuleRefusals.SPACE_ENTRY);
        // Miss ladder choice : omitted = the derived ladder every
        // space had before the policy column existed, never a guessed rung.
        oneOf(input.missPolicy() == null
                        ? com.sixbean.testpike.common.mock.MockMissPolicies.DEFAULT_POLICY
                        : input.missPolicy(),
                MISS_POLICIES, "「未命中策略（miss 阶梯）」", MockRuleRefusals.SPACE_ENTRY);
        if (input.rateLimitRps() != null && input.rateLimitRps() <= 0) {
            throw new ServiceException(MockRuleRefusals.rateLimitNotPositive(input.rateLimitRps()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.quotaDaily() != null && input.quotaDaily() <= 0) {
            throw new ServiceException(MockRuleRefusals.dailyQuotaNotPositive(input.quotaDaily()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Chaos dial: null = no injection; anything set must sit inside the
        // honest percentage range (the DB CHECK only guards side-door writes).
        if (input.chaosPercent() != null
                && (input.chaosPercent() < 0 || input.chaosPercent() > 100)) {
            throw new ServiceException(MockRuleRefusals.chaosOutOfRange(input.chaosPercent()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    public void validateStub(MockStubInput input) {
        requireText(input.name(), "「规则名称」", 120, MockRuleRefusals.RULE_ENTRY);
        oneOf(input.method() == null ? null : input.method().trim().toUpperCase(),
                METHODS, "「请求方法」", MockRuleRefusals.RULE_ENTRY);
        oneOf(input.pathMatchType() == null ? "exact" : input.pathMatchType(),
                PATH_MATCH_TYPES, "「路径匹配方式」", MockRuleRefusals.RULE_ENTRY);
        String pattern = requireText(input.pathPattern(), "「路径」", 300, MockRuleRefusals.RULE_ENTRY);
        if (!pattern.startsWith("/")) {
            throw new ServiceException(MockRuleRefusals.pathPatternMissingSlash(pattern),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("regex".equals(input.pathMatchType())) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new ServiceException(
                        MockRuleRefusals.regexPathUncompilable(pattern, e.getDescription()),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        if ("template".equals(input.pathMatchType()) && pattern.contains("{")
                != pattern.contains("}")) {
            throw new ServiceException(MockRuleRefusals.templateBracesUnpaired(pattern),
                    BizErrorCode.VALIDATION_ERROR);
        }
        oneOf(input.source() == null ? "manual" : input.source(),
                STUB_SOURCES, "「来源」", MockRuleRefusals.RULE_ENTRY);
        requireJsonShape(input.matchersJson(), true, "「匹配条件」");
        String rowViolation = matchEngine.matcherRowViolation(input.matchersJson());
        if (rowViolation != null) {
            throw new ServiceException(rowViolation, BizErrorCode.VALIDATION_ERROR);
        }
        requireJsonShape(input.responseJson(), false, "「响应行为」");
        // Fault parameter-domain gate: the response builder may carry a
        // `fault` key — its shape must be legal BEFORE the row lands, sharing
        // one spectrum with the data-plane injector (MockFaultRules).
        String faultViolation = faultViolationIn(input.responseJson());
        if (faultViolation != null) {
            throw new ServiceException(faultViolation, BizErrorCode.VALIDATION_ERROR);
        }
        if (input.scenarioName() != null && input.scenarioName().length() > 80) {
            throw new ServiceException(
                    MockRuleRefusals.scenarioNameTooLong(input.scenarioName().length()),
                    BizErrorCode.VALIDATION_ERROR);
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
     * refusal that echoes the payload, names the on-screen field and points to
     * the form instead of letting the database CHECK answer with a 500. */
    private void requireJsonShape(String json, boolean wantArray, String fieldLabel) {
        String text = trimToNull(json);
        if (text == null) {
            return; // column default applies
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(text);
            if (wantArray ? !node.isArray() : !node.isObject()) {
                throw new ServiceException(wantArray
                        ? MockRuleRefusals.matchersMustBeArray(text)
                        : MockRuleRefusals.responseMustBeObject(text),
                        BizErrorCode.VALIDATION_ERROR);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(
                    MockRuleRefusals.jsonMalformed(fieldLabel, text, e.getOriginalMessage()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String requireText(String value, String fieldLabel, int maxLen, String entry) {
        String text = trimToNull(value);
        if (text == null) {
            throw new ServiceException(MockRuleRefusals.blankRequired(fieldLabel, entry),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (text.length() > maxLen) {
            throw new ServiceException(
                    MockRuleRefusals.tooLong(fieldLabel, maxLen, text.length()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return text;
    }

    private void oneOf(String value, List<String> allowed, String fieldLabel, String entry) {
        if (value == null || !allowed.contains(value)) {
            throw new ServiceException(
                    MockRuleRefusals.valueNotAllowed(fieldLabel, value, allowed, entry),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
