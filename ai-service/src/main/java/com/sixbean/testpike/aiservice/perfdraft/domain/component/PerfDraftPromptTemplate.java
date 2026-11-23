package com.sixbean.testpike.aiservice.perfdraft.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.perfdraft.api.dto.PerfDraftRequest;
import com.sixbean.testpike.common.util.NonUiActionContract;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt template for AI load-test scenario drafting (B-11).
 * <p>Output contract = strict JSON {@code {"steps":[{"action":"http.request",
 * "method":"GET","url":"..."}]}} — the only action the perf engines compile
 * (core PerfStepActionGate narrows the DSL to http.request; this leg states
 * that contract instead of re-judging it). The HTTP method vocabulary is
 * rendered from NonUiActionContract.HTTP_METHODS, the same single source the
 * core save/compile gates read — a hand-copied list here would drift (red
 * line 6).
 * <p>The reply is a draft for the wizard: the model must invent no URLs or
 * payload values beyond what the user sentence contains, and nothing is
 * persisted until a human confirms it in the wizard form.
 */
@Component
public class PerfDraftPromptTemplate {

    /** Contract anchor for the conservative fallback head (the shared L0
     *  assembly replaces it with the published template row when present). */
    public static final String SYSTEM = """
            你是接口压测的场景设计助手。把用户的一句中文压测意图转成压测步骤序列。
            每一步是一个 JSON 对象，action 只能是 "http.request"（压力模板只编译这一个动作）；
            method 只能取「允许的方法清单」中的 HTTP 动词（缺省 GET），url 必填且不得为空；
            url/参数只允许使用描述中出现过的事实，禁止编造描述里没有的地址、账号或报文内容。
            只输出一个 JSON 对象，不要输出其他文字，格式：
            {"steps":[{"action":"http.request","method":"GET","url":"/api/products/1"},
            {"action":"http.request","method":"POST","url":"/api/orders"}]}
            """;

    private final ObjectMapper objectMapper;

    public PerfDraftPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Assemble the dialogue for one drafting call. The system turn comes from
     * the caller's behaviour-kernel head (shared L0 assembly: published
     * template row, or this class's {@code SYSTEM} contract as the
     * conservative fallback); this piece only shapes the messages.
     */
    public List<ChatMessage> messages(PerfDraftRequest request, String systemHead) {
        StringBuilder user = new StringBuilder("压测意图描述：")
                .append(request.description().trim())
                .append("\n允许的方法清单（method 只能从这些 HTTP 动词里选）：")
                .append(renderMethods())
                .append("\n请按契约输出步骤 JSON。");
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user.toString()));
    }

    /** Method vocabulary rendered from the shared contract (render failure degrades, never breaks). */
    private String renderMethods() {
        try {
            return objectMapper.writeValueAsString(List.copyOf(NonUiActionContract.HTTP_METHODS));
        } catch (JsonProcessingException e) {
            return String.valueOf(NonUiActionContract.HTTP_METHODS);
        }
    }
}
