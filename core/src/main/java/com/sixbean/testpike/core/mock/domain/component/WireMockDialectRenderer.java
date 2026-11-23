package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WireMock dialect renderer . Output is a
 * WireMock standalone stub mapping document: {@code {"mappings":[...]}} with
 * one entry per stub, directly droppable into a WireMock {@code __files/mappings}
 * tree.
 * <p>WireMock carries more natively than Mockoon — query/header matchers,
 * scenarios (our three state columns map 1:1 onto scenarioName/
 * requiredScenarioState/newScenarioState), delay and connection-reset faults —
 * so only what truly cannot cross (body matchers in our restricted dialect,
 * template placeholders, probability-based chaos) lands in the honesty notes.
 */
@Component
public class WireMockDialectRenderer {

    /** Dialect id as used in the export format query parameter. */
    public static final String FORMAT = "wiremock";

    public String render(MockSpaceInfo space, List<MockStubInfo> stubs) {
        return renderResult(space, stubs).content();
    }

    public ExportDialectResult renderResult(MockSpaceInfo space, List<MockStubInfo> stubs) {
        List<String> notes = new ArrayList<>();
        ObjectNode root = MockExportSupport.MAPPER.createObjectNode();
        ArrayNode mappings = root.putArray("mappings");
        for (MockStubInfo stub : stubs) {
            mappings.add(mapping(space, stub, notes));
        }
        String content;
        try {
            content = MockExportSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("WireMock 方言导出序列化失败: " + e.getMessage(), e);
        }
        return new ExportDialectResult(FORMAT, content, notes);
    }

    private ObjectNode mapping(MockSpaceInfo space, MockStubInfo stub, List<String> notes) {
        ObjectNode node = MockExportSupport.MAPPER.createObjectNode();
        node.put("name", stub.name());
        node.put("priority", stub.priority() == null ? 5 : stub.priority());
        if (!Boolean.TRUE.equals(stub.enabled())) {
            node.put("persistent", false);
            notes.add("「" + stub.name() + "」处于停用态：WireMock 无停用旗，导出保留映射但不生效，导入后请按需删除");
        }
        node.set("request", request(space, stub, notes));
        node.set("response", response(stub, notes));
        if (stub.scenarioName() != null && !stub.scenarioName().isBlank()) {
            ObjectNode scenario = node;
            scenario.put("scenarioName", stub.scenarioName());
            if (stub.requiredState() != null && !stub.requiredState().isBlank()) {
                scenario.put("requiredScenarioState", stub.requiredState());
            }
            if (stub.newState() != null && !stub.newState().isBlank()) {
                scenario.put("newScenarioState", stub.newState());
            }
        }
        return node;
    }

    private ObjectNode request(MockSpaceInfo space, MockStubInfo stub, List<String> notes) {
        ObjectNode request = MockExportSupport.MAPPER.createObjectNode();
        String method = stub.method() == null ? "GET" : stub.method().toUpperCase(Locale.ROOT);
        // ANY = WireMock matches every verb when the method key is absent.
        if (!"ANY".equals(method)) {
            request.put("method", method);
        }
        String rel = MockExportSupport.relativePath(stub, notes, FORMAT);
        String full = MockExportSupport.routeRoot(space) + rel;
        String type = stub.pathMatchType() == null ? "exact" : stub.pathMatchType();
        switch (type) {
            case "exact" -> request.put("url", full);
            case "prefix" -> request.put("urlPathPattern", java.util.regex.Pattern.quote(full) + ".*");
            case "template" -> request.put("urlPathPattern", full);
            default -> request.put("urlPathPattern", full);
        }
        for (JsonNode row : MockExportSupport.matcherRows(stub)) {
            String kind = row.path("kind").asText("?");
            String key = row.path("key").asText("");
            String matchType = row.path("matchType").asText("exact");
            String value = row.path("value").asText("");
            switch (kind) {
                case "query" -> putMatcher(request.withObject("queryParams"), key, row, matchType, value);
                case "header" -> putMatcher(request.withObject("headers"), key, row, matchType, value);
                default -> notes.add("「" + stub.name() + "」匹配器 " + kind
                        + "：本方言导出未覆盖（body 匹配器与平台 JSONPath 子集语义不 1:1），导入后需人工补");
            }
        }
        return request;
    }

    /** One matcher row → the nearest WireMock matcher block (exact/contains/regex/exists). */
    private void putMatcher(ObjectNode parent, String key, JsonNode row, String matchType, String value) {
        ObjectNode matcher = parent.withObject(key);
        switch (matchType) {
            case "exact" -> matcher.put("equalTo", value);
            case "contains" -> matcher.put("containing", value);
            case "regex" -> matcher.put("matches", value);
            case "exists" -> matcher.put("exists", true);
            default -> matcher.put("equalTo", row.path("value").asText(""));
        }
    }

    private ObjectNode response(MockStubInfo stub, List<String> notes) {
        ObjectNode node = MockExportSupport.MAPPER.createObjectNode();
        JsonNode payload = MockExportSupport.readResponse(stub);
        node.put("status", payload.path("status").asInt(200));
        Map<String, String> headers = MockExportSupport.responseHeaders(payload);
        if (!headers.isEmpty()) {
            node.set("headers", MockExportSupport.headersObject(headers));
        }
        String body = MockExportSupport.bodyText(payload);
        if (MockExportSupport.bodyIsJson(payload, body)) {
            try {
                node.set("jsonBody", MockExportSupport.MAPPER.readTree(body));
            } catch (Exception e) {
                node.put("body", body);
            }
        } else {
            node.put("body", body);
        }
        if (MockExportSupport.isTemplateBody(payload)) {
            notes.add("「" + stub.name() + "」响应正文是平台模板（{{mock.*}}/{{gen.*}}）："
                    + "WireMock 用自身 transformer 体系，占位符按原文导出需手工替换");
        }
        JsonNode fault = payload.path("fault");
        String faultType = fault.path("type").asText("");
        switch (faultType) {
            case "" -> {
            }
            case "delay" -> node.put("fixedDelayMilliseconds", fault.path("minMs").asInt(0));
            case "disconnect" -> node.put("fault", "CONNECTION_RESET_BY_PEER");
            default -> notes.add("「" + stub.name() + "」故障注入 " + faultType
                    + "：WireMock 无 1:1 形态（概率型/畸形/慢速 dribble 参数域不同），导入后需改写");
        }
        return node;
    }
}
