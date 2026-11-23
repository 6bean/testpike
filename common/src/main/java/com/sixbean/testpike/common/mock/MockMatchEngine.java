package com.sixbean.testpike.common.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.NonUiActionContract;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matching kernel : decides which stub answers a mocked request.
 * Capability surface mirrors WireMock's matcher family set — method with ANY
 * wildcard, path four types (exact / prefix / regex / template {var} capture),
 * per-key query, case-insensitive header, cookie, and five body families
 * (exact / contains / regex / JSONPath restricted subset / form field).
 * <p>Single-source discipline: JSONPath rides the restricted subset frozen in
 * {@link NonUiActionContract} — no second dialect is invented here; XPath is
 * out of scope by design (P2). Arbitration = ascending priority, first stub
 * whose every matcher passes wins; each evaluated stub leaves a trace line so
 * the inspector can show the traffic-light trail. Anything the kernel cannot
 * understand (broken JSON, illegal path, bad regex) fails closed for that stub
 * — a misconfigured rule never silently swallows traffic by matching all.
 * <p>The same class serves the save chain via
 * {@link #matcherRowViolation(String)} so UI, snapshot and traffic plane all
 * speak one legality spectrum (validation single point, AG red line).
 */
@Component
public class MockMatchEngine {

    /** Matcher row kinds — the frozen key set of matchers_json rows. */
    static final Set<String> KINDS = Set.of("query", "header", "cookie", "body");

    /** Value-comparison modes for query/header/cookie/form rows. */
    static final Set<String> COMPARE_TYPES = Set.of("exact", "contains", "regex", "exists");

    /** Body row families (five types per F2; XPath deliberately absent). */
    static final Set<String> BODY_TYPES = Set.of("exact", "contains", "regex", "jsonpath", "form");

    /** JSONPath assertion modes on a body:jsonpath row. */
    static final Set<String> ASSERT_MODES = Set.of("equals", "exists", "type");

    /** Node type names accepted by assertMode=type. */
    static final Set<String> JSON_TYPES =
            Set.of("object", "array", "string", "number", "boolean", "null");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Adjudicate one request against a rule set: sort by priority, first full
     * match wins, every evaluated rule contributes trace lines.
     */
    public MockMatchVerdict match(MockMatchRequest request, List<MockStubRule> rules) {
        List<MockMatchTrace> traces = new ArrayList<>();
        List<MockStubRule> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparingInt(MockStubRule::priority));
        for (MockStubRule rule : ordered) {
            MockRuleEvaluation attempt = evaluate(rule, request);
            traces.addAll(attempt.traces());
            if (attempt.ok()) {
                return MockMatchVerdict.hit(rule, attempt.pathVars(), traces);
            }
        }
        return MockMatchVerdict.noMatch(traces);
    }

    /** Per-rule evaluation: method → path → matcher rows, stopping at the first failing facet. */
    private MockRuleEvaluation evaluate(MockStubRule rule, MockMatchRequest request) {
        List<MockMatchTrace> traces = new ArrayList<>();
        if (!methodMatches(rule.method(), request.method())) {
            traces.add(new MockMatchTrace(rule.name(), "method", MockMatchTrace.FAIL,
                    "期望方法 " + (rule.method() == null ? "?" : rule.method().toUpperCase())
                            + "，实际 " + request.method()));
            return new MockRuleEvaluation(false, Map.of(), traces);
        }
        Map<String, String> pathVars = new LinkedHashMap<>();
        String pathFail = pathViolation(rule.pathMatchType(), rule.pathPattern(),
                request.path(), pathVars);
        if (pathFail != null) {
            traces.add(new MockMatchTrace(rule.name(), "path", MockMatchTrace.FAIL, pathFail));
            return new MockRuleEvaluation(false, Map.of(), traces);
        }
        JsonNode rows;
        try {
            String json = rule.matchersJson() == null || rule.matchersJson().isBlank()
                    ? "[]" : rule.matchersJson();
            rows = objectMapper.readTree(json);
        } catch (Exception ex) {
            traces.add(new MockMatchTrace(rule.name(), "matchers", MockMatchTrace.FAIL,
                    "matchers 集合不是合法 JSON，规则按不命中处理（fail-closed，绝不匹配一切）"));
            return new MockRuleEvaluation(false, Map.of(), traces);
        }
        if (!rows.isArray()) {
            traces.add(new MockMatchTrace(rule.name(), "matchers", MockMatchTrace.FAIL,
                    "matchers 必须是 JSON 数组，规则按不命中处理"));
            return new MockRuleEvaluation(false, Map.of(), traces);
        }
        for (JsonNode row : rows) {
            String rowFail = rowViolation(rule.name(), row, request);
            if (rowFail != null) {
                traces.add(new MockMatchTrace(rule.name(), stepOf(row), MockMatchTrace.FAIL, rowFail));
                return new MockRuleEvaluation(false, Map.of(), traces);
            }
        }
        traces.add(new MockMatchTrace(rule.name(), "all", MockMatchTrace.PASS, null));
        return new MockRuleEvaluation(true, pathVars, traces);
    }

    /** ANY is the stored wildcard; every other value compares case-insensitively. */
    private boolean methodMatches(String ruleMethod, String requestMethod) {
        if (ruleMethod == null || ruleMethod.isBlank() || "ANY".equalsIgnoreCase(ruleMethod.trim())) {
            return true;
        }
        return ruleMethod.trim().equalsIgnoreCase(requestMethod);
    }

    /**
     * Path facet of the four types. Returns null when the path matches (template
     * captures land in pathVars), otherwise the plain-word miss description.
     * Trailing slashes normalise away so /orders and /orders/ are one endpoint.
     */
    private String pathViolation(String matchType, String pattern, String path,
                                 Map<String, String> pathVars) {
        String type = matchType == null || matchType.isBlank() ? "exact" : matchType;
        String want = stripTrailingSlash(pattern == null ? "" : pattern.trim());
        String got = stripTrailingSlash(path == null ? "/" : path);
        return switch (type) {
            case "exact" -> want.equals(got) ? null
                    : "期望路径全等 " + want + "，实际 " + got;
            case "prefix" -> isSegmentPrefix(want, got) ? null
                    : "期望路径前缀 " + want + "（按段边界），实际 " + got;
            case "regex" -> {
                try {
                    yield Pattern.compile(want).matcher(got).find() ? null
                            : "期望路径命中正则 " + want + "，实际 " + got;
                } catch (PatternSyntaxException ex) {
                    yield "正则路径模式无法编译：" + ex.getDescription();
                }
            }
            case "template" -> templateViolation(want, got, pathVars);
            default -> "未知的路径匹配类型「" + type + "」";
        };
    }

    private String stripTrailingSlash(String value) {
        return value.length() > 1 && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** /api prefixes /api and /api/v1 only — never /apix (segment boundary). */
    private boolean isSegmentPrefix(String prefix, String path) {
        return path.equals(prefix) || path.startsWith(prefix + "/") || prefix.equals("/");
    }

    /** Template segments: literal equality or single-segment {var} capture. */
    private String templateViolation(String pattern, String path, Map<String, String> pathVars) {
        String[] wantParts = splitSegments(pattern);
        String[] gotParts = splitSegments(path);
        if (wantParts.length != gotParts.length) {
            return "期望模板路径 " + pattern + "（" + wantParts.length + " 段），实际 "
                    + path + "（" + gotParts.length + " 段）";
        }
        Map<String, String> captured = new LinkedHashMap<>();
        for (int i = 0; i < wantParts.length; i++) {
            String seg = wantParts[i];
            if (seg.startsWith("{") && seg.endsWith("}") && seg.length() > 2) {
                String var = seg.substring(1, seg.length() - 1);
                if (gotParts[i].isEmpty()) {
                    return "模板变量 {" + var + "} 对应段为空";
                }
                captured.put(var, gotParts[i]);
            } else if (!seg.equals(gotParts[i])) {
                return "期望模板路径 " + pattern + "，实际 " + path + "（第 " + (i + 1) + " 段不符）";
            }
        }
        pathVars.putAll(captured);
        return null;
    }

    private String[] splitSegments(String path) {
        String body = path.startsWith("/") ? path.substring(1) : path;
        return body.isEmpty() ? new String[0] : body.split("/", -1);
    }

    // ---- matcher rows ----

    /** Trace step label for a row (inspector column). */
    private String stepOf(JsonNode row) {
        String kind = row.path("kind").asText("?");
        String key = row.path("key").asText(row.path("path").asText(row.path("bodyType").asText("")));
        if (key.isBlank()) {
            return kind;
        }
        return kind + ":" + key;
    }

    /**
     * One matcher row against one request. Null = pass; non-null = plain-word
     * expectation-vs-actual used both by matching and (via row-shape checks)
     * the save chain.
     */
    private String rowViolation(String stubName, JsonNode row, MockMatchRequest request) {
        String kind = row.path("kind").asText("");
        switch (kind) {
            case "query" -> {
                String key = row.path("key").asText("");
                List<String> values = request.queryParams() == null ? List.of()
                        : request.queryParams().getOrDefault(key, List.of());
                if ("exists".equals(row.path("matchType").asText("exact"))) {
                    return values.isEmpty() ? "缺少键「" + key + "」" : null;
                }
                String lastMiss = "缺少「" + key + "」，期望 " + row.path("value").asText("");
                for (String value : values) {
                    // repeated keys: any single value passing satisfies the row
                    String miss = compareRow(key, value, row);
                    if (miss == null) {
                        return null;
                    }
                    lastMiss = miss;
                }
                return lastMiss;
            }
            case "header" -> {
                return compareRow(row.path("key").asText(null),
                        request.header(row.path("key").asText("")), row);
            }
            case "cookie" -> {
                String value = request.cookies() == null ? null
                        : request.cookies().get(row.path("key").asText(""));
                return compareRow(row.path("key").asText(null), value, row);
            }
            case "body" -> {
                return bodyRowViolation(row, request);
            }
            default -> {
                return "未知的匹配器种类「" + kind + "」（仅支持 query/header/cookie/body）";
            }
        }
    }

    /**
     * Shared compare semantics for single-value rows (query per-value, header,
     * cookie, form field): exact / contains / regex / exists.
     */
    private String compareRow(String key, String actual, JsonNode row) {
        String matchType = row.path("matchType").asText("exact");
        String expected = row.path("value").asText(null);
        if (!COMPARE_TYPES.contains(matchType)) {
            return "比较方式「" + matchType + "」不在允许集合 " + COMPARE_TYPES + " 内";
        }
        if ("exists".equals(matchType)) {
            return actual != null ? null : "缺少键「" + key + "」";
        }
        if (expected == null) {
            return "matchType=" + matchType + " 必须给定 value";
        }
        if (actual == null) {
            return "缺少「" + key + "」，期望 " + matchType + " " + expected;
        }
        return switch (matchType) {
            case "exact" -> actual.equals(expected) ? null
                    : "期望 " + key + "=" + expected + "，实际 " + actual;
            case "contains" -> actual.contains(expected) ? null
                    : "期望 " + key + " 包含「" + expected + "」，实际 " + actual;
            case "regex" -> regexMiss(key, expected, actual);
            default -> "不支持的比较方式 " + matchType;
        };
    }

    private String regexMiss(String key, String expected, String actual) {
        try {
            // find() semantics: the pattern may hit anywhere in the value
            return Pattern.compile(expected).matcher(actual).find() ? null
                    : "期望 " + key + " 命中正则 " + expected + "，实际 " + actual;
        } catch (PatternSyntaxException ex) {
            return "正则无法编译：" + ex.getDescription();
        }
    }

    /** body family dispatch: five types, JSONPath on the restricted subset only. */
    private String bodyRowViolation(JsonNode row, MockMatchRequest request) {
        String bodyType = row.path("bodyType").asText("");
        if (!BODY_TYPES.contains(bodyType)) {
            return "body 匹配类型「" + bodyType + "」不在允许集合 " + BODY_TYPES + " 内";
        }
        String body = request.body();
        return switch (bodyType) {
            case "exact", "contains", "regex" -> plainBodyViolation(bodyType, row, body);
            case "jsonpath" -> jsonPathRowViolation(row, body);
            case "form" -> formRowViolation(row, request);
            default -> "未知的 body 匹配类型";
        };
    }

    /** exact / contains / regex over the raw payload (find semantics for regex). */
    private String plainBodyViolation(String bodyType, JsonNode row, String body) {
        String expected = row.path("value").asText(null);
        if (expected == null) {
            return "body " + bodyType + " 必须给定 value";
        }
        if (body == null) {
            return "请求无正文，无法按 " + bodyType + " 判定";
        }
        return switch (bodyType) {
            case "exact" -> body.equals(expected) ? null : "期望正文全等，实际不符";
            case "contains" -> body.contains(expected) ? null
                    : "期望正文包含「" + expected + "」，实际不符";
            default -> regexMiss("body", expected, body);
        };
    }

    /**
     * JSONPath row on the single restricted subset: validate path through
     * NonUiActionContract (one dialect only), then navigate $.a.b[0] segments.
     */
    private String jsonPathRowViolation(JsonNode row, String body) {
        String path = row.path("path").asText(null);
        String assertMode = row.path("assertMode").asText("equals");
        if (path == null || path.isBlank()) {
            return "jsonpath 匹配必须给定 path（受限子集 $.a.b[0].c）";
        }
        String subsetViolation = NonUiActionContract.jsonPathViolation(path);
        if (subsetViolation != null) {
            return subsetViolation;
        }
        if (!ASSERT_MODES.contains(assertMode)) {
            return "assertMode「" + assertMode + "」不在允许集合 " + ASSERT_MODES + " 内";
        }
        JsonNode doc;
        try {
            doc = objectMapper.readTree(body == null || body.isBlank() ? "null" : body);
        } catch (Exception ex) {
            return "正文不是合法 JSON，jsonpath 无法取值";
        }
        JsonNode found = navigate(doc, path);
        switch (assertMode) {
            case "exists":
                return found != null && !found.isNull() ? null
                        : "期望 " + path + " 存在，实际不存在";
            case "type": {
                String want = row.path("value").asText("");
                if (!JSON_TYPES.contains(want)) {
                    return "assertMode=type 的 value 需在 " + JSON_TYPES + " 内，当前「" + want + "」";
                }
                String actual = found == null ? "missing" : found.getNodeType().toString().toLowerCase();
                return want.equals(actual) ? null
                        : "期望 " + path + " 类型 " + want + "，实际 " + actual;
            }
            default: {
                String expected = row.path("value").asText(null);
                if (expected == null) {
                    return "assertMode=equals 必须给定 value";
                }
                if (found == null || found.isNull()) {
                    return "期望 " + path + "=" + expected + "，实际不存在";
                }
                return expected.equals(found.isValueNode() ? found.asText() : found.toString()) ? null
                        : "期望 " + path + "=" + expected + "，实际 "
                                + (found.isValueNode() ? found.asText() : found.toString());
            }
        }
    }

    /** Walk the restricted-subset path (dot names + numeric indexes only). */
    private JsonNode navigate(JsonNode root, String path) {
        JsonNode current = root;
        for (String token : path.substring(1).split("\\.")) {
            if (token.isEmpty()) {
                continue;
            }
            int bracket = token.indexOf('[');
            String name = bracket < 0 ? token : token.substring(0, bracket);
            if (!name.isEmpty()) {
                current = current.get(name);
                if (current == null) {
                    return null;
                }
            }
            while (bracket >= 0) {
                int close = token.indexOf(']', bracket);
                if (close < 0) {
                    return null;
                }
                String indexText = token.substring(bracket + 1, close);
                try {
                    current = current.get(Integer.parseInt(indexText));
                } catch (NumberFormatException ex) {
                    return null;
                }
                if (current == null) {
                    return null;
                }
                bracket = token.indexOf('[', close);
            }
        }
        return current;
    }

    /** form row: decode application/x-www-form-urlencoded and compare one field. */
    private String formRowViolation(JsonNode row, MockMatchRequest request) {
        if (request.contentType() == null
                || !request.contentType().toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            return "form 匹配要求 Content-Type 为 x-www-form-urlencoded";
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String body = request.body() == null ? "" : request.body();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            fields.put(urlDecode(name), urlDecode(value));
        }
        String key = row.path("key").asText(null);
        if (key == null || key.isBlank()) {
            return "form 匹配必须给定字段名 key";
        }
        return compareRow(key, fields.get(key), row);
    }

    private String urlDecode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw; // leave undecodable segments as-is; comparison will miss
        }
    }

    // ---- save chain legality (same key set — single point, no second validator) ----

    /**
     * Legality of a whole matchers_json payload as stored by the save chain:
     * parseable array, frozen key set per row, compilable regex, JSONPath rows
     * on the restricted subset. Null = legal; non-null = plain-word violation.
     */
    public String matcherRowViolation(String matchersJson) {
        if (matchersJson == null || matchersJson.isBlank()) {
            return null;
        }
        JsonNode rows;
        try {
            rows = objectMapper.readTree(matchersJson);
        } catch (Exception ex) {
            return "matchers 集合不是合法 JSON（每条匹配器一行）";
        }
        if (!rows.isArray()) {
            return "matchers 必须是 JSON 数组（每条匹配器一行）";
        }
        for (JsonNode row : rows) {
            String violation = singleRowLegality(row);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    /** Row-shape legality independent of any live request. */
    private String singleRowLegality(JsonNode row) {
        String kind = row.path("kind").asText("");
        if (!KINDS.contains(kind)) {
            return "匹配器种类「" + kind + "」不在允许集合 " + KINDS + " 内";
        }
        if (!"body".equals(kind) && row.path("key").asText("").isBlank()) {
            return kind + " 匹配必须给定 key";
        }
        if ("body".equals(kind)) {
            String bodyType = row.path("bodyType").asText("");
            if (!BODY_TYPES.contains(bodyType)) {
                return "body 匹配类型「" + bodyType + "」不在允许集合 " + BODY_TYPES + " 内";
            }
            return switch (bodyType) {
                case "jsonpath" -> {
                    String pathViolation = NonUiActionContract.jsonPathViolation(
                            row.path("path").asText(null));
                    if (pathViolation != null) {
                        yield pathViolation;
                    }
                    String assertMode = row.path("assertMode").asText("equals");
                    yield ASSERT_MODES.contains(assertMode) ? null
                            : "assertMode「" + assertMode + "」不在允许集合 " + ASSERT_MODES + " 内";
                }
                case "form" -> row.path("key").asText("").isBlank()
                        ? "form 匹配必须给定字段名 key" : compareLegality(row);
                default -> row.path("value").asText("").isEmpty()
                        && !row.hasNonNull("value")
                        ? "body " + bodyType + " 必须给定 value" : compareLegality(row);
            };
        }
        return compareLegality(row);
    }

    /** compareType legability + regex compilability for rows carrying one. */
    private String compareLegality(JsonNode row) {
        String matchType = row.path("matchType").asText("exact");
        if (!COMPARE_TYPES.contains(matchType)) {
            return "比较方式「" + matchType + "」不在允许集合 " + COMPARE_TYPES + " 内";
        }
        if (!"exists".equals(matchType) && row.path("value").asText("").isBlank()) {
            return "matchType=" + matchType + " 必须给定 value";
        }
        if ("regex".equals(matchType)) {
            try {
                Pattern.compile(row.path("value").asText(""));
            } catch (PatternSyntaxException ex) {
                return "正则无法编译：" + ex.getDescription();
            }
        }
        return null;
    }
}
