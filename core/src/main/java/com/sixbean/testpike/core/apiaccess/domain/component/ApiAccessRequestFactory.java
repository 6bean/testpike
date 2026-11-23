package com.sixbean.testpike.core.apiaccess.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessTarget;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteTarget;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns caller arguments into one addressed request, or refuses with the reason.
 *
 * <p>Stray arguments are refused instead of ignored. That is what keeps the door from becoming an
 * identity-forgery seam: a caller can only ever fill parameters the endpoint itself declares, so
 * there is no way to smuggle an actor claim through the argument bag — the acting identity is
 * injected by the dispatcher as a header and is not addressable here at all.
 */
@Component
public class ApiAccessRequestFactory {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final ObjectMapper objectMapper;

    public ApiAccessRequestFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Serialize a composed payload with the shared mapper (no hand-rolled JSON writer). */
    private String writeBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("草案载荷无法序列化为请求体：" + ex.getOriginalMessage());
        }
    }

    /**
     * Build the request target.
     *
     * @param spec      catalog entry (already policy-cleared)
     * @param arguments caller-supplied argument bag (name to value)
     * @throws IllegalArgumentException when a placeholder is unfilled, a required query parameter
     *                                  is missing, or an argument is not declared by the endpoint
     */
    public ApiAccessTarget build(ApiEndpointSpec spec, Map<String, Object> arguments) {
        Map<String, Object> rest = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        List<String> used = new ArrayList<>();

        Matcher matcher = PLACEHOLDER.matcher(spec.pathPattern());
        Set<String> placeholders = new LinkedHashSet<>();
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        Map<String, Object> pathVariables = new LinkedHashMap<>();
        for (String name : placeholders) {
            if (!rest.containsKey(name)) {
                throw new IllegalArgumentException("缺少路径参数 " + name + "（该条目需要 " + requiredNames(spec) + "）");
            }
            pathVariables.put(name, String.valueOf(rest.remove(name)));
            used.add(name);
        }
        String path = UriComponentsBuilder.fromPath(spec.pathPattern())
                .buildAndExpand(pathVariables)
                .encode()
                .toUriString();

        StringBuilder query = new StringBuilder();
        for (ApiEndpointParam param : spec.params()) {
            if (!ApiEndpointParam.IN_QUERY.equals(param.in())) {
                continue;
            }
            if (!rest.containsKey(param.name())) {
                if (param.required() && param.defaultValue() == null) {
                    throw new IllegalArgumentException("缺少必填查询参数 " + param.name());
                }
                continue;
            }
            Object value = rest.remove(param.name());
            used.add(param.name());
            for (String single : wireValues(value)) {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(encode(param.name())).append('=').append(encode(single));
            }
        }
        if (!rest.isEmpty()) {
            throw new IllegalArgumentException("参数 " + String.join("、", rest.keySet())
                    + " 不是该条目声明的参数，直通不接收未声明参数");
        }
        String requestUri = query.length() == 0 ? path : path + "?" + query;
        return new ApiAccessTarget(requestUri, List.copyOf(used));
    }

    /**
     * Compose a change landing: path placeholders, declared query parameters, and a request body
     * built only from the body type's own declared fields.
     *
     * <p>The whitelist is the point. A generative catalog that let a proposal put arbitrary keys in
     * a body would hand the dialog a mass-assignment channel against internal doors, so anything the
     * door did not declare is refused before the request is built.
     *
     * @throws IllegalArgumentException when the entry declares no body to compose, an argument is
     *                                  neither a declared parameter nor a declared body field, or a
     *                                  required placeholder/parameter is missing
     */
    public ApiAccessWriteTarget buildWrite(ApiEndpointSpec spec, Map<String, Object> arguments) {
        Map<String, Object> rest = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        List<String> used = new ArrayList<>();

        Matcher matcher = PLACEHOLDER.matcher(spec.pathPattern());
        Map<String, Object> pathVariables = new LinkedHashMap<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!rest.containsKey(name)) {
                throw new IllegalArgumentException("缺少路径参数 " + name
                        + "（该条目需要 " + requiredNames(spec) + "）");
            }
            pathVariables.put(name, String.valueOf(rest.remove(name)));
            used.add(name);
        }
        String path = UriComponentsBuilder.fromPath(spec.pathPattern())
                .buildAndExpand(pathVariables)
                .encode()
                .toUriString();

        StringBuilder query = new StringBuilder();
        for (ApiEndpointParam param : spec.params()) {
            if (!ApiEndpointParam.IN_QUERY.equals(param.in()) || !rest.containsKey(param.name())) {
                if (ApiEndpointParam.IN_QUERY.equals(param.in()) && param.required()
                        && param.defaultValue() == null && !rest.containsKey(param.name())) {
                    throw new IllegalArgumentException("缺少必填查询参数 " + param.name());
                }
                continue;
            }
            Object value = rest.remove(param.name());
            used.add(param.name());
            for (String single : wireValues(value)) {
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(encode(param.name())).append('=').append(encode(single));
            }
        }
        String requestUri = query.length() == 0 ? path : path + "?" + query;

        if (spec.bodyType() == null) {
            if (!rest.isEmpty()) {
                throw new IllegalArgumentException("参数 " + String.join("、", rest.keySet())
                        + " 不是该条目声明的参数，直通不接收未声明参数");
            }
            return new ApiAccessWriteTarget(requestUri, spec.httpMethod(), null, List.copyOf(used));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        for (String field : spec.bodyFields()) {
            if (rest.containsKey(field)) {
                body.put(field, rest.remove(field));
                used.add(field);
            }
        }
        if (!rest.isEmpty()) {
            throw new IllegalArgumentException("载荷字段 " + String.join("、", rest.keySet())
                    + " 不在该条目请求体 " + spec.bodyType() + " 的声明字段内，直通不组装未声明字段");
        }
        if (body.isEmpty()) {
            throw new IllegalArgumentException("该条目的请求体 " + spec.bodyType()
                    + " 需要一个字段值，草案载荷是空的（可填字段：" + String.join("、", spec.bodyFields()) + "）");
        }
        return new ApiAccessWriteTarget(requestUri, spec.httpMethod(), writeBody(body), List.copyOf(used));
    }

    /**
     * Project ids the call claims, from the endpoint's project anchor parameter.
     *
     * @return empty when the endpoint has no anchor or the caller gave none (the guard then refuses)
     */
    public List<Long> anchorProjectIds(ApiEndpointSpec spec, Map<String, Object> arguments) {
        String anchor = spec.projectAnchor();
        if (anchor == null || arguments == null || !arguments.containsKey(anchor)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String raw : flatten(arguments.get(anchor))) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(token));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("项目归属参数 " + anchor + " 需为项目 id，收到：" + token);
            }
        }
        return List.copyOf(ids);
    }

    /** Names the endpoint must receive (placeholders plus required query parameters). */
    private String requiredNames(ApiEndpointSpec spec) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(spec.pathPattern());
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        for (ApiEndpointParam param : spec.params()) {
            if (ApiEndpointParam.IN_QUERY.equals(param.in()) && param.required() && param.defaultValue() == null) {
                names.add(param.name());
            }
        }
        return names.isEmpty() ? "无" : String.join("、", names);
    }

    /**
     * Values as they go on the wire: a collection becomes repeated parameters, a scalar stays one
     * value exactly as given. Comma splitting is deliberately absent here — an endpoint that takes
     * "1,2" inside a single String parameter would be broken by it.
     */
    private List<String> wireValues(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        if (value instanceof Object[] array) {
            for (Object item : array) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        out.add(String.valueOf(value));
        return out;
    }

    /** One value or a collection of values, each as text; null entries dropped. */
    private List<String> flatten(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        if (value instanceof Object[] array) {
            for (Object item : array) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        String text = String.valueOf(value);
        for (String part : text.split(",")) {
            String token = part.trim();
            if (!token.isEmpty()) {
                out.add(token);
            }
        }
        return out;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
