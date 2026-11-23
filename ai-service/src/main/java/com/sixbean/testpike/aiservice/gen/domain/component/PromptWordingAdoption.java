package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


@Component
public class PromptWordingAdoption {

    private static final Logger log = LoggerFactory.getLogger(PromptWordingAdoption.class);

    
    static final String SECTION_HEADER = "期望表述约束（评审采纳，必须遵循）：\n";

    
    static final int MAX_PAIRS = 5;

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;

    public PromptWordingAdoption(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
    }

    
    public String wordingSegment(long projectId) {
        List<CoreGateway.WordingPair> pairs;
        try {
            pairs = coreGateway.wordingPairs(projectId, MAX_PAIRS);
        } catch (Exception e) {
            log.warn("D5 措辞采纳对读路径异常 projectId={}（降级空段，禁炸生成）: {}",
                    projectId, e.getMessage());
            return "";
        }
        if (pairs == null || pairs.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (CoreGateway.WordingPair pair : pairs) {
            if (pair == null) {
                continue;
            }
            appendChangeLines(pair.diffChangesJson(), lines);
            if (pair.remark() != null && !pair.remark().isBlank()) {
                lines.add("- 评审表述反馈：" + pair.remark().trim());
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(SECTION_HEADER);
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    
    private void appendChangeLines(String diffChangesJson, List<String> lines) {
        if (diffChangesJson == null || diffChangesJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(diffChangesJson);
            if (!root.isArray()) {
                return;
            }
            for (JsonNode change : root) {
                JsonNode label = change.get("label");
                JsonNode before = change.get("before");
                JsonNode after = change.get("after");
                if (label == null || after == null) {
                    continue;
                }
                lines.add("- 字段修正「" + label.asText() + "」应为：" + after.asText()
                        + (before == null || before.isNull() ? ""
                                : "（原：" + before.asText() + "）"));
            }
        } catch (Exception e) {
            log.warn("D5 措辞差异 changes 解析失败（跳过修正行，保留反馈半句）: {}", e.getMessage());
        }
    }
}
