package com.sixbean.testpike.aiservice.judge.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class JudgeLlmVerdictParser {

    private final ObjectMapper objectMapper;

    public JudgeLlmVerdictParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public JudgeVerdict parse(String content, List<String> dimensionNames, String evidenceText) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(content));
        } catch (Exception e) {
            throw new ServiceException("裁判回包不是合法 JSON，无法判分："
                    + truncate(content));
        }
        JsonNode dims = root.path("dimensions");
        if (!dims.isArray()) {
            throw new ServiceException("裁判回包缺少 dimensions 数组，无法判分："
                    + truncate(content));
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        List<String> orphanDimensions = new ArrayList<>();
        String evidence = evidenceText == null ? "" : evidenceText;
        for (JsonNode d : dims) {
            String name = d.path("name").asText(null);
            JsonNode scoreNode = d.path("score");
            String reason = d.path("reason").asText(null);
            if (name == null || name.isBlank() || !scoreNode.isNumber()
                    || reason == null || reason.isBlank()) {
                throw new ServiceException("裁判回包维度件缺 name/score/reason 必填字段，"
                        + "无法判分：维度=" + name + "，回包=" + truncate(content));
            }
            double score = scoreNode.asDouble();
            boolean orphan = score < 0 || score > 100
                    || !dimensionNames.contains(name)
                    || !anchorGrounded(d.path("anchor").asText(""), evidence);
            if (orphan) {
                orphanDimensions.add(name);
                continue;
            }
            scores.put(name, score);
            reasons.put(name, reason);
        }
        return new JudgeVerdict(scores, reasons, orphanDimensions);
    }

    
    private boolean anchorGrounded(String anchor, String evidence) {
        return anchor != null && !anchor.isBlank() && evidence.contains(anchor);
    }

    
    private String stripFence(String content) {
        if (content == null) {
            throw new ServiceException("裁判回包为空，无法判分");
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first > 0 && last > first) {
                return text.substring(first + 1, last).trim();
            }
        }
        return text;
    }

    private String truncate(String content) {
        if (content == null) {
            return "null";
        }
        return content.length() <= 200 ? content : content.substring(0, 200) + "...";
    }
}
