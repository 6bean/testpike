package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.ApiAccessFixtureController;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import com.sixbean.testpike.foreign.ForeignMappingFixture;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Catalog assembly rules: which mappings become entries, which are skipped, and that a rebuild is
 * what makes an endpoint appear without anybody registering it.
 */
class ApiEndpointCatalogTest {

    private final RequestMappingInfo.BuilderConfiguration options = buildOptions();

    private static RequestMappingInfo.BuilderConfiguration buildOptions() {
        RequestMappingInfo.BuilderConfiguration config = new RequestMappingInfo.BuilderConfiguration();
        config.setPatternParser(new PathPatternParser());
        return config;
    }

    /** Mapping with no declared HTTP method (unclassified read/write). */
    private RequestMappingInfo mapping(String pattern) {
        return RequestMappingInfo.paths(pattern).options(options).build();
    }

    private RequestMappingInfo mappingWithMethods(String pattern, RequestMethod... methods) {
        return RequestMappingInfo.paths(pattern).methods(methods).options(options).build();
    }

    private HandlerMethod handler(Class<?> type, String methodName) throws Exception {
        Method method = type.getDeclaredMethod(methodName);
        return new HandlerMethod(type.getDeclaredConstructor().newInstance(), method);
    }

    @Test
    void ownHandlersBecomeEntries() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mappingWithMethods("/internal/fixture/dictionary", RequestMethod.GET),
                handler(ApiAccessFixtureController.class, "dictionary"));
        Map<String, ApiEndpointSpec> catalog = ApiEndpointCatalog.describe(handlers);
        ApiEndpointSpec entry = catalog.get("GET /internal/fixture/dictionary");
        assertNotNull(entry);
        assertEquals("/internal/fixture/dictionary", entry.pathPattern());
    }

    @Test
    void foreignHandlersAreNotCataloged() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mappingWithMethods("/foreign/thing", RequestMethod.GET),
                handler(ForeignMappingFixture.class, "thing"));
        assertTrue(ApiEndpointCatalog.describe(handlers).isEmpty(),
                "framework or third-party handlers must not become dialog-reachable entries");
    }

    @Test
    void mappingWithoutDeclaredMethodIsSkipped() throws Exception {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlers.put(mapping("/internal/fixture/dictionary"), handler(ApiAccessFixtureController.class, "dictionary"));
        Map<String, ApiEndpointSpec> catalog = ApiEndpointCatalog.describe(handlers);
        assertFalse(catalog.containsKey("GET /internal/fixture/dictionary"),
                "an unclassified method has no read/write verdict, so it stays out of reach");
    }

    @Test
    void rebuildPicksUpNewHandlerWithoutRegistration() throws Exception {
        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping =
                mock(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> first = new LinkedHashMap<>();
        first.put(mappingWithMethods("/internal/fixture/dictionary", RequestMethod.GET),
                handler(ApiAccessFixtureController.class, "dictionary"));
        when(mapping.getHandlerMethods()).thenReturn(first);
        ApiEndpointCatalog catalog = new ApiEndpointCatalog(mapping);
        assertEquals(1, catalog.endpoints().size());

        Map<RequestMappingInfo, HandlerMethod> grown = new LinkedHashMap<>(first);
        grown.put(mappingWithMethods("/internal/fixture/items", RequestMethod.GET),
                handlerByParams(ApiAccessFixtureController.class, "listItems",
                        long.class, String.class, int.class));
        when(mapping.getHandlerMethods()).thenReturn(grown);
        assertEquals(2, catalog.refresh().size(), "a new endpoint enters the catalog by itself");
        assertNotNull(catalog.find("GET /internal/fixture/items"));
    }

    /** A failed rebuild must reach the alert chain like every other scheduler does. */
    @Test
    void refresherRaisesTheAlertChainWhenTheRebuildFails() {
        ApiEndpointCatalog catalog = mock(ApiEndpointCatalog.class);
        JobRunner jobRunner = mock(JobRunner.class);
        when(catalog.endpoints()).thenReturn(Map.of());
        when(catalog.refresh()).thenThrow(new IllegalStateException("registry unavailable"));
        new ApiEndpointCatalogRefresher(catalog, jobRunner).refreshCatalog();
        verify(jobRunner, times(1)).alert(eq("apiAccessCatalogRefresh"),
                org.mockito.ArgumentMatchers.any(Exception.class));
    }

    @Test
    void refresherDelegatesToTheCatalog() {
        ApiEndpointCatalog catalog = mock(ApiEndpointCatalog.class);
        when(catalog.endpoints()).thenReturn(Map.of());
        when(catalog.refresh()).thenReturn(Map.of());
        new ApiEndpointCatalogRefresher(catalog, mock(JobRunner.class)).refreshCatalog();
        verify(catalog, times(1)).refresh();
    }

    private HandlerMethod handlerByParams(Class<?> type, String methodName, Class<?>... params) throws Exception {
        Method method = type.getDeclaredMethod(methodName, params);
        return new HandlerMethod(type.getDeclaredConstructor().newInstance(), method);
    }
}
