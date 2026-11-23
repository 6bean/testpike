package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.MockRequestContext;
import com.sixbean.testpike.common.genkit.MockTemplateResolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumption spot of the template kernel on the traffic plane (design F3/F5):
 * turns one stub's response_json payload plus the served request into the
 * final response. The payload is the form-builder document — status, header
 * rows, body mode (static text / template / schema) and body text.
 * <p>Static bodies pass through raw (placeholders included — that is the point
 * of static mode); template bodies and every header value render through the
 * single common {@link MockTemplateResolver} kernel, so the mock-server never
 * grows its own template dialect (single-parser red line). Schema-synthesised
 * bodies land with the contract-direct task and fail closed until then —
 * a half-supported mode must refuse, not serve pretending.
 * <p>Fail-closed discipline: unparsable payloads and unresolvable references
 * throw with a human-readable reason naming the offending token; the caller
 * (traffic controller) decides how to report it, this class never invents a
 * fallback body.
 */
@Component
public class MockResponseRenderer {

    /** Response builder document is JSON object text — strict, tolerant of unknown future keys. */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    /** Body modes as written by the form builder (design F3 tri-state). */
    static final String MODE_STATIC = "static";
    static final String MODE_TEMPLATE = "template";
    static final String MODE_SCHEMA = "schema";

    /**
     * Render one stub response.
     *
     * @param responseJson the stub's response builder payload (JSON object text)
     * @param request      snapshot of the served request for echo references
     * @param seedEvents   shared {fn, seed} sink — name + seed only, never values
     */
    public MockRenderedResponse render(String responseJson, MockRequestContext request,
                                       List<Map<String, Object>> seedEvents) {
        JsonNode root = parsePayload(responseJson);
        int status = root.path("status").asInt(200);
        // one resolver instance per response: cycle cursors and seed replay stay coherent
        MockTemplateResolver kernel = new MockTemplateResolver(request, seedEvents);
        Map<String, String> headers = renderHeaders(root.path("headers"), kernel);
        String bodyMode = root.path("bodyMode").asText(MODE_STATIC);
        String body = switch (bodyMode) {
            case MODE_STATIC -> root.path("body").asText("");
            case MODE_TEMPLATE -> kernel.render(root.path("body").asText(""));
            case MODE_SCHEMA -> throw new ServiceException(
                    "响应构造 bodyMode=schema：schema 合成档随契约直出批次上线，"
                            + "当前仅支持 static / template 两态",
                    BizErrorCode.VALIDATION_ERROR);
            default -> throw new ServiceException(
                    "响应构造 bodyMode「" + bodyMode + "」不是支持的模式，"
                            + "当前可用：static / template",
                    BizErrorCode.VALIDATION_ERROR);
        };
        return new MockRenderedResponse(status, headers, body);
    }

    /** Header rows of the builder document, each value rendered the same way. */
    private Map<String, String> renderHeaders(JsonNode headersNode, MockTemplateResolver kernel) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headersNode == null || !headersNode.isObject()) {
            return out;
        }
        headersNode.fields().forEachRemaining(entry ->
                out.put(entry.getKey(), kernel.render(entry.getValue().asText(""))));
        return out;
    }

    private JsonNode parsePayload(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new ServiceException("stub 响应构造为空，无法出响应（请在表单构建器补齐）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            JsonNode root = PAYLOAD_MAPPER.readTree(responseJson);
            if (!root.isObject()) {
                throw new ServiceException("stub 响应构造必须是 JSON 对象"
                        + "（status/headers/bodyMode/body 键），当前不是对象形态",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return root;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ServiceException("stub 响应构造不是合法 JSON：" + ex.getOriginalMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
