package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.ApiAccessFixtureController;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape rules of the generated catalog: what a caller may address, what counts as a change, and
 * which shapes the door cannot serve. Pinned here so a mapping change cannot silently widen them.
 */
class ApiEndpointIntrospectorTest {

    private ApiEndpointSpec specOf(String method, String httpMethod, String pattern, Class<?>... argTypes)
            throws Exception {
        Method handler = ApiAccessFixtureController.class.getDeclaredMethod(method, argTypes);
        return ApiEndpointIntrospector.introspect(handler, httpMethod, pattern);
    }

    @Test
    void keyCombinesMethodAndPattern() throws Exception {
        ApiEndpointSpec spec = specOf("dictionary", "GET", "/internal/fixture/dictionary");
        assertEquals("GET /internal/fixture/dictionary", spec.key());
        assertEquals(ApiEndpointIntrospector.endpointKey("GET", "/internal/fixture/dictionary"), spec.key());
    }

    @Test
    void requirednessFollowsAnnotationsAndDefaults() throws Exception {
        ApiEndpointSpec spec = specOf("listItems", RequestMethod.GET.name(), "/internal/fixture/items",
                long.class, String.class, int.class);
        Map<String, ApiEndpointParam> byName = Map.of();
        for (ApiEndpointParam param : spec.params()) {
            byName = with(byName, param);
        }
        assertTrue(byName.get("projectId").required(), "a bare @RequestParam is required");
        assertFalse(byName.get("keyword").required(), "required=false is honoured");
        assertFalse(byName.get("page").required(), "a declared default makes the parameter optional");
        assertEquals("1", byName.get("page").defaultValue());
        assertEquals(ApiEndpointParam.IN_QUERY, byName.get("projectId").in());
        assertNull(byName.get("projectId").defaultValue());
        assertFalse(spec.writeClass());
        assertTrue(spec.dispatchable());
        assertEquals("projectId", spec.projectAnchor());
    }

    @Test
    void pathVariablesBindFromPattern() throws Exception {
        ApiEndpointSpec spec = specOf("item", "GET", "/internal/fixture/items/{id}", long.class, long.class);
        ApiEndpointParam id = spec.param("id");
        assertNotNull(id);
        assertEquals(ApiEndpointParam.IN_PATH, id.in());
        assertTrue(id.required());
        assertEquals("projectId", spec.projectAnchor());
    }

    @Test
    void commaJoinedAnchorStillNamesTheAnchor() throws Exception {
        ApiEndpointSpec spec = specOf("visibleItems", "GET", "/internal/fixture/items/visible", String.class);
        assertEquals("projectIds", spec.projectAnchor());
    }

    @Test
    void changeMethodsAreClassifiedAsWrites() throws Exception {
        ApiEndpointSpec delete = specOf("deleteItem", "DELETE", "/internal/fixture/items/{id}", long.class);
        assertTrue(delete.writeClass());
        ApiEndpointSpec create = specOf("createItem", "POST", "/internal/fixture/items", String.class);
        assertTrue(create.writeClass());
        assertNotNull(create.unsupportedReason(), "a required request body is not composable by the door");
    }

    @Test
    void optionalBodyKeepsTheShapeDispatchable() throws Exception {
        ApiEndpointSpec spec = specOf("updateItem", "PUT", "/internal/fixture/items/{id}",
                long.class, String.class);
        assertNull(spec.unsupportedReason(), "an optional body is not a shape failure; the method class blocks it");
        assertTrue(spec.writeClass());
    }

    @Test
    void headerBoundInputIsNotAddressable() throws Exception {
        ApiEndpointSpec spec = specOf("byHeader", "GET", "/internal/fixture/by-header",
                String.class, long.class);
        assertNotNull(spec.unsupportedReason());
        assertFalse(spec.dispatchable());
        ApiEndpointParam tenant = spec.param("tenant");
        assertNotNull(tenant);
        assertFalse(tenant.addressable());
    }

    @Test
    void containerArgumentsStayInvisible() throws Exception {
        ApiEndpointSpec spec = specOf("withRequest", "GET", "/internal/fixture/with-request-object",
                HttpServletRequest.class, long.class);
        List<ApiEndpointParam> params = spec.params();
        assertEquals(1, params.size(), "the servlet request argument is not a caller parameter");
        assertEquals("projectId", params.get(0).name());
        assertTrue(spec.dispatchable());
    }

    @Test
    void anchorlessEntryHasNoAnchor() throws Exception {
        ApiEndpointSpec spec = specOf("dictionary", "GET", "/internal/fixture/dictionary");
        assertNull(spec.projectAnchor());
        assertTrue(spec.params().isEmpty());
    }

    private static Map<String, ApiEndpointParam> with(Map<String, ApiEndpointParam> current,
                                                      ApiEndpointParam param) {
        java.util.Map<String, ApiEndpointParam> merged = new java.util.LinkedHashMap<>(current);
        merged.put(param.name(), param);
        return merged;
    }
}
