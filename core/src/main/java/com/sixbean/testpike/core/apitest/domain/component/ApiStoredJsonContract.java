package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

/**
 * Stored-JSON column shape gate (apitest domain, single source).
 * <p>Request-level (tp_apitest_request) and collection-level (tp_apitest_collection
 * headers_json/auth_json, ) save chains share one normalizer: blank/null input
 * lands on the column default, non-JSON fails closed naming the offending text,
 * and the array-vs-object shape is enforced per field. Verdicts stay identical to
 * the pre- request-face wording so existing assertions keep their meaning —
 * extraction moves the logic, it does not change it.
 */
public final class ApiStoredJsonContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiStoredJsonContract() {
        // static gate, no instances
    }

    /**
     * Normalize a stored-JSON column value: null/blank → fallback, parse or fail,
     * shape check (wantArray: []-style row array; otherwise {}-style object).
     */
    public static String normalize(String raw, String fallback, String field, boolean wantArray) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ServiceException(field + "不是合法 JSON，当前值「" + raw + "」——请在编辑区改为合法 JSON（格式：以 { 或 [ 开头的完整结构）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (wantArray && !node.isArray()) {
            throw new ServiceException(field + "必须是数组（形如 []）——请在编辑区改为数组后重新保存，示例：[]", BizErrorCode.VALIDATION_ERROR);
        }
        if (!wantArray && !node.isObject()) {
            throw new ServiceException(field + "必须是对象（形如 {}）——请在编辑区改为对象后重新保存，示例：{}", BizErrorCode.VALIDATION_ERROR);
        }
        return node.toString();
    }

    /** Re-parse an already-normalized document for the contract gates (shape guaranteed valid). */
    public static JsonNode read(String json, String field) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new ServiceException(field + "不是合法 JSON——请恢复为合法 JSON 后重新保存（格式：以 { 或 [ 开头的完整结构）", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
