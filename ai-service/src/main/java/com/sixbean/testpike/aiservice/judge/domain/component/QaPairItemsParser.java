package com.sixbean.testpike.aiservice.judge.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class QaPairItemsParser {

    private final ObjectMapper objectMapper;

    public QaPairItemsParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<Map<String, Object>> parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                throw new IllegalArgumentException("造题结果缺少问答条目清单(items)");
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode item : items) {
                String question = text(item, "question");
                if (question == null || question.isBlank()) {
                    throw new IllegalArgumentException("造题条目缺少问题(question)");
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("question", question.trim());
                entry.put("answer", text(item, "answer"));
                entry.put("sourceAnchor", text(item, "sourceAnchor"));
                result.add(entry);
            }
            return result;
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
}
