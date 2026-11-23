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
import java.util.UUID;

/**
 * Mockoon dialect renderer , M-e export phase 1 — "export first"
 * decision). Output follows the Mockoon Hub v1 document shape: a root object
 * carrying {@code name}/{@code endpoints}, each endpoint with a lowercase
 * method, the callable path under the route, and a {@code response} list.
 * <p>Rules Mockoon cannot express faithfully (cookie/body matchers, template
 * placeholders, scenario states, faults other than a plain delay) are
 * exported with their body kept and named in {@code notes} — an importer must
 * never believe a migrated mock is identical when it is not.
 */
@Component
public class MockoonDialectRenderer {

    /** Dialect id as used in the export format query parameter. */
    public static final String FORMAT = "mockoon";

    /**
     * Render and return the file body only (the plain download door that
     * does not need the loss list).
     */
    public String render(MockSpaceInfo space, List<MockStubInfo> stubs) {
        return renderResult(space, stubs).content();
    }

    public ExportDialectResult renderResult(MockSpaceInfo space, List<MockStubInfo> stubs) {
        List<String> notes = new ArrayList<>();
        ObjectNode root = MockExportSupport.MAPPER.createObjectNode();
        root.put("uuid", UUID.randomUUID().toString());
        root.put("name", space.name());
        ArrayNode endpoints = root.putArray("endpoints");
        for (MockStubInfo stub : stubs) {
            endpoints.add(endpoint(space, stub, notes));
        }
        String content;
        try {
            content = MockExportSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Mockoon 方言导出序列化失败: " + e.getMessage(), e);
        }
        return new ExportDialectResult(FORMAT, content, notes);
    }

    private ObjectNode endpoint(MockSpaceInfo space, MockStubInfo stub, List<String> notes) {
        ObjectNode node = MockExportSupport.MAPPER.createObjectNode();
        node.put("uuid", UUID.randomUUID().toString());
        node.put("name", stub.name());
        node.put("documentation", stub.source() == null ? "" : stub.source());
        node.put("method", mockoonMethod(stub));
        node.put("path", MockExportSupport.routeRoot(space) + MockExportSupport.relativePath(stub, notes, FORMAT));
        node.put("isDisabled", !Boolean.TRUE.equals(stub.enabled()));
        ArrayNode responses = node.putArray("response");
        responses.add(response(stub, notes));
        if (stub.scenarioName() != null && !stub.scenarioName().isBlank()) {
            notes.add("「" + stub.name() + "」状态机场景「" + stub.scenarioName()
                    + "」：Mockoon 无原生 scenario，导入后需拆成多条路由手工接链");
        }
        return node;
    }

    /** ANY has no verb slot in Mockoon — its wildcard literal is "all". */
    private String mockoonMethod(MockStubInfo stub) {
        String method = stub.method() == null ? "GET" : stub.method().toUpperCase(Locale.ROOT);
        return "ANY".equals(method) ? "all" : method.toLowerCase(Locale.ROOT);
    }

    private ObjectNode response(MockStubInfo stub, List<String> notes) {
        ObjectNode node = MockExportSupport.MAPPER.createObjectNode();
        JsonNode payload = MockExportSupport.readResponse(stub);
        node.put("status", payload.path("status").asInt(200));
        node.put("latency", payload.path("fault").path("minMs").asInt(0));
        Map<String, String> headers = MockExportSupport.responseHeaders(payload);
        node.set("headers", MockExportSupport.headersNode(headers));
        node.put("body", MockExportSupport.bodyText(payload));
        String faultType = payload.path("fault").path("type").asText("");
        if (!faultType.isBlank()) {
            notes.add("「" + stub.name() + "」故障注入 " + faultType
                    + "：Mockoon 只支持延迟与状态码改写，其余（断连/畸形/慢速）导入后丢失");
        }
        if (MockExportSupport.isTemplateBody(payload)) {
            notes.add("「" + stub.name() + "」响应正文是平台模板（{{mock.*}}/{{gen.*}}）："
                    + "Mockoon 用自己的模板语言，占位符按原文导出需手工替换");
        }
        List<String> kinds = MockExportSupport.unsupportedMatcherKinds(
                MockExportSupport.matcherRows(stub), false);
        if (!kinds.isEmpty()) {
            notes.add("「" + stub.name() + "」匹配器 " + String.join("/", kinds)
                    + "：Mockoon 无等价能力，该路由导入后匹配面放宽，需人工复核");
        }
        return node;
    }
}
