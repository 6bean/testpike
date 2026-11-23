package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one handler method into an {@link ApiEndpointSpec} using reflection only.
 *
 * <p>Kept free of the Spring mapping model so the shape rules (which parameters a caller can
 * address, what counts as a write, what makes an endpoint undispatchable) are unit-testable
 * without a web context. Method parameter names are real because the build keeps
 * {@code -parameters} on, which the whole project already relies on for SpEL and path variables.
 */
public final class ApiEndpointIntrospector {

    /** Safe-read methods: they carry no state change of their own, so the door may serve them. */
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

    /** Container-supplied parameters: the caller neither needs nor can provide them. */
    private static final Set<String> CONTAINER_TYPE_NAMES = Set.of(
            "HttpServletRequest", "HttpServletResponse", "HttpSession", "ServletRequest",
            "ServletResponse", "Principal", "Model", "ModelMap", "BindingResult",
            "RedirectAttributes", "UriInfo", "WebRequest", "ServletWebRequest", "Locale");

    private ApiEndpointIntrospector() {
    }

    /**
     * Declared fields of a request-body type: record components first, then bean fields.
     *
     * <p>This is the whitelist a change landing composes against: anything the internal door did
     * not declare cannot be sent to it, which keeps a generative catalog from turning the dialog
     * into a mass-assignment channel. Ownership-bearing values the door does declare (a project
     * anchor, for instance) are still re-stamped by the service from the session, never trusted
     * from the proposal.
     */
    static List<String> declaredFields(Class<?> type) {
        List<String> names = new ArrayList<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                names.add(component.getName());
            }
            return List.copyOf(names);
        }
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic() || "serialVersionUID".equals(field.getName())) {
                continue;
            }
            names.add(field.getName());
        }
        return List.copyOf(names);
    }

    /** Stable, model-readable handle for one endpoint. */
    public static String endpointKey(String httpMethod, String pathPattern) {
        return httpMethod + " " + pathPattern;
    }

    /**
     * Introspect one handler method.
     *
     * @param method     the Java method behind the mapping
     * @param httpMethod declared HTTP method, uppercase
     * @param pathPattern path pattern with placeholders intact
     * @return catalog entry describing what a caller must and may provide
     */
    public static ApiEndpointSpec introspect(Method method, String httpMethod, String pathPattern) {
        List<ApiEndpointParam> params = new ArrayList<>();
        String unsupported = null;
        String bodyType = null;
        List<String> bodyFields = List.of();
        for (Parameter parameter : method.getParameters()) {
            PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
            if (pathVariable != null) {
                String name = firstName(pathVariable.value(), pathVariable.name(), parameter.getName());
                params.add(new ApiEndpointParam(name, ApiEndpointParam.IN_PATH,
                        pathVariable.required(), simpleType(parameter), null));
                continue;
            }
            RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
            if (requestParam != null) {
                String name = firstName(requestParam.value(), requestParam.name(), parameter.getName());
                // Spring spells "no default" as a magic sentinel, not as an empty string: comparing
                // with isEmpty() would make every parameter look optional and default-valued.
                boolean hasDefault = !ValueConstants.DEFAULT_NONE.equals(requestParam.defaultValue());
                params.add(new ApiEndpointParam(name, ApiEndpointParam.IN_QUERY,
                        requestParam.required() && !hasDefault, simpleType(parameter),
                        hasDefault ? requestParam.defaultValue() : null));
                continue;
            }
            RequestBody requestBody = parameter.getAnnotation(RequestBody.class);
            if (requestBody != null) {
                bodyType = simpleType(parameter);
                bodyFields = declaredFields(parameter.getType());
                if (requestBody.required() && unsupported == null) {
                    unsupported = "endpoint reads a request body, which the read door does not compose";
                }
                continue;
            }
            if (CONTAINER_TYPE_NAMES.contains(simpleType(parameter))) {
                continue;
            }
            params.add(new ApiEndpointParam(parameter.getName(), ApiEndpointParam.IN_OTHER, true,
                    simpleType(parameter), null));
            if (unsupported == null) {
                unsupported = "parameter " + parameter.getName() + " is bound from "
                        + simpleType(parameter) + ", which a caller cannot address";
            }
        }
        return new ApiEndpointSpec(endpointKey(httpMethod, pathPattern), httpMethod, pathPattern,
                List.copyOf(params), !READ_METHODS.contains(httpMethod), unsupported,
                bodyType, bodyFields);
    }

    /** First non-blank declared name, falling back to the reflective parameter name. */
    private static String firstName(String value, String name, String fallback) {
        if (!value.isBlank()) {
            return value;
        }
        if (!name.isBlank()) {
            return name;
        }
        return fallback;
    }

    /** Type name as displayed in schemas: component type of arrays and generics. */
    private static String simpleType(Parameter parameter) {
        Class<?> type = parameter.getType();
        if (type.isArray()) {
            return simpleName(type.getComponentType()) + "[]";
        }
        return simpleName(type);
    }

    private static String simpleName(Class<?> type) {
        String name = type.getSimpleName();
        return name.isEmpty() ? type.getName() : name;
    }
}
