package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.ApiWsContract;
import com.sixbean.testpike.common.util.ExpectMatchContract;
import com.sixbean.testpike.common.util.NonUiActionContract;

import java.util.List;
import java.util.Set;


public final class ApiAssertionFormContract {

    /** Assertion type vocabulary (five plus the schema type added for JSON Schema checks; free-form script assertions stay refused). */
    public static final Set<String> ASSERTION_TYPES =
            Set.of("status", "duration", "body", "header", "extract", "schema");

    /** Schema-source dialects: inline = pasted schema text/object, contract = reference the endpoint's responseSchemas from the parsed spec. */
    public static final Set<String> SCHEMA_VIAS = Set.of("inline", "contract");

    /** Extraction lanes for the extract row (AV correction, single owner row = schedule line ): body keeps the JsonPath lane, header/cookie read by name. */
    public static final Set<String> EXTRACT_SOURCES = Set.of("body", "header", "cookie");

    private static final ObjectMapper SCHEMA_MAPPER = new ObjectMapper();

    private ApiAssertionFormContract() {
    }

    /** HTTP-face row check (default transport; promotion/spec-draft callers keep this entry). */
    public static String rowViolation(JsonNode row) {
        return rowViolation(row, ApiWsContract.DEFAULT_PROTOCOL);
    }

    /**
 * Protocol-crossed row check : the transport discriminator picks
 * the grammar — http face keeps the six-type vocabulary, ws face accepts only
 * the three ws.* types (vocabulary single source ApiWsContract, no second copy).
 * Cross-face rows are refused naming both the type and the way back, so a
 * status row saved on a ws request (or ws.receive on http) never reaches the
 * executor to fail there. An unknown transport fails closed naming the value.
 */
    public static String rowViolation(JsonNode row, String rawProtocol) {
        String protocol = ApiWsContract.normalize(rawProtocol);
        if (protocol == null) {
            return "传输协议不合法：仅支持 http / ws，当前值「" + rawProtocol + "」";
        }
        if (row == null || !row.isObject()) {
            return "断言每行必须是对象（type/op/expect 表单行）";
        }
        String type = row.path("type").asText("");
        if ("ws".equals(protocol)) {
            return wsRowViolation(row, type);
        }
        if (ApiWsContract.WS_ASSERTION_TYPES.contains(type)) {
            return "断言类型「" + type + "」不适用于 HTTP 传输面——请在请求上切到 ws 协议"
                    + "（ws.receive / ws.order / ws.frequency），或改用 HTTP 型断言";
        }
        if (!ASSERTION_TYPES.contains(type)) {
            return "断言类型仅支持 "
                    + String.join(" / ", ASSERTION_TYPES.stream().sorted().toList())
                    + "（禁自由表达式与脚本断言），当前值「" + type + "」";
        }
        return switch (type) {
            case "status" -> statusViolation(row);
            case "duration" -> durationViolation(row);
            case "body" -> bodyViolation(row);
            case "header" -> headerViolation(row);
            case "schema" -> schemaViolation(row);
            default -> extractViolation(row);
        };
    }

    /**
     * WebSocket-face row check: only ws.receive / ws.order / ws.frequency parse,
     * each with its own expect shape (content match / arrival sequence / minimum
     * count) plus the optional withinMs window. The executor judges the live
     * stream; this gate only refuses shapes that could never be judged.
     */
    private static String wsRowViolation(JsonNode row, String type) {
        if (!ApiWsContract.WS_ASSERTION_TYPES.contains(type)) {
            if (ASSERTION_TYPES.contains(type)) {
                return "断言类型「" + type + "」不适用于 WebSocket 传输面（当前仅 "
                        + String.join(" / ", ApiWsContract.WS_ASSERTION_TYPES.stream().sorted().toList())
                        + "），HTTP 型断言请切回 http 协议";
            }
            return "断言类型仅支持 "
                    + String.join(" / ", ApiWsContract.WS_ASSERTION_TYPES.stream().sorted().toList())
                    + "（禁自由表达式与脚本断言），当前值「" + type + "」";
        }
        String windowViolation = wsWindowViolation(row);
        if (windowViolation != null) {
            return windowViolation;
        }
        return switch (type) {
            case "ws.receive" -> wsReceiveViolation(row);
            case "ws.order" -> wsOrderViolation(row);
            default -> wsFrequencyViolation(row);
        };
    }

    /** Optional window budget: positive integer milliseconds (absent = executor default 3000). */
    private static String wsWindowViolation(JsonNode row) {
        JsonNode window = row.get("withinMs");
        if (window == null || window.isNull()) {
            return null;
        }
        int value;
        if (window.isIntegralNumber()) {
            value = window.asInt();
        } else if (window.isTextual() && window.asText().trim().matches("\\d+")) {
            try {
                value = Integer.parseInt(window.asText().trim());
            } catch (NumberFormatException e) {
                return "消息等待窗口 withinMs 超过该步骤的超时上限（timeoutMs），请把等待毫秒数改小到上限以内";
            }
        } else {
            return "ws 断言窗口 withinMs 必须是 ≥1 的整数毫秒（缺省=3000），当前值「"
                    + window.asText() + "」";
        }
        if (value < 1) {
            return "ws 断言窗口 withinMs 必须是 ≥1 的整数毫秒（缺省=3000），当前值「" + value + "」";
        }
        return null;
    }

