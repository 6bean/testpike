package com.sixbean.testpike.core.spec.domain.component;

import com.alibaba.fastjson2.JSON;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


@Component
public class OpenApiSpecParser {

    
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    
    private static final int MAX_REF_DEPTH = 5;

    public static final String FORMAT_JSON = "openapi3-json";
    public static final String FORMAT_YAML = "openapi3-yaml";

    /**
 *
 * @param specFormat openapi3-json | openapi3-yaml
 */
    public ParsedApiSpec parse(byte[] content, String specFormat) {
        if (content == null || content.length == 0) {
            throw new ServiceException("契约文件内容为空，请检查上传文件", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> root = readTree(content, specFormat);
        Object openapi = root.get("openapi");
        if (openapi == null || !String.valueOf(openapi).startsWith("3")) {
            throw new ServiceException("仅支持 OpenAPI 3 文档（缺少 openapi: 3.x 版本声明）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> paths = asMap(root.get("paths"));
        if (paths == null || paths.isEmpty()) {
            throw new ServiceException("OpenAPI 文档缺少有效 paths 接口定义",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<ApiEndpoint> endpoints = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            Map<String, Object> item = asMap(pathEntry.getValue());
            if (item == null) {
                continue;
            }
            List<ApiParameter> pathParams = readParameters(item.get("parameters"), root);
            for (Map.Entry<String, Object> opEntry : item.entrySet()) {
                String method = opEntry.getKey().toLowerCase(Locale.ROOT);
                if (!METHODS.contains(method)) {
                    continue;
                }
                endpoints.add(readEndpoint(method, pathEntry.getKey(),
                        asMap(opEntry.getValue()), pathParams, root));
            }
        }
        endpoints.sort(Comparator.comparing(ApiEndpoint::endpointKey));
        Map<String, Object> info = asMap(root.get("info"));
        return new ParsedApiSpec(specFormat,
                info == null ? null : str(info.get("title")),
                info == null ? null : str(info.get("version")),
                List.copyOf(endpoints));
    }


    private static Map<String, Object> readTree(byte[] content, String specFormat) {
        Object tree;
        try {
            if (FORMAT_YAML.equals(specFormat)) {
                tree = new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(new java.io.ByteArrayInputStream(content));
            } else if (FORMAT_JSON.equals(specFormat)) {
                tree = JSON.parse(new String(content, StandardCharsets.UTF_8));
            } else {
                throw new ServiceException("不支持的契约格式: " + specFormat
                        + "（仅 openapi3-json / openapi3-yaml）", BizErrorCode.VALIDATION_ERROR);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("契约文件解析失败，请确认内容为合法 JSON/YAML（"
                    + e.getClass().getSimpleName() + "）", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> root = asMap(tree);
        if (root == null) {
            throw new ServiceException("契约文件根节点必须是对象", BizErrorCode.VALIDATION_ERROR);
        }
        return root;
    }


    
    public static String endpointKey(String method, String rawPath) {
        return method.trim().toUpperCase(Locale.ROOT) + " " + normalizePath(rawPath);
    }

    private ApiEndpoint readEndpoint(String method, String rawPath, Map<String, Object> op,
                                     List<ApiParameter> pathParams, Map<String, Object> root) {
        String path = normalizePath(rawPath);
        String key = method.toUpperCase(Locale.ROOT) + " " + path;
        List<ApiParameter> parameters = new ArrayList<>(
                readParameters(op.get("parameters"), root));
        parameters.addAll(pathParams);

        Map<String, Object> requestBody = asMap(op.get("requestBody"));
        Map<String, String> requestBodyInfo = readContentSchema(requestBody, root);

        Map<String, String> responseSchemas = new TreeMap<>();
        Map<String, Object> responses = asMap(op.get("responses"));
        if (responses != null) {
            for (Map.Entry<String, Object> re : responses.entrySet()) {
                Map<String, String> one = readContentSchema(asMap(re.getValue()), root);
                if (!one.isEmpty()) {
                    responseSchemas.put(re.getKey(), one.get("schema"));
                }
            }
        }

        String operationId = str(op.get("operationId"));
        String summary = str(op.get("summary"));
        String title = summary != null && !summary.isBlank() ? summary
                : (operationId != null && !operationId.isBlank() ? operationId : path);
        String description = str(op.get("description"));
        List<String> tags = readTags(op.get("tags"));

        boolean deprecated = Boolean.TRUE.equals(op.get("deprecated"));
        ApiEndpoint base = new ApiEndpoint(method.toUpperCase(Locale.ROOT), path, key,
                operationId, title, description, tags, List.copyOf(parameters),
                requestBodyInfo.get("contentType"), requestBodyInfo.get("schema"),
                Map.copyOf(responseSchemas), readSecurityRefs(op.get("security")),
                deprecated, null);
        return new ApiEndpoint(base.method(), base.path(), base.endpointKey(),
                base.operationId(), base.title(), base.description(), base.tags(),
                base.parameters(),
                base.requestContentType(), base.requestSchema(), base.responseSchemas(),
                base.securityRefs(), base.deprecated(), structureHash(base));
    }

    
    private static String structureHash(ApiEndpoint ep) {
        StringBuilder material = new StringBuilder(ep.endpointKey());
        for (ApiParameter p : ep.parameters()) {
            material.append("|p=").append(p.name()).append(':').append(p.location())
                    .append(':').append(p.required()).append(':').append(p.type());
        }
        material.append("|req=").append(ep.requestContentType())
                .append(':').append(ep.requestSchema());
        ep.responseSchemas().forEach((code, schema) ->
                material.append("|resp=").append(code).append(':').append(schema));
        material.append("|sec=").append(String.join(",", ep.securityRefs()));
        return DigestHex.sha256(material.toString());
    }

    // ---------- parameters / security ----------

    private static List<ApiParameter> readParameters(Object node, Map<String, Object> root) {
        List<ApiParameter> out = new ArrayList<>();
        if (!(node instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            Set<String> visited = new HashSet<>();
            Map<String, Object> param = derefMap(asMap(item), root, visited);
            if (param == null || param.get("name") == null) {
                continue;
            }
            Map<String, Object> schema = asMap(param.get("schema"));
            String type = schema == null ? "object"
                    : (schema.get("type") != null ? str(schema.get("type")) : refName(schema));
            String format = schema == null ? null : str(schema.get("format"));
            String example = schema == null ? null : str(schema.get("example"));
            out.add(new ApiParameter(str(param.get("name")), str(param.get("in")),
                    Boolean.TRUE.equals(param.get("required")), type, format, example));
        }
        return out;
    }

    private static List<String> readTags(Object node) {
        List<String> out = new ArrayList<>();
        if (!(node instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            String tag = str(item);
            if (tag != null && !tag.isBlank() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    private static List<String> readSecurityRefs(Object node) {
        List<String> out = new ArrayList<>();
        if (!(node instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            Map<String, Object> req = asMap(item);
            if (req != null) {
                req.keySet().stream().sorted().forEach(k -> {
                    if (!out.contains(k)) {
                        out.add(k);
                    }
                });
            }
        }
        return out;
    }

    // ---------- requestBody / responses content → schema ----------

    
    private static Map<String, String> readContentSchema(Map<String, Object> node,
                                                         Map<String, Object> root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null) {
            return out;
        }
        Map<String, Object> content = asMap(node.get("content"));
        if (content == null || content.isEmpty()) {
            return out;
        }
        String chosen = content.containsKey("application/json") ? "application/json"
                : content.keySet().iterator().next();
        Map<String, Object> media = asMap(content.get(chosen));
        Map<String, Object> schema = media == null ? null : asMap(media.get("schema"));
        if (schema != null && (schema.get("type") != null || schema.get("$ref") != null)) {
            out.put("contentType", chosen);
            out.put("schema", schema.get("type") != null
                    ? str(schema.get("type")) : refName(schema));
        }
        return out;
    }


    
    private static Map<String, Object> derefMap(Map<String, Object> node,
                                                Map<String, Object> root, Set<String> visited) {
        Map<String, Object> current = node;
        int depth = 0;
        while (current != null && current.get("$ref") instanceof String ref && depth < MAX_REF_DEPTH) {
            if (!visited.add(ref)) {
                return null;
            }
            current = lookupRef(ref, root);
            depth++;
        }
        return current;
    }

    private static Map<String, Object> lookupRef(String ref, Map<String, Object> root) {
        if (!ref.startsWith("#/")) {
            return null;
        }
        Object current = root;
        for (String segment : ref.substring(2).split("/")) {
            Map<String, Object> map = asMap(current);
            if (map == null) {
                return null;
            }
            current = map.get(segment.replace("~1", "/").replace("~0", "~"));
        }
        return asMap(current);
    }

 
    private static String refName(Map<String, Object> schema) {
        Object ref = schema.get("$ref");
        if (ref instanceof String s) {
            int idx = s.lastIndexOf('/');
            return idx >= 0 ? s.substring(idx + 1) : s;
        }
        return "object";
    }


    
    public static String normalizePath(String rawPath) {
        String path = rawPath == null ? "/" : rawPath.trim();
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : null;
    }

    private static String str(Object node) {
        return node == null ? null : String.valueOf(node);
    }
}
