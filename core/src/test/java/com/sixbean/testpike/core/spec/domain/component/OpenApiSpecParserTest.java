package com.sixbean.testpike.core.spec.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class OpenApiSpecParserTest {

    private final OpenApiSpecParser parser = new OpenApiSpecParser();

    private static final String PET_API_JSON = """
            {
              "openapi": "3.0.1",
              "info": {"title": "Pet API", "version": "1.2.0"},
              "paths": {
                "/pets": {
                  "get": {
                    "operationId": "listPets",
                    "summary": "查询宠物列表",
                    "tags": ["pet"],
                    "parameters": [
                      {"name": "limit", "in": "query", "required": false,
                       "schema": {"type": "integer"}},
                      {"$ref": "#/components/parameters/TenantHeader"}
                    ],
                    "responses": {
                      "200": {"description": "ok",
                              "content": {"application/json":
                                  {"schema": {"$ref": "#/components/schemas/Pet"}}}},
                      "400": {"description": "bad"}
                    }
                  },
                  "post": {
                    "operationId": "createPet",
                    "security": [{"apiKey": []}],
                    "requestBody": {"content": {"application/json":
                        {"schema": {"$ref": "#/components/schemas/Pet"}}}},
                    "responses": {"201": {"description": "created"}}
                  }
                },
                "/pets/{petId}": {
                  "delete": {
                    "operationId": "deletePet",
                    "parameters": [{"name": "petId", "in": "path", "required": true,
                                   "schema": {"type": "string"}}],
                    "responses": {"204": {"description": "gone"}}
                  }
                },
                "/health/?debug=1": {
                  "get": {"operationId": "health", "responses": {"200": {"description": "ok"}}}
                }
              },
              "components": {
                "parameters": {
                  "TenantHeader": {"name": "X-Tenant", "in": "header", "required": true,
                                   "schema": {"type": "string"}}
                },
                "schemas": {
                  "Pet": {"type": "object", "properties": {
                      "id": {"type": "integer"},
                      "friend": {"$ref": "#/components/schemas/Pet"}
                  }}
                }
              }
            }
            """;

    private static final String PET_API_YAML = """
            openapi: 3.0.1
            info:
              title: Pet API
              version: "1.2.0"
            paths:
              /pets:
                get:
                  operationId: listPets
                  summary: 查询宠物列表
                  tags: [pet]
                  parameters:
                    - name: limit
                      in: query
                      required: false
                      schema: {type: integer}
                    - $ref: '#/components/parameters/TenantHeader'
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Pet'
                    '400':
                      description: bad
                post:
                  operationId: createPet
                  security:
                    - apiKey: []
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Pet'
                  responses:
                    '201':
                      description: created
              /pets/{petId}:
                delete:
                  operationId: deletePet
                  parameters:
                    - name: petId
                      in: path
                      required: true
                      schema: {type: string}
                  responses:
                    '204':
                      description: gone
              /health/?debug=1:
                get:
                  operationId: health
                  responses:
                    '200':
                      description: ok
            components:
              parameters:
                TenantHeader:
                  name: X-Tenant
                  in: header
                  required: true
                  schema: {type: string}
              schemas:
                Pet:
                  type: object
                  properties:
                    id: {type: integer}
                    friend:
                      $ref: '#/components/schemas/Pet'
            """;

    @Test
    void json解析产出清单与元信息() {
        ParsedApiSpec spec = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        assertEquals("Pet API", spec.title());
        assertEquals("1.2.0", spec.version());
        assertEquals(4, spec.endpoints().size());
    }

    @Test
    void 参数含ref展开与位置类型齐全() {
        ParsedApiSpec spec = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        ApiEndpoint get = find(spec, "GET /pets");
        assertEquals(2, get.parameters().size());
        ApiParameter limit = get.parameters().get(0);
        assertEquals("limit", limit.name());
        assertEquals("query", limit.location());
        assertEquals("integer", limit.type());
        assertFalse(limit.required());
        ApiParameter tenant = get.parameters().get(1);
        assertEquals("X-Tenant", tenant.name());
        assertEquals("header", tenant.location());
        assertTrue(tenant.required());
    }

    @Test
    void 请求体响应与安全键名提取() {
        ParsedApiSpec spec = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        ApiEndpoint post = find(spec, "POST /pets");
        assertEquals("application/json", post.requestContentType());
        assertEquals("Pet", post.requestSchema());
        assertEquals(List.of("apiKey"), post.securityRefs());
        ApiEndpoint get = find(spec, "GET /pets");
        assertEquals("Pet", get.responseSchemas().get("200"));
        assertFalse(get.responseSchemas().containsKey("400"));
    }

    @Test
    void 路径归一键去query去尾斜杠() {
        ParsedApiSpec spec = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        ApiEndpoint health = spec.endpoints().stream()
                .filter(ep -> "health".equals(ep.operationId()))
                .findFirst().orElseThrow();
        assertEquals("GET /health", health.endpointKey());
        assertEquals("DELETE /pets/{petId}", find(spec, "DELETE /pets/{petId}").endpointKey());
    }

    @Test
    void 结构哈希稳定且逐端点不同() {
        ParsedApiSpec a = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        ParsedApiSpec b = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        assertEquals(find(a, "GET /pets").structureHash(),
                find(b, "GET /pets").structureHash());
        assertNotNull(find(a, "POST /pets").structureHash());
        assertFalse(find(a, "GET /pets").structureHash()
                .equals(find(a, "POST /pets").structureHash()));
    }

    @Test
    void yaml与json同文档解析等价() {
        ParsedApiSpec json = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        ParsedApiSpec yaml = parser.parse(
                PET_API_YAML.getBytes(StandardCharsets.UTF_8), "openapi3-yaml");
        assertEquals("openapi3-yaml", yaml.specFormat());
        assertEquals(json.endpoints().stream().map(ApiEndpoint::endpointKey).sorted().toList(),
                yaml.endpoints().stream().map(ApiEndpoint::endpointKey).sorted().toList());
        assertEquals(find(json, "GET /pets").structureHash(),
                find(yaml, "GET /pets").structureHash());
    }

    @Test
    void 递归ref自引用不炸栈() {
        ParsedApiSpec spec = parser.parse(
                PET_API_JSON.getBytes(StandardCharsets.UTF_8), "openapi3-json");
        assertEquals(4, spec.endpoints().size());
    }

    @Test
    void 非OpenAPI文档与空字节人话拒() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> parser.parse("{\"hello\":1}".getBytes(StandardCharsets.UTF_8),
                        "openapi3-json"));
        assertTrue(e1.getMessage().contains("OpenAPI"));
        assertThrows(ServiceException.class,
                () -> parser.parse(new byte[0], "openapi3-json"));
        assertThrows(ServiceException.class,
                () -> parser.parse("paths: {}".getBytes(StandardCharsets.UTF_8),
                        "openapi3-yaml"));
    }

    private static ApiEndpoint find(ParsedApiSpec spec, String keyOrPathFragment) {
        String needle = keyOrPathFragment.contains(" ")
                ? keyOrPathFragment
                : keyOrPathFragment;
        return spec.endpoints().stream()
                .filter(ep -> ep.endpointKey().equals(needle)
                        || ep.endpointKey().endsWith(needle)
                        || ep.path().equals(needle))
                .findFirst()
                .orElseThrow(() -> new AssertionError("清单缺少端点: " + needle));
    }
}
