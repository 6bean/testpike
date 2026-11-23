package com.sixbean.testpike.core.spec.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apitest.domain.component.ApiAssertionFormContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SpecDraftMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OpenApiSpecParser parser = new OpenApiSpecParser();
    private final SpecDraftMapper mapper = new SpecDraftMapper();

    private static final String API_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Order API", "version": "1.0.0"},
              "paths": {
                "/orders": {
                  "get": {
                    "operationId": "listOrders",
                    "summary": "查询订单列表",
                    "description": "分页条件查询，租户头必填",
                    "tags": ["order", "query"],
                    "parameters": [
                      {"name": "limit", "in": "query", "required": false,
                       "schema": {"type": "integer"}}
                    ],
                    "responses": {
                      "200": {"description": "ok",
                              "content": {"application/json":
                                  {"schema": {"type": "object"}}}},
                      "400": {"description": "bad",
                              "content": {"application/json":
                                  {"schema": {"type": "object"}}}},
                      "404": {"description": "not found"}
                    }
                  },
                  "post": {
                    "operationId": "createOrder",
                    "security": [{"bearerAuth": []}],
                    "requestBody": {"content": {"application/json":
                        {"schema": {"type": "object"}}}},
                    "responses": {"201": {"description": "created"}}
                  }
                },
                "/health": {
                  "get": {"responses": {"200": {"description": "ok"}}}
                }
              }
            }
            """;

    private List<ApiSpecDraftItem> drafts() {
        ParsedApiSpec parsed = parser.parse(
                API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        return mapper.fromParsedSpec(parsed, 3000, "$.code", "0");
    }

    @Test
    void summaryAndDescriptionAndTagsCarriedToDraft() {
        ApiSpecDraftItem item = drafts().stream()
                .filter(d -> d.endpointKey().equals("GET /orders")).findFirst().orElseThrow();
        assertEquals("查询订单列表", item.title());
        assertEquals("分页条件查询，租户头必填", item.description());
        assertEquals(List.of("order", "query"), item.tags());
    }

    @Test
    void titleFallsBackToOperationIdThenPath() {
        List<ApiSpecDraftItem> all = drafts();
        ApiSpecDraftItem post = all.stream()
                .filter(d -> d.endpointKey().equals("POST /orders")).findFirst().orElseThrow();
        assertEquals("createOrder", post.title());
        ApiSpecDraftItem health = all.stream()
                .filter(d -> d.endpointKey().equals("GET /health")).findFirst().orElseThrow();
        assertEquals("/health", health.title());
    }

    @Test
    void defaultAssertionsPassFormContract() throws Exception {
        ApiSpecDraftItem item = drafts().stream()
                .filter(d -> d.endpointKey().equals("GET /orders")).findFirst().orElseThrow();
        List<Map<String, Object>> rows = MAPPER.readValue(item.assertionsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        assertEquals(3, rows.size());
        Map<String, Object> status = rows.get(0);
        assertEquals("status", status.get("type"));
        assertEquals(200, status.get("expect"));
        Map<String, Object> duration = rows.get(1);
        assertEquals("duration", duration.get("type"));
        assertEquals("range", duration.get("op"));
        assertEquals(List.of(0, 3000), duration.get("expect"));
        Map<String, Object> body = rows.get(2);
        assertEquals("body", body.get("type"));
        assertEquals("$.code", body.get("path"));
        for (Map<String, Object> row : rows) {
            assertNull(ApiAssertionFormContract.rowViolation(MAPPER.valueToTree(row)),
                    "默认断言行必须过 WS2 表单内核: " + row);
        }
    }

    @Test
    void negativeCandidatesFromDeclared4xxDefaultUnselected() throws Exception {
        ApiSpecDraftItem item = drafts().stream()
                .filter(d -> d.endpointKey().equals("GET /orders")).findFirst().orElseThrow();
        assertFalse(item.negativeSelected());
        List<Map<String, Object>> negs = MAPPER.readValue(item.negativeAssertionsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        assertEquals(1, negs.size());
        assertEquals(400, negs.get(0).get("expect"));
        for (Map<String, Object> row : negs) {
            assertNull(ApiAssertionFormContract.rowViolation(MAPPER.valueToTree(row)));
        }
        ApiSpecDraftItem no4xx = drafts().stream()
                .filter(d -> d.endpointKey().equals("POST /orders")).findFirst().orElseThrow();
        assertEquals("[]", no4xx.negativeAssertionsJson());
    }

    @Test
    void securityCarriesKeyNamesOnlyNoSecret() {
        ApiSpecDraftItem post = drafts().stream()
                .filter(d -> d.endpointKey().equals("POST /orders")).findFirst().orElseThrow();
        assertEquals(List.of("bearerAuth"), post.securityRefs());
        assertTrue(post.authHintJson().contains("bearerAuth"));
        assertFalse(post.authHintJson().toLowerCase().contains("token:"));
    }

    @Test
    void parameterSkeletonCarriesNameLocationRequired() throws Exception {
        ApiSpecDraftItem item = drafts().stream()
                .filter(d -> d.endpointKey().equals("GET /orders")).findFirst().orElseThrow();
        List<Map<String, Object>> params = MAPPER.readValue(item.paramsSkeletonJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        assertEquals(1, params.size());
        assertEquals("limit", params.get(0).get("name"));
        assertEquals("query", params.get(0).get("location"));
        assertEquals(false, params.get(0).get("required"));
        assertNotNull(params.get(0).get("type"));
    }

    @Test
    void channelMetaMarkedDeterministicWithEmptySourceAnchor() {
        ApiSpecDraftItem item = drafts().get(0);
        assertEquals("A", item.channel());
        assertEquals("deterministic", item.confidence());
        assertNull(item.sourceAnchor());
    }

    @Test
    void parameterSkeletonCarriesFormatAndExampleThenGenkitFills() throws Exception {
        String spec = """
                {
                  "openapi": "3.0.1",
                  "info": {"title": "P API", "version": "1.0.0"},
                  "paths": {
                    "/p": {
                      "get": {
                        "operationId": "probe",
                        "parameters": [
                          {"name": "cardNo", "in": "query", "required": true,
                           "schema": {"type": "string", "format": "id-card"}},
                          {"name": "mobile", "in": "query",
                           "schema": {"type": "string", "format": "phone"}},
                          {"name": "contact", "in": "query",
                           "schema": {"type": "string", "example": "zhang@example.com"}},
                          {"name": "limit", "in": "query",
                           "schema": {"type": "integer"}}
                        ],
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """;
        ParsedApiSpec parsed = parser.parse(
                spec.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        ApiSpecDraftItem item = mapper.fromParsedSpec(parsed, 3000, "", "")
                .get(0);
        List<Map<String, Object>> params = MAPPER.readValue(item.paramsSkeletonJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() { });
        assertEquals(4, params.size());
        Map<String, Object> cardNo = params.get(0);
        assertEquals("id-card", cardNo.get("format"));
        assertNotNull(cardNo.get("example"), "format=id-card 无 example 须由 genkit 身份证族填充");
        assertTrue(String.valueOf(cardNo.get("example")).matches("\\d{17}[\\dXx]"),
                "genkit 填充必须是带校验位形态的 18 位: " + cardNo.get("example"));
        Map<String, Object> mobile = params.get(1);
        assertEquals("phone", mobile.get("format"));
        assertTrue(String.valueOf(mobile.get("example")).matches("1\\d{10}"),
                "genkit 填充必须是合法手机号: " + mobile.get("example"));
        Map<String, Object> contact = params.get(2);
        assertEquals("zhang@example.com", contact.get("example"), "schema.example 优先");
        assertNull(params.get(3).get("example"), "无 format 无 example 不硬造值");
    }

    @Test
    void bodySkeletonPlaceholderWhenRequestContentTypePresent() {
        ApiSpecDraftItem post = drafts().stream()
                .filter(d -> d.endpointKey().equals("POST /orders")).findFirst().orElseThrow();
        assertEquals("application/json", post.requestContentType());
        assertTrue(post.bodySkeleton().startsWith("{"));
        ApiSpecDraftItem get = drafts().stream()
                .filter(d -> d.endpointKey().equals("GET /orders")).findFirst().orElseThrow();
        assertEquals("", get.bodySkeleton());
    }
}
