package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Catalog of this application's own endpoints, read off the live request mappings.
 *
 * <p>Why generated instead of registered: every hand-written tool row is a capability that
 * somebody had to implement first. The pass-through door needs the opposite property — when a
 * developer adds an endpoint, the dialog can reach it in the same release without a new row,
 * and safety comes from the classification of the mapping (method, shape, project anchor) plus
 * the governance overlay, not from somebody remembering to register it.
 *
 * <p>Scope: only controllers of this application (the core service) are cataloged. Framework
 * handlers such as the error page are excluded, and so are mappings that declare no HTTP method
 * — an unclassified method cannot be judged read or write, so it stays out of reach.
 */
@Component
public class ApiEndpointCatalog {

    /** Handler beans of this application only (framework handlers and third-party MVC stay out). */
    static final String CORE_PACKAGE_PREFIX = "com.sixbean.testpike.core.";

    private static final Logger log = LoggerFactory.getLogger(ApiEndpointCatalog.class);

    /**
     * The MVC handler registry itself. Qualified by bean name because actuator registers a second
     * {@link RequestMappingHandlerMapping} subclass for endpoint exposure, and an unqualified
     * injection is ambiguous (proved by a live boot, not by a unit test).
     */
    private final RequestMappingHandlerMapping handlerMapping;

    private volatile Map<String, ApiEndpointSpec> snapshot = Map.of();

    public ApiEndpointCatalog(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.handlerMapping = handlerMapping;
    }

    /** Current catalog (built on first access; key = {@code HTTP-METHOD /path/pattern}). */
    public Map<String, ApiEndpointSpec> endpoints() {
        Map<String, ApiEndpointSpec> current = snapshot;
        if (current.isEmpty()) {
            current = refresh();
        }
        return current;
    }

    /** One catalog entry by key, null when the key is unknown or blank. */
    public ApiEndpointSpec find(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return endpoints().get(key.trim());
    }

    /** Rebuilds the snapshot from the live mappings (called on first use and by the refresh door). */
    public synchronized Map<String, ApiEndpointSpec> refresh() {
        Map<String, ApiEndpointSpec> built = describe(handlerMapping.getHandlerMethods());
        snapshot = built;
        log.info("api pass-through catalog rebuilt endpoints={}", built.size());
        return built;
    }

    /**
     * Pure mapping-to-catalog transform, exposed for tests: one entry per declared method and
     * path pattern combination.
     */
    static Map<String, ApiEndpointSpec> describe(Map<RequestMappingInfo, HandlerMethod> handlers) {
        Map<String, ApiEndpointSpec> catalog = new LinkedHashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlers.entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith(CORE_PACKAGE_PREFIX)) {
                continue;
            }
            Set<String> patterns = entry.getKey().getPatternValues();
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            if (methods.isEmpty() || patterns.isEmpty()) {
                // No declared method or no path: read/write class is unknowable, keep it out of reach.
                continue;
            }
            for (org.springframework.web.bind.annotation.RequestMethod method : methods) {
                for (String pattern : patterns) {
                    ApiEndpointSpec spec = ApiEndpointIntrospector.introspect(
                            handler.getMethod(), method.name(), pattern);
                    catalog.putIfAbsent(spec.key(), spec);
                }
            }
        }
        return Map.copyOf(catalog);
    }
}
