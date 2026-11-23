package com.sixbean.testpike.aiservice.nlstep.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.nlstep.api.dto.NlStepRequest;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class NlStepPromptTemplate {

    
    public static final String SYSTEM = """
            你是移动端自动化测试的步骤生成助手。把用户的一句中文操作描述转成 app.* 抽象动作步骤序列。
            只允许使用「本次允许动词清单」中的动词（清单由目标引擎能力决定，清单外的动词一律不可用）。
            每个步骤是一个 JSON 对象，action 字段必须是 app. 前缀加清单内动词（如 app.tap）；
            目标与值写在 target/value 等字段里（target 可以是字符串或定位器对象），只使用描述中出现过的
            事实，禁止编造描述里没有的文案、坐标或账号。
            只输出一个 JSON 对象，不要输出其他文字，格式：
            {"steps":[{"action":"app.tap","target":{"text":"登录"}},{"action":"app.input","value":"admin"}]}
            """;

    private final ObjectMapper objectMapper;

    public NlStepPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Assemble the dialogue for one step-generation call. The system turn is
     * supplied by the caller (the behaviour-kernel head from the shared L0
     * assembly — published template row or this class's {@code SYSTEM}
     * contract as the conservative fallback, always extended with the kernel
     * invariant); this piece only shapes the messages.
     */
    public List<ChatMessage> messages(NlStepRequest request, String systemHead) {
        StringBuilder user = new StringBuilder("操作描述：")
                .append(request.description().trim())
                .append("\n目标引擎：").append(request.engineCode())
                .append("\n本次允许动词清单（action 只能从这些里选，全名带 app. 前缀）：")
                .append(renderVerbs(request.allowedVerbs()))
                .append("\n请按契约输出步骤 JSON。");
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user.toString()));
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
