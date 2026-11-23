package com.sixbean.testpike.core.apiaccess.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apiaccess.ApiAccessFixtureController;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessTarget;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argument-to-request translation: placeholders, encoding, and the refusals that keep a caller from
 * addressing anything the endpoint did not declare.
 */
class ApiAccessRequestFactoryTest {

    private final ApiAccessRequestFactory factory = new ApiAccessRequestFactory(new ObjectMapper());

    private ApiEndpointSpec specOf(String method, String httpMethod, String pattern, Class<?>... argTypes)
            throws Exception {
        Method handler = ApiAccessFixtureController.class.getDeclaredMethod(method, argTypes);
        return ApiEndpointIntrospector.introspect(handler, httpMethod, pattern);
    }

    @Test
    void queryParametersAreEncodedAndCommasSurvive() throws Exception {
        ApiEndpointSpec spec = specOf("listItems", "GET", "/internal/fixture/items",
                long.class, String.class, int.class);
        ApiAccessTarget target = factory.build(spec, Map.of("projectId", 12, "keyword", "a,b"));
        assertTrue(target.requestUri().startsWith("/internal/fixture/items?"), target.requestUri());
        assertTrue(target.requestUri().contains("projectId=12"), target.requestUri());
        assertTrue(target.requestUri().contains("keyword=a%2Cb"),
                "a comma inside one value must not be split into two parameters: " + target.requestUri());
        assertEquals(List.of("keyword", "projectId"), sorted(target.usedArguments()));
    }

    @Test
    void pathPlaceholdersAreFilledFromArguments() throws Exception {
        ApiEndpointSpec spec = specOf("item", "GET", "/internal/fixture/items/{id}", long.class, long.class);
        ApiAccessTarget target = factory.build(spec, Map.of("id", 7, "projectId", 3));
        assertEquals("/internal/fixture/items/7?projectId=3", target.requestUri());
    }

    @Test
    void missingPathPlaceholderIsRefused() throws Exception {
        ApiEndpointSpec spec = specOf("item", "GET", "/internal/fixture/items/{id}", long.class, long.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec, Map.of("projectId", 3)));
        assertTrue(ex.getMessage().contains("id"), ex.getMessage());
    }

    @Test
    void undeclaredArgumentIsRefusedInsteadOfIgnored() throws Exception {
        ApiEndpointSpec spec = specOf("listItems", "GET", "/internal/fixture/items",
                long.class, String.class, int.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec, Map.of("projectId", 1, "operatorId", 999)));
        assertTrue(ex.getMessage().contains("operatorId"), ex.getMessage());
        assertTrue(ex.getMessage().contains("不是该条目声明的参数"), ex.getMessage());
    }

    @Test
    void missingRequiredQueryParameterIsRefused() throws Exception {
        ApiEndpointSpec spec = specOf("listItems", "GET", "/internal/fixture/items",
                long.class, String.class, int.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.build(spec, Map.of("keyword", "x")));
        assertTrue(ex.getMessage().contains("projectId"), ex.getMessage());
    }

    @Test
    void collectionValuesBecomeRepeatedParameters() throws Exception {
        ApiEndpointSpec spec = specOf("byStatus", "GET", "/internal/fixture/items/by-status",
                long.class, List.class);
        ApiAccessTarget target = factory.build(spec,
                Map.of("projectId", 5, "status", List.of(1, 2)));
        assertTrue(target.requestUri().contains("status=1"), target.requestUri());
        assertTrue(target.requestUri().contains("status=2"), target.requestUri());
    }

    @Test
    void anchorIdsAreParsedFromEveryAnchorShape() throws Exception {
        ApiEndpointSpec single = specOf("item", "GET", "/internal/fixture/items/{id}", long.class, long.class);
        assertEquals(List.of(5L), factory.anchorProjectIds(single, Map.of("projectId", "5")));

        ApiEndpointSpec joined = specOf("visibleItems", "GET", "/internal/fixture/items/visible", String.class);
        assertEquals(List.of(1L, 2L, 3L), factory.anchorProjectIds(joined, Map.of("projectIds", "1, 2,3")));

        ApiEndpointSpec none = specOf("dictionary", "GET", "/internal/fixture/dictionary");
        assertTrue(factory.anchorProjectIds(none, Map.of()).isEmpty());
    }

    @Test
    void nonNumericAnchorIsRefusedWithTheOffendingValue() throws Exception {
        ApiEndpointSpec spec = specOf("item", "GET", "/internal/fixture/items/{id}", long.class, long.class);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.anchorProjectIds(spec, Map.of("projectId", "abc")));
        assertTrue(ex.getMessage().contains("abc"), ex.getMessage());
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
