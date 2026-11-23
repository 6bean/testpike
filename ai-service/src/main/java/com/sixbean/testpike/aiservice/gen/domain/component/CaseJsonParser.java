package com.sixbean.testpike.aiservice.gen.domain.component;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedStep;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.ArrayList;
import java.util.List;


public class CaseJsonParser {

    private final ObjectMapper objectMapper;

    public CaseJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GeneratedCase parse(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 输出不是合法 JSON: " + e.getMessage());
        }
        // Required-shape gate: one rule source for the gaps (parse and the
        // targeted repair prompt read the same list; the legacy throw order
        // and messages stay byte-identical — the ring feedback keys on them).
        List<String> gaps = gapsIn(root);
        if (!gaps.isEmpty()) {
            throw new IllegalArgumentException(gaps.get(0));
        }
        String title = text(root, "title");
        String precondition = text(root, "precondition");
        String expectedResult = text(root, "expectedResult");
        List<GeneratedStep> steps = new ArrayList<>();
        int no = 1;
        for (JsonNode stepNode : root.path("steps")) {
            steps.add(new GeneratedStep(no++, text(stepNode, "description"),
                    stepNode.path("isAssert").asInt(0)));
        }
        int priority = root.path("priority").isMissingNode() ? 1 : root.path("priority").asInt(1);
        int confidence = root.path("confidence").isMissingNode() ? 2 : root.path("confidence").asInt(2);
        String endType = root.path("endType").isMissingNode() ? "web" : root.path("endType").asText("web");
        return new GeneratedCase(title, precondition, expectedResult, steps, priority, confidence, endType);
    }

    /**
     * Missing required keys of a draft, in the parse throw order — the
     * single rule source behind both {@link #parse} and the targeted repair
     * prompt (never a second list of required fields).
     */
    public List<String> collectMissingFields(String content) {
        JsonNode root;
        try {
            root = objectMapper.readTree(JsonOutputExtractor.extractJson(content));
        } catch (Exception e) {
            return List.of("LLM 输出不是合法 JSON: " + e.getMessage());
        }
        return gapsIn(root);
    }

    private List<String> gapsIn(JsonNode root) {
        List<String> gaps = new ArrayList<>();
        String title = text(root, "title");
        if (title == null || title.isBlank()) {
            gaps.add("生成结果缺少测试目标(title)");
        }
        String expectedResult = text(root, "expectedResult");
        if (expectedResult == null || expectedResult.isBlank()) {
            gaps.add("生成结果缺少预期结果(expectedResult)");
        }
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            gaps.add("生成结果缺少操作步骤(steps)");
        } else {
            for (JsonNode stepNode : stepsNode) {
                String desc = text(stepNode, "description");
                if (desc == null || desc.isBlank()) {
                    gaps.add("生成步骤缺少描述(description)");
                    break;
                }
            }
        }
        return gaps;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
