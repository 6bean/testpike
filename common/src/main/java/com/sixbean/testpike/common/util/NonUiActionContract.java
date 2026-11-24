package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.regex.Pattern;


public final class NonUiActionContract {

    // Contract-wording alignment (docs/04 honest-marking rule): when a step
    // has no truth source (no probe candidate segment, no contract / element
    // ledger hit), URL / locator / expected-value literals in stage2 output
    // must carry the manual-refinement mark in description; detection lives
    // in FidelityGate.controlRefs (no-evidence shape), not in this validator
    // — this class checks structure only and never audits evidence.

 
    public static final Set<String> NON_UI_ACTIONS = Set.of(
            "console.assert", "net.assert", "http.request",
            "sql.assert", "sql.exec", "sql.query");

 
    public static final Set<String> CONSOLE_MODES = Set.of("fail", "warn", "collect");

    
    public static final Set<String> ASSERT_WINDOWS = Set.of("sinceLast", "full");

    
    public static final Set<String> CONSOLE_LEVELS =
            Set.of("error", "warning", "log", "info", "debug");

    
    public static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

 
    public static final Set<String> POLL_UNTIL = Set.of("match");

 
    public static final Pattern JSON_PATH_SUBSET =
            Pattern.compile("^\\$((\\.[A-Za-z_]\\w*)|\\[[0-9]+\\])*$");

    /** Full set of declared expect keys (04 contract 2.1.2; unknown keys fail closed). */
    public static final Set<String> EXPECT_KEYS =
            Set.of("status", "body", "durationMs", "headers");

    
    private static final Pattern SAVE_AS_NAME = Pattern.compile("(?:global\\.)?[A-Za-z_]\\w*");

    private NonUiActionContract() {
    }

    
    public static boolean isNonUiAction(String action) {
        return action != null && NON_UI_ACTIONS.contains(action);
    }

    
    public static Boolean isAssert(String action, JsonNode step) {
        return switch (action == null ? "" : action) {
            case "console.assert", "net.assert", "sql.assert" -> true;
            case "http.request" -> {
                JsonNode expect = step == null ? null : step.get("expect");
                yield expect != null && !expect.isNull();
            }
            case "sql.exec", "sql.query" -> false;
            default -> null;
        };
    }

    
    public static String consoleAssertViolation(JsonNode step) {
        JsonNode mode = step.get("mode");
        if (mode != null && !mode.isNull() && !CONSOLE_MODES.contains(mode.asText())) {
            return "console.assert 的 mode 仅支持 fail / warn / collect，当前值「" + mode.asText() + "」";
        }
        JsonNode scope = step.get("scope");
        if (scope != null && !scope.isNull() && !ASSERT_WINDOWS.contains(scope.asText())) {
            return "console.assert 的 scope 仅支持 sinceLast / full，当前值「" + scope.asText() + "」";
        }
        JsonNode level = step.get("level");
        if (level != null && !level.isNull()) {
            if (!level.isArray() || level.isEmpty()) {
                return "console.assert 的 level 必须是非空数组（例 [\"error\"]）";
            }
            for (JsonNode item : level) {
                if (!CONSOLE_LEVELS.contains(item.asText())) {
                    return "console.assert 的 level 含非法值「" + item.asText()
                            + "」（仅 error / warning / log / info / debug）";
                }
            }
        }
        JsonNode ignore = step.get("ignorePatterns");
        if (ignore != null && !ignore.isNull()) {
            if (!ignore.isArray()) {
                return "console.assert 的 ignorePatterns 必须是字符串数组";
            }
            for (JsonNode item : ignore) {
                String violation = globViolation(item.asText(), "ignorePatterns");
                if (violation != null) {
                    return violation;
                }
            }
        }
        return null;
    }

    
    public static String netAssertViolation(JsonNode step) {
        JsonNode match = step.get("match");
        if (match == null || match.isNull() || !match.isObject()) {
            return "net.assert 还差匹配条件：请在该步骤填写 match 规则（含 urlPattern，例 {\"urlPattern\":\"*/api/v1/orders*\"}）";
        }
        String patternViolation = globViolation(match.path("urlPattern").asText(null), "match.urlPattern");
        if (patternViolation != null) {
            return patternViolation;
        }
        JsonNode method = match.get("method");
        if (method != null && !method.isNull()
                && !HTTP_METHODS.contains(method.asText().toUpperCase(java.util.Locale.ROOT))) {
            return "net.assert 的 match.method 不是合法 HTTP 动词，当前值「" + method.asText() + "」";
        }
        JsonNode window = step.get("window");
        if (window != null && !window.isNull() && !ASSERT_WINDOWS.contains(window.asText())) {
            return "net.assert 的 window 仅支持 sinceLast / full，当前值「" + window.asText() + "」";
        }
        return expectObjectViolation("net.assert", step.get("expect"));
    }

    
    public static String httpRequestViolation(JsonNode step) {
        JsonNode method = step.get("method");
        if (method != null && !method.isNull()
                && !HTTP_METHODS.contains(method.asText().toUpperCase(java.util.Locale.ROOT))) {
            return "http.request 的 method 不是合法 HTTP 动词，当前值「" + method.asText() + "」";
        }
        String expectViolation = expectObjectViolation("http.request", step.get("expect"));
        if (expectViolation != null) {
            return expectViolation;
        }
        JsonNode poll = step.get("poll");
        if (poll != null && !poll.isNull()) {
            if (!poll.isObject()) {
                return "http.request 的 poll 必须是对象（intervalMs/timeoutMs/until）";
            }
            JsonNode until = poll.get("until");
            if (until != null && !until.isNull()
                    && !POLL_UNTIL.contains(until.asText())) {
                return "http.request 的 poll.until 仅支持 match（expect 满足即停），当前值「"
                        + until.asText() + "」";
            }
        }
        return saveAsViolation("http.request", step);
    }

    
    public static String saveAsViolation(String action, JsonNode step) {
        JsonNode saveAs = step.get("saveAs");
        if (saveAs == null || saveAs.isNull()) {
            return null;
        }
        String value = saveAs.asText();
        if (!SAVE_AS_NAME.matcher(value).matches()) {
            return action + " 的 saveAs 必须是合法变量名（字母/下划线开头，后续字母数字下划线，"
                    + "跨集合共享请用 global.键名，供 {{VAR:"
                    + (value.isBlank() ? "名字" : value) + "}} 引用），当前值「" + value + "」";
        }
        return null;
    }

