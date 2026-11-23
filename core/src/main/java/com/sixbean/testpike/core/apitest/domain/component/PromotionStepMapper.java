package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.ApiWsContract;

/**
 * WS2 assertion form rows to step expect mapping, single source .
 * <p>The sandbox request stores its assertions as form rows (type/op/expect,
 * {@link ApiAssertionFormContract}); a promoted regression case carries them as one
 * http.request step expect object (04 contract 2.1.2). This class is the only place
 * allowed to translate between the two carriers — the promotion service must not
 * hand-build expect JSON elsewhere, and the step-side save gate
 * ({@code NonUiActionContract.expectObjectViolation}) accepts mapped output verbatim
 * (double judgement parity, pinned by PromotionStepMapperTest).
 * <p>No silent row loss (red line 9): every one of the five form types has a landing
 * slot; conflicts that a copy would silently drop (two status rows, several extract
 * rows competing for the single step-level saveAs slot) are refused with plain
 * language. Per-row legality delegates to the form contract — no second grammar.
 * <p>Pure static, no state, no Spring; exception wrapping belongs to the caller.
 */
public final class PromotionStepMapper {

    /** Reused for reshaping; the mapper never mutates input rows (pure output). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromotionStepMapper() {
        // static mapping contract, no instances
    }

    /**
     * Pre-flight legality of a whole assertion group (null = mappable).
     * Checks array shape, then every row through the form contract (single source),
     * then the two slot conflicts that would silently lose an assertion.
     */
    public static String mappingViolation(JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            return "断言组必须是表单行数组（[{type,op,expect,...}]）";
        }
        int statusRows = 0;
        int extractRows = 0;
        for (JsonNode row : rows) {
            // ws.* rows never promote: script steps have no live-socket vocabulary.
            // Name them before the http-face grammar so the message says why
            // (the form contract alone would call them "unknown types").
            String type = row.path("type").asText("");
            if (ApiWsContract.WS_ASSERTION_TYPES.contains(type)) {
                return "WebSocket 断言（" + type
                        + "）暂不能升格为用例步骤——脚本步骤词表无 ws 槽位，"
                        + "请保留在接口请求上运行（升格其余断言行即可）";
            }
            String violation = ApiAssertionFormContract.rowViolation(row);
            if (violation != null) {
                return violation;
            }
            switch (row.path("type").asText("")) {
                case "status" -> statusRows++;
                case "extract" -> extractRows++;
                case "schema" -> {
                    // The step expect vocabulary (status/body/durationMs/headers)
                    // has no schema slot yet — refuse promotion instead of
                    // silently dropping the assertion.
                    return "结构校验断言（schema）暂不能升格为用例步骤——脚本步骤断言词表"
                            + "尚无结构校验槽位，请保留在接口请求上运行（升格其余断言行即可）";
                }
                default -> {
                }
            }
        }
        if (statusRows > 1) {
            return "断言组含多条状态码断言——一步 expect 只有一个状态码槽位，"
                    + "保留其一即可（其余会静默丢失，已拒）";
        }
        if (extractRows > 1) {
            return "断言组含多条变量提取断言——一个步骤只有一个 saveAs 捕获槽，"
                    + "请合并为单条提取（多条会静默丢失，已拒）";
        }
        return null;
    }

    /**
     * Map a validated assertion group into the step expect object (+ saveAs).
     * Callers gate first with {@link #mappingViolation}; illegal input here throws
     * {@link IllegalArgumentException} carrying the same plain-language message
     * (defensive — the promotion service always checks before mapping).
     */
    public static PromotionStepMapping toStepExpect(JsonNode rows) {
        String violation = mappingViolation(rows);
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        ObjectNode expect = MAPPER.createObjectNode();
        ArrayNode bodyItems = null;
        ArrayNode headerRows = null;
        String saveAs = null;
        for (JsonNode row : rows) {
            switch (row.path("type").asText("")) {
                case "status" -> expect.set("status", row.get("expect"));
                case "duration" -> expect.set("durationMs", durationCarrier(row));
                case "body" -> {
                    if (bodyItems == null) {
                        bodyItems = expect.putArray("body");
                    }
                    bodyItems.add(bodyCarrier(row));
                }
                case "header" -> {
                    if (headerRows == null) {
                        headerRows = expect.putArray("headers");
                    }
                    headerRows.add(headerCarrier(row));
                }
                default -> saveAs = row.path("saveAs").asText();
            }
        }
        return new PromotionStepMapping(expect, saveAs);
    }

    /** Form duration row -> expect.durationMs (op/expect/bounds pass straight through). */
    private static ObjectNode durationCarrier(JsonNode row) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("op", row.get("op"));
        node.set("expect", row.get("expect"));
        copyIfPresent(row, node, "bounds");
        return node;
    }

    /**
     * Form body row -> expect.body item in the B10 expect-match shape:
     * contains/exact carry text, regex carries pattern, range carries min/max
     * (+ bounds) — the step-side gate and the executor judge both read that shape,
     * so the form pair {@code expect:[lo,hi]} is split into endpoints here.
     */
    private static ObjectNode bodyCarrier(JsonNode row) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("path", row.get("path"));
        String op = row.path("op").asText("");
        JsonNode expect = row.get("expect");
        switch (op) {
            case "regex" -> {
                node.put("matchType", "regex");
                node.set("pattern", expect);
            }
            case "range" -> {
                node.put("matchType", "range");
                node.set("min", expect.get(0));
                node.set("max", expect.get(1));
                copyIfPresent(row, node, "bounds");
            }
            default -> {
                node.put("matchType", op.isEmpty() ? "contains" : op);
                node.put("text", expect.asText());
            }
        }
        return node;
    }

    /** Form header row -> expect.headers row (same carrier shape, name/op/expect). */
    private static ObjectNode headerCarrier(JsonNode row) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("name", row.get("name"));
        node.set("op", row.get("op"));
        node.set("expect", row.get("expect"));
        copyIfPresent(row, node, "bounds");
        copyIfPresent(row, node, "pattern");
        return node;
    }

    private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && !value.isNull()) {
            to.set(field, value);
        }
    }
}
