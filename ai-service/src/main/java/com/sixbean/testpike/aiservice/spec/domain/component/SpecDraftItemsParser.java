package com.sixbean.testpike.aiservice.spec.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Tolerance: the LLM output goes through fence/markdown/prose stripping
 * before parsing; a missing or non-array items key = IllegalArgumentException
 * (upstream fails in human words); a legitimate empty items array = empty
 * result for that chunk ( residual-1 — one interface-free chunk must not
 * fail the run; the anti-overwrite guard for an entirely empty document
 * lives in SpecDraftExtractServiceImpl).
 * requestContentType/paramsSkeletonJson/bodySkeleton/assertionsJson/
 */
public class SpecDraftItemsParser {

    private final ObjectMapper objectMapper;

    public SpecDraftItemsParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
 * Parses one chunk answer into draft item maps : a
 * legitimate {@code {"items":[]}} answer — the extraction contract
 * explicitly allows it for chunks without any interface — returns an
 * empty list so one interface-free chunk is skipped-and-counted-zero
 * instead of failing the whole run into retries. Malformed shapes (items
 * key missing, items not an array, invalid JSON) still raise
 * IllegalArgumentException; the document-level anti-overwrite guard (all
 * chunks empty = human-readable refusal, no write-back) lives in
 * SpecDraftExtractServiceImpl.
 */
    public SpecDraftParseOutcome parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                throw new IllegalArgumentException("抽取结果缺少接口条目清单(items)");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            int rejectedCount = 0;
            for (JsonNode item : items) {
                String path = text(item, "path");
                if (path == null || path.isBlank()) {
                    // Entry-level tolerance: the path contract stays enforced
                    // (a path-less answer never becomes a draft) but one
                    // malformed entry must not kill the whole document run
                    // into retries and dead letters — drop it and book the
                    // count for the caller's honest gap reporting.
                    rejectedCount++;
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("method", text(item, "method"));
                entry.put("path", path.trim());
                entry.put("title", text(item, "title"));
                entry.put("description", text(item, "description"));
                entry.put("tags", textList(item, "tags"));
                entry.put("requestContentType", text(item, "requestContentType"));
                entry.put("paramsSkeletonJson", rawJson(item, "paramsSkeletonJson"));
                entry.put("bodySkeleton", rawJson(item, "bodySkeleton"));
                entry.put("assertionsJson", rawJson(item, "assertionsJson"));
                entry.put("negativeAssertionsJson", rawJson(item, "negativeAssertionsJson"));
                entry.put("securityRefs", textList(item, "securityRefs"));
                entry.put("authHintJson", rawJson(item, "authHintJson"));
                entry.put("sourceAnchor", text(item, "sourceAnchor"));
                result.add(entry);
            }
            return new SpecDraftParseOutcome(result, rejectedCount);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 输出不是合法 JSON: " + e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static List<String> textList(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        value.forEach(el -> list.add(el.asText()));
        return list;
    }

    
    private static String rawJson(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }
}
