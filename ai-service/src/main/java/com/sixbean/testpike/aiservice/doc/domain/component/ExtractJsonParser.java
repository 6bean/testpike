package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.common.util.JsonOutputExtractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class ExtractJsonParser {

    private final ObjectMapper objectMapper;

    public ExtractJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<Map<String, Object>> parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
            JsonNode items = root.path("requirements");
            if (!items.isArray() || items.isEmpty()) {
                throw new IllegalArgumentException("提取结果缺少需求点清单(requirements)");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode item : items) {
                String title = text(item, "title");
                if (title == null || title.isBlank()) {
                    throw new IllegalArgumentException("提取条目缺少标题(title)");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("title", title.trim());
                entry.put("acceptanceCriteria", text(item, "acceptanceCriteria"));
                entry.put("chapter", text(item, "chapter"));
                entry.put("confidence", item.path("confidence").isNumber()
                        ? item.path("confidence").asDouble() : null);
                // AP-4(3) fifth key = verbatim source excerpt (the
                // evidence anchor the preview jumps back to); absent/blank = null,
                // never a regeneration failure (older models omit it).
                String evidence = text(item, "evidence");
                entry.put("evidence", evidence == null || evidence.isBlank() ? null : evidence.trim());
                result.add(entry);
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 输出不是合法 JSON: " + e.getMessage());
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