    private static String wsReceiveViolation(JsonNode row) {
        JsonNode expect = row.get("expect");
        if (expect == null || expect.isNull()) {
            return "消息接收断言还差期望内容：请在 expect 填写窗口内要命中的消息文本（例 expect=pong）";
        }
        String op = row.path("op").asText("");
        if (op.isEmpty()) {
            return null;
        }
        String enumViolation = ExpectMatchContract.matchTypeViolation(op);
        if (enumViolation != null) {
            return "ws.receive 断言" + enumViolation;
        }
        if ("range".equals(op)) {
            return expectPairViolation(row, "区间");
        }
        if ("regex".equals(op)) {
            if (!expect.isTextual() || expect.asText().isBlank()) {
                return "正则断言还差匹配规则：请在 expect 填写正则表达式（例 ^ok$）";
            }
            ObjectNode shaped = row.deepCopy();
            shaped.put("pattern", expect.asText());
            return ExpectMatchContract.regexPatternViolation(shaped);
        }
        return null;
    }

    private static String wsOrderViolation(JsonNode row) {
        JsonNode expect = row.get("expect");
        if (expect == null || !expect.isArray() || expect.isEmpty()) {
            return "ws.order 的 expect 必须是非空字符串数组（期望到达序列），"
                    + "例 [\"READY\",\"DONE\"]";
        }
        for (JsonNode item : expect) {
            if (!item.isTextual() && !item.isNumber()) {
                return "ws.order 的 expect 每一项必须是字符串或数值（消息帧为文本），当前值「"
                        + item.asText() + "」";
            }
        }
        return null;
    }

    private static String wsFrequencyViolation(JsonNode row) {
        JsonNode expect = row.get("expect");
        if (expect == null || expect.isNull()) {
            return "ws.frequency 的 expect 必须是正整数（窗口内最少消息条数），例 expect=5";
        }
        if (expect.isIntegralNumber()) {
            if (expect.asInt() < 1) {
                return "ws.frequency 的 expect 必须是正整数（窗口内最少消息条数），当前值「"
                        + expect.asInt() + "」";
            }
            return null;
        }
        if (expect.isTextual() && expect.asText().trim().matches("\\d+")) {
            try {
                if (Integer.parseInt(expect.asText().trim()) < 1) {
                    return "ws.frequency 的 expect 必须是正整数（窗口内最少消息条数），当前值「"
                            + expect.asText().trim() + "」";
                }
            } catch (NumberFormatException e) {
                return "ws.frequency 的 expect 必须是正整数（窗口内最少消息条数），当前值超长「"
                        + truncate(expect.asText()) + "」";
            }
            return null;
        }
        return "ws.frequency 的 expect 必须是正整数（窗口内最少消息条数），当前值「"
                + expect.asText() + "」";
    }

    
    public static List<String> typeOptions() {
        return ASSERTION_TYPES.stream().sorted().toList();
    }

    private static String statusViolation(JsonNode row) {
        JsonNode expect = row.get("expect");
        if (expect == null || !expect.isIntegralNumber() || expect.asInt() < 100
                || expect.asInt() > 599) {
            return "状态码断言 expect 必须是 100–599 的整数，例 200";
        }
        return null;
    }

    private static String durationViolation(JsonNode row) {
        String opViolation = ExpectMatchContract.matchTypeViolation(row.path("op").asText(""));
        if (opViolation != null) {
            return "耗时断言" + opViolation + "（例：op=range, expect=[0,3000]）";
        }
        return expectPairViolation(row, "耗时");
    }

    private static String bodyViolation(JsonNode row) {
        String pathViolation = NonUiActionContract.jsonPathViolation(
                row.path("path").asText(null));
        if (pathViolation != null) {
            return pathViolation;
        }
        return matchRowViolation(row);
    }

    private static String headerViolation(JsonNode row) {
        if (row.path("name").asText("").isBlank()) {
            return "响应头断言还差头名：请在 name 填写要校验的头名称（例 name=Content-Type）";
        }
        return matchRowViolation(row);
    }

