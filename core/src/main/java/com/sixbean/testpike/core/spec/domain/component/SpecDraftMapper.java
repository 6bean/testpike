package com.sixbean.testpike.core.spec.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.common.domain.component.FirstSuccessCode;
import com.sixbean.testpike.core.common.domain.component.FormatGenSample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class SpecDraftMapper {

    
    private static final String CHANNEL_DETERMINISTIC = "A";
    private static final String CONFIDENCE_DETERMINISTIC = "deterministic";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    
    public List<ApiSpecDraftItem> fromParsedSpec(ParsedApiSpec parsed, int durationThresholdMs,
                                                 String bodyCheckPath, String bodyCheckExpect) {
        List<ApiSpecDraftItem> out = new ArrayList<>();
        for (ApiEndpoint ep : parsed.endpoints()) {
            out.add(toDraft(ep, durationThresholdMs, bodyCheckPath, bodyCheckExpect));
        }
        return out;
    }

    private ApiSpecDraftItem toDraft(ApiEndpoint ep, int durationThresholdMs,
                                     String bodyCheckPath, String bodyCheckExpect) {
        int successCode = firstSuccessCode(ep);
        List<Map<String, Object>> assertions = new ArrayList<>();
        assertions.add(statusRow(successCode));
        assertions.add(durationRow(durationThresholdMs));
        Map<String, Object> bodyRow = bodyConventionRow(bodyCheckPath, bodyCheckExpect);
        if (bodyRow != null) {
            assertions.add(bodyRow);
        }
        List<Map<String, Object>> negatives = new ArrayList<>();
        for (String code : ep.responseSchemas().keySet()) {
            if (code.startsWith("4") && code.length() == 3) {
                negatives.add(statusRow(Integer.parseInt(code)));
            }
        }
        boolean hasBody = ep.requestContentType() != null && !ep.requestContentType().isBlank();
        return new ApiSpecDraftItem(
                ep.endpointKey(), ep.method(), ep.path(), ep.title(), ep.description(),
                ep.tags() == null ? List.of() : ep.tags(),
                toJson(paramRows(ep.parameters())),
                ep.requestContentType(),
                hasBody ? "{}" : "",
                toJson(assertions),
                negatives.isEmpty() ? "[]" : toJson(negatives),
                false,
                ep.securityRefs(),
                authHint(ep.securityRefs()),
                CHANNEL_DETERMINISTIC, CONFIDENCE_DETERMINISTIC, null);
    }

    /**
     * Parameter skeleton rows: name/location/required/type + format/example.
     * example prefers the declared schema value; otherwise the shared
     * format→genkit table fills a deterministic sample (single source with
     * the mock stub mapper — FormatGenSample). Seed = name+location stable
     * digest — re-running the preview yields the same value (channel-A floor,
     * never system randomness).
     */
    private List<Map<String, Object>> paramRows(List<ApiParameter> parameters) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiParameter p : parameters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.name());
            row.put("location", p.location());
            row.put("required", p.required());
            row.put("type", p.type());
            if (p.format() != null) {
                row.put("format", p.format());
            }
            String example = p.example() != null
                    ? p.example()
                    : FormatGenSample.sample(p.format(), p.name() + "|" + p.location());
            if (example != null) {
                row.put("example", example);
            }
            out.add(row);
        }
        return out;
    }

    /** Lowest declared 2xx = the success-code assertion; shared pick, 200 fallback. */
    private static int firstSuccessCode(ApiEndpoint ep) {
        return FirstSuccessCode.pick(ep.responseSchemas());
    }

    private static Map<String, Object> statusRow(int code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "status");
        row.put("expect", code);
        return row;
    }

    private static Map<String, Object> durationRow(int thresholdMs) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "duration");
        row.put("op", "range");
        row.put("expect", List.of(0, thresholdMs));
        return row;
    }

    private static Map<String, Object> bodyConventionRow(String path, String expect) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "body");
        row.put("path", path);
        row.put("op", "exact");
        row.put("expect", expect);
        return row;
    }

    
    private static String authHint(List<String> securityRefs) {
        if (securityRefs == null || securityRefs.isEmpty()) {
            return "{}";
        }
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("type", "environment-credential");
        hint.put("credentialRefs", securityRefs);
        return toJsonStatic(hint);
    }

    private String toJson(Object value) {
        return toJsonStatic(value);
    }

    private static String toJsonStatic(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("预览映射序列化失败", e);
        }
    }
}
