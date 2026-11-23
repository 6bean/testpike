package com.sixbean.testpike.aiservice.recordflow.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.recordflow.api.dto.RecordFlowRequest;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class RecordFlowPromptTemplate {

    
    public static final String SYSTEM = """
            你是移动端自动化测试的录制反推助手。把一次真机操作录制的事件时间序还原成 app.* 抽象动作步骤序列。
            只允许使用「本次允许动词清单」中的动词（清单由目标引擎能力决定，清单外的动词一律不可用）。
            每个步骤是一个 JSON 对象，action 字段必须是 app. 前缀加清单内动词（如 app.tap）；
            目标必须写成定位器对象（如 {"text":"登录"}、{"resourceId":"com.demo:id/user"}、{"x":540,"y":1200}），
            不允许字符串目标；输入值放 value 字段（字符串），只使用事件中出现过的
            事实（坐标/文本/键语义），禁止编造事件里没有的文案、坐标或账号。
            事件里的连续输入文本合并为一个 app.input 步；返回主屏/返回键等系统事件对应 app.back 等语义动词。
            只输出一个 JSON 对象，不要输出其他文字，格式：
            {"steps":[{"action":"app.tap","target":{"x":540,"y":1200}},{"action":"app.input","value":"admin"}]}
            """;

    private final ObjectMapper objectMapper;

    public RecordFlowPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Assemble the dialogue for one reverse-engineering call. The system turn
     * is the caller's behaviour-kernel head from the shared L0 assembly
     * (published template row or this class's {@code SYSTEM} contract as the
     * conservative fallback); this piece only shapes the messages.
     */
    public List<ChatMessage> messages(RecordFlowRequest request, String systemHead) {
        StringBuilder user = new StringBuilder("采集档位：")
                .append(request.eventSource())
                .append("\n目标引擎：").append(request.engineCode())
                .append("\n录制事件时间序（JSON 数组，按时间升序）：")
                .append(renderEvents(request.events()))
                .append("\n本次允许动词清单（action 只能从这些里选，全名带 app. 前缀）：")
                .append(renderVerbs(request.allowedVerbs()))
                .append("\n请按契约输出步骤 JSON。");
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user.toString()));
    }

    
    private String renderEvents(List<?> events) {
        if (events == null || events.isEmpty()) {
            return "（空）";
        }
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            return String.valueOf(events);
        }
    }

    
    private String renderVerbs(List<String> verbs) {
        if (verbs == null || verbs.isEmpty()) {
            return "（空）";
        }
        try {
            return objectMapper.writeValueAsString(verbs);
        } catch (JsonProcessingException e) {
            return String.valueOf(verbs);
        }
    }
}