    private static String extractViolation(JsonNode row) {
        String source = row.path("source").isNull() || row.path("source").isMissingNode()
                ? "body"
                : row.path("source").asText("").trim().toLowerCase();
        if (!EXTRACT_SOURCES.contains(source)) {
            return "变量提取来源 source 仅支持 body / header / cookie（缺省=body，按 JsonPath 提取），"
                    + "当前值「" + row.path("source").asText("") + "」";
        }
        if ("body".equals(source)) {
            String pathViolation = NonUiActionContract.jsonPathViolation(
                    row.path("path").asText(null));
            if (pathViolation != null) {
                return pathViolation;
            }
        } else if (row.path("name").asText("").isBlank()) {
            return "按 " + source + " 提取变量还差名称：请在 name 填写要提取的头或 Cookie 名称（例 name=X-Auth-Token）";
        }
        if (row.get("saveAs") == null || row.get("saveAs").isNull()) {
            return "变量提取断言还差保存变量名：请在 saveAs 填写变量名"
                    + "（例 saveAs=TOKEN，后续请求用 {{VAR:TOKEN}} 引用）";
        }
        return NonUiActionContract.saveAsViolation("变量提取断言", row);
    }

    /**
 * Schema row shape check : the source dialect plus, for the
 * inline dialect, that the pasted schema is an object or a parseable JSON
 * object literal. Deep validity (is it a legal JSON Schema document) is
 * decided fail-closed at execution time by the executor's jsonschema kernel,
 * which names the offending schema instead of silently passing.
 */
    private static String schemaViolation(JsonNode row) {
        String via = row.path("via").asText("");
        if (via.isBlank()) {
            return "结构校验断言必须选择来源：inline（粘贴 JSON Schema）或 "
                    + "contract（引用接口已保存的响应结构样本 responseSchemas）";
        }
        if (!SCHEMA_VIAS.contains(via)) {
            return "结构校验来源仅支持 inline / contract，当前值「" + via + "」";
        }
        if ("inline".equals(via)) {
            return inlineSchemaViolation(row.get("schema"));
        }
        JsonNode code = row.get("code");
        if (code == null || code.isNull()) {
            // No fixed code: the executor resolves the schema against the actual
            // response status, mirroring the contract detail view.
            return null;
        }
        int status = parseStatusCode(code);
        if (status < 100 || status > 599) {
            return "结构校验引用的响应码 code 必须是 100–599 的整数（缺省=按实际响应码取对应结构样本），"
                    + "当前值「" + code.asText() + "」";
        }
        return null;
    }

    private static String inlineSchemaViolation(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return "inline 必须粘贴 JSON Schema（对象或 JSON 文本），例 "
                    + "{\"type\":\"object\",\"required\":[\"code\"]}";
        }
        if (schema.isObject()) {
            return null;
        }
        if (!schema.isTextual()) {
            return "JSON Schema 必须是对象或 JSON 文本，当前形态无法判定";
        }
        JsonNode parsed;
        try {
            parsed = SCHEMA_MAPPER.readTree(schema.asText());
        } catch (Exception e) {
            return "JSON Schema 不可解析（粘贴内容不是合法 JSON）："
                    + truncate(schema.asText());
        }
        if (!parsed.isObject()) {
            return "JSON Schema 必须是对象或 JSON 文本，当前形态无法判定";
        }
        return null;
    }

    /** Contract code slot accepts an integral number or a digits-only string. */
    private static int parseStatusCode(JsonNode code) {
        if (code.isIntegralNumber()) {
            return code.asInt();
        }
        if (code.isTextual()) {
            try {
                return Integer.parseInt(code.asText().trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static String truncate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    
    private static String matchRowViolation(JsonNode row) {
        String op = row.path("op").asText("");
        String enumViolation = ExpectMatchContract.matchTypeViolation(op);
        if (enumViolation != null) {
            return enumViolation;
        }
        if ("range".equals(op)) {
            return expectPairViolation(row, "区间");
        }
        if ("regex".equals(op)) {
            JsonNode pattern = row.get("pattern");
            if (pattern != null && !pattern.isNull()) {
                return ExpectMatchContract.regexPatternViolation(row);
            }
            JsonNode expect = row.get("expect");
            if (expect == null || !expect.isTextual() || expect.asText().isBlank()) {
                return "正则断言还差匹配规则：请在 expect 填写正则表达式（例 ^ok$）";
            }
            ObjectNode shaped = row.deepCopy();
            shaped.put("pattern", expect.asText());
            return ExpectMatchContract.regexPatternViolation(shaped);
        }
        if (row.get("expect") == null || row.get("expect").isNull()) {
            return "断言还差期望值：请在 expect 填写预期返回的内容";
        }
        return null;
    }

    
    private static String expectPairViolation(JsonNode row, String field) {
        JsonNode expect = row.get("expect");
        if (expect == null || !expect.isArray() || expect.isEmpty() || expect.size() > 2) {
            return field + "断言 expect 必须是 [min,max] 数值对（例 [0,3000]）";
        }
        for (JsonNode bound : expect) {
            if (!bound.isNumber()) {
                return field + "断言的区间端点必须是数值，当前值「" + bound.asText() + "」";
            }
        }
        return null;
    }
}
