package com.sixbean.testpike.core.spec.domain.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;


public class SpecDraftGrounding {

    public static final String CONFIDENCE_HIGH = "high";
    public static final String CONFIDENCE_LOW = "low";

    
    public List<ApiSpecDraftItem> apply(List<ApiSpecDraftItem> rawItems,
                                        List<String> chunks) {
        String corpus = normalize(joinChunks(chunks));
        Map<String, ApiSpecDraftItem> merged = new LinkedHashMap<>();
        for (ApiSpecDraftItem item : rawItems) {
            boolean grounded = isGrounded(item.sourceAnchor(), corpus);
            String confidence = grounded ? CONFIDENCE_HIGH : CONFIDENCE_LOW;
            ApiSpecDraftItem judged = withVerdict(item, confidence);
            String key = mergeKey(item);
            ApiSpecDraftItem existing = merged.get(key);
            if (existing == null || CONFIDENCE_HIGH.equals(confidence)) {
                merged.put(key, judged);
            }
        }
        return new ArrayList<>(merged.values());
    }

    
    private String mergeKey(ApiSpecDraftItem item) {
        String key = item.endpointKey();
        if (key != null && !key.isBlank()) {
            return normalize(key);
        }
        String method = item.method() == null ? "" : item.method().trim().toUpperCase(Locale.ROOT);
        String path = item.path() == null ? "" : item.path().replaceAll("\\s+", "");
        return method + " " + path;
    }

    
    private boolean isGrounded(String anchor, String corpus) {
        if (anchor == null) {
            return false;
        }
        String needle = normalize(anchor);
        return !needle.isEmpty() && corpus.contains(needle);
    }

    private ApiSpecDraftItem withVerdict(ApiSpecDraftItem item, String confidence) {
        return new ApiSpecDraftItem(item.endpointKey(), item.method(), item.path(),
                item.title(), item.description(), item.tags(),
                item.paramsSkeletonJson(), item.requestContentType(), item.bodySkeleton(),
                item.assertionsJson(), item.negativeAssertionsJson(), item.negativeSelected(),
                item.securityRefs(), item.authHintJson(), "B", confidence, item.sourceAnchor());
    }

    private String joinChunks(List<String> chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk != null) {
                sb.append(chunk).append('\n');
            }
        }
        return sb.toString();
    }

    
    private String normalize(String text) {
        return text.replaceAll("\\s+", "");
    }
}