    /**
     * Full legal-domain check of the expect object (04 contract 2.1.2; shared by
     * net.assert and http.request — single source).
     * <p>Extension: the existing status / body face is untouched; adds
     * durationMs{op=range,expect=[min,max],bounds} and headers[{name,op,expect}]
     * — the promotion chain maps WS2 assertion
     * form rows into this object. Unknown keys fail closed: a mistyped assertion
     * key that silently no-ops would let a broken case pass. Enum / range / regex
     * judgements delegate to ExpectMatchContract; this class only orchestrates.
     */
    public static String expectObjectViolation(String action, JsonNode expect) {
        if (expect == null || expect.isNull()) {
            return null;
        }
        if (!expect.isObject()) {
            return action + " 的 expect 必须是对象（status / body / durationMs / headers）";
        }
        java.util.Iterator<String> keys = expect.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!EXPECT_KEYS.contains(key)) {
                return action + " 的 expect 含未声明键「" + key + "」（仅支持 "
                        + String.join(" / ", EXPECT_KEYS.stream().sorted().toList())
                        + "——拼错的断言键会被静默忽略，等于断言从未生效，已拒）";
            }
        }
        String bodyViolation = expectBodyViolation(action, expect);
        if (bodyViolation != null) {
            return bodyViolation;
        }
        String durationViolation = durationExpectViolation(action, expect.get("durationMs"));
        if (durationViolation != null) {
            return durationViolation;
        }
        return headerExpectViolation(action, expect.get("headers"));
    }

    /**
     * expect.durationMs judgement: {op, expect:[min,max], bounds?}.
     * Same domain as the WS2 form duration row (identical message corpus —
     * one judgement, two carriers, so the promotion mapping needs no
     * translation); execution enforces the numeric bounds comparison against
     * the observed elapsed time.
     */
    private static String durationExpectViolation(String action, JsonNode duration) {
        if (duration == null || duration.isNull()) {
            return null;
        }
        if (!duration.isObject()) {
            return action + " 的 durationMs 必须是对象（op=range + expect=[min,max]，例 "
                    + "{\"op\":\"range\",\"expect\":[0,3000]}）";
        }
        String op = duration.path("op").asText("");
        // Same domain as the WS2 form duration row (ApiAssertionFormContract
        // .durationViolation): op must be in the matcher family and expect must
        // be a [min,max] numeric pair — one judgement domain, no second spec.
        String enumViolation = ExpectMatchContract.matchTypeViolation(op);
        if (enumViolation != null) {
            return "耗时断言" + enumViolation + "（例：op=range, expect=[0,3000]）";
        }
        JsonNode pair = duration.get("expect");
        if (pair == null || !pair.isArray() || pair.isEmpty() || pair.size() > 2) {
            return "耗时断言 expect 必须是 [min,max] 数值对（例 [0,3000]）";
        }
        for (JsonNode bound : pair) {
            if (!bound.isNull() && !bound.isNumber()) {
                return "耗时断言的区间端点必须是数值，当前值「" + bound.asText() + "」";
            }
        }
        JsonNode bounds = duration.get("bounds");
        if (bounds != null && !bounds.isNull() && !ExpectMatchContract.BOUNDS.contains(bounds.asText())) {
            return "bounds 仅支持 "
                    + String.join(" / ", ExpectMatchContract.boundsOptions())
                    + "，当前值「" + bounds.asText() + "」";
        }
        return null;
    }

    /**
     * expect.headers judgement: array of {name, op, expect[, bounds/pattern]}.
     * Row shape mirrors the WS2 form header row; the op family and the regex /
     * range chains all delegate to ExpectMatchContract (single source, no
     * second spec — red line 6), this method only orchestrates rows.
     */
    private static String headerExpectViolation(String action, JsonNode headers) {
        if (headers == null || headers.isNull()) {
            return null;
        }
        if (!headers.isArray() || headers.isEmpty()) {
            return action + " 的 headers 必须是非空数组（每项 name + op + expect，例 "
                    + "[{\"name\":\"Content-Type\",\"op\":\"contains\",\"expect\":\"json\"}]）";
        }
        for (JsonNode row : headers) {
            if (!row.isObject()) {
                return action + " 的 headers 每项必须是对象（name / op / expect）";
            }
            if (row.path("name").asText("").isBlank()) {
                return "Header 断言还差头名：请在断言表单填写 name（例 name=Content-Type）";
            }
            String op = row.path("op").asText("");
            String enumViolation = ExpectMatchContract.matchTypeViolation(op);
            if (enumViolation != null) {
                return enumViolation;
            }
            if ("range".equals(op)) {
                JsonNode pair = row.get("expect");
                if (pair == null || !pair.isArray() || pair.isEmpty() || pair.size() > 2) {
                    return "区间断言 expect 必须是 [min,max] 数值对（例 [0,3000]）";
                }
                for (JsonNode bound : pair) {
                    if (!bound.isNull() && !bound.isNumber()) {
                        return "区间断言的区间端点必须是数值，当前值「" + bound.asText() + "」";
                    }
                }
                JsonNode bounds = row.get("bounds");
                if (bounds != null && !bounds.isNull()
                        && !ExpectMatchContract.BOUNDS.contains(bounds.asText())) {
                    return "bounds 仅支持 "
                            + String.join(" / ", ExpectMatchContract.boundsOptions())
                            + "，当前值「" + bounds.asText() + "」";
                }
            } else if ("regex".equals(op)) {
                String pattern = row.path("pattern").asText(
                        row.path("expect").asText(""));
                if (pattern.isBlank()) {
                    return "正则断言还差正则串：请在 expect 中填写正则表达式（例 ^ok$）";
                }
                String regexViolation = ExpectMatchContract.regexPatternViolation(
                        shapePattern(row, pattern));
                if (regexViolation != null) {
                    return regexViolation;
                }
            } else if (row.get("expect") == null || row.get("expect").isNull()) {
                return "断言还差期望值：请在表单填写 expect";
            }
        }
        return null;
    }

    /** Reshape a header row for ExpectMatchContract.regexPatternViolation (form rows carry the pattern in expect). */
    private static JsonNode shapePattern(JsonNode row, String pattern) {
        com.fasterxml.jackson.databind.node.ObjectNode shaped = row.deepCopy();
        shaped.put("pattern", pattern);
        return shaped;
    }

    
    public static String expectBodyViolation(String action, JsonNode expect) {
        if (expect == null || expect.isNull()) {
            return null;
        }
        JsonNode body = expect.get("body");
        if (body == null || body.isNull()) {
            return null;
        }
        if (!body.isArray()) {
            return action + " 的 expect.body 必须是数组（每项 path + B10 匹配器族）";
        }
        for (JsonNode item : body) {
            String pathViolation = jsonPathViolation(item.path("path").asText(null));
            if (pathViolation != null) {
                return action + " 的 " + pathViolation;
            }
            String matchViolation = ExpectMatchContract.expectViolation(item);
            if (matchViolation != null) {
                return action + " 的 expect.body：" + matchViolation;
            }
        }
        return null;
    }

    
    public static String jsonPathViolation(String path) {
        if (path == null || path.isBlank()) {
            return "expect.body 每一项还差字段路径：请填写 path（支持 $.a.b[0].c 写法）";
        }
        if (JSON_PATH_SUBSET.matcher(path.trim()).matches()) {
            return null;
        }
        return "JsonPath 仅支持受限子集（例 $.data.items[0].status）：禁 filter（[?(...)]）、"
                + "script、通配（* / ..）、切片（[1:2]）表达式，当前值「" + path + "」";
    }

    
    public static String globViolation(String pattern, String field) {
        if (pattern == null || pattern.isBlank()) {
            return "还差 " + field + "：请按通配写法填写（例 */api/v1/orders*）";
        }
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '[' || c == '(' || c == '{' || c == '\\') {
                return field + " 仅支持 glob 通配 * 与 ?（不允许 " + c
                        + " 等正则/字符类元字符），当前值「" + pattern + "」";
            }
        }
        return null;
    }
}
