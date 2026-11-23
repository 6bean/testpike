package com.sixbean.testpike.aiservice.locate.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.locate.api.dto.VisualLocateRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


@Component
public class VisualLocatePromptTemplate {

    
    public static final String SYSTEM = """
            你是移动端测试执行的视觉定位助手。给定失败步骤的目标元素描述、失败现场截图\
（截图见 image_url 段）与无障碍树候选清单，请在截图上找出该目标元素的点击中心点。
            像素坐标以截图左上角为原点，x 向右、y 向下，单位为像素。
            找不到目标时不要瞎猜：把 confidence 置为 0 并在 label 说明原因。
            只输出一个 JSON 对象，不要输出其他文字，格式：\
{"x":数字,"y":数字,"label":"命中的元素名称或说明","confidence":0到1之间的小数}
            """;

    private final ObjectMapper objectMapper;

    public VisualLocatePromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    
    public List<ChatMessage> messages(VisualLocateRequest request, String imageUrl, String systemHead) {
        StringBuilder user = new StringBuilder("失败步骤目标描述：")
                .append(describeTarget(request.step(), request.action()))
                .append("\n无障碍树候选清单（可能为空，仅供参考勿照抄）：")
                .append(renderCandidates(request.a11yCandidates()))
                .append("\n请按契约输出该目标的点击点位 JSON。");
        ChatMessage userMsg = imageUrl == null || imageUrl.isBlank()
                ? new ChatMessage("user", user.toString())
                : ChatMessage.userVision(user.toString(), imageUrl);
        return List.of(new ChatMessage("system", systemHead), userMsg);
    }

    
    private String describeTarget(Map<String, Object> step, String action) {
        if (step == null) {
            return action == null || action.isBlank() ? "（无步定义）" : action;
        }
        StringBuilder text = new StringBuilder();
        Object selector = step.get("selector");
        if (selector instanceof Map<?, ?> map && !map.isEmpty()) {
            text.append("定位器=").append(toJson(map));
        }
        for (String key : new String[]{"text", "value", "description"}) {
            Object val = step.get(key);
            if (val != null && !String.valueOf(val).isBlank()) {
                text.append(text.isEmpty() ? "" : "；").append(key).append('=' ).append(val);
            }
        }
        if (text.isEmpty()) {
            text.append("（步定义无定位线索，请按动作语义与截图判断）");
        }
        return (action == null || action.isBlank() ? "" : action + " ") + text;
    }

    private String renderCandidates(List<Map<String, Object>> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "（无）";
        }
        return toJson(candidates);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
