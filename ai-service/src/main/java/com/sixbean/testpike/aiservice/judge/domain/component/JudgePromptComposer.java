package com.sixbean.testpike.aiservice.judge.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class JudgePromptComposer {

    
    @SuppressWarnings("unchecked")
    public List<String> dimensionNames(Map<String, Object> rubricSnapshot) {
        return dimensionMaps(rubricSnapshot).stream()
                .map(d -> String.valueOf(d.get("name")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dimensionMaps(Map<String, Object> rubricSnapshot) {
        Object dims = rubricSnapshot == null ? null : rubricSnapshot.get("dimensions");
        return dims instanceof List ? (List<Map<String, Object>>) dims : List.of();
    }

    
    public List<ChatMessage> compose(Map<String, Object> rubricSnapshot, String input,
                                     String output, List<String> referenceDocs) {
        StringBuilder user = new StringBuilder();
        user.append("请按以下评分维度对被测产出判分（分值 0~100）。\n\n评分维度：\n");
        for (Map<String, Object> d : dimensionMaps(rubricSnapshot)) {
            user.append("- ").append(d.get("name")).append("：").append(d.get("definition"));
            Object anchors = d.get("anchorExamples");
            if (anchors instanceof List<?> list && !list.isEmpty()) {
                user.append("（示例锚点：").append(list).append("）");
            }
            user.append('\n');
        }
        user.append("\n【样本需求】\n").append(nullSafe(input))
                .append("\n\n【被测产出】\n").append(nullSafe(output));
        if (referenceDocs != null && !referenceDocs.isEmpty()) {
            user.append("\n\n【参考件（可作引用锚）】\n").append(String.join("\n", referenceDocs));
        }
        user.append("\n\n只输出 JSON，不要其他文本。格式："
                + "{\"dimensions\":[{\"name\":\"维度名\",\"score\":85,\"reason\":\"判分理由\","
                + "\"anchor\":\"从被测产出或参考件逐字摘录的引用锚原文\"}]}。"
                + "anchor 必须逐字摘自【被测产出】或【参考件】，无逐字依据的维度禁止给分。");
        return List.of(
                new ChatMessage("system",
                        "你是严格的测试用例评审裁判。每个维度的给分必须给出逐字引用锚"
                                + "（anchor），引用必须真实出现在被评材料中；编造引用=无效判分。"),
                new ChatMessage("user", user.toString()));
    }

    private String nullSafe(String text) {
        return text == null ? "" : text;
    }
}
