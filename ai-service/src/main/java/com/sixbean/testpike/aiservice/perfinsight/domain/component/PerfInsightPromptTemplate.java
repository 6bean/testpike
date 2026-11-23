package com.sixbean.testpike.aiservice.perfinsight.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.perfinsight.api.dto.PerfInsightRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt template for the AI load-test run-insight read-out (B-11 .
 * <p>Output contract = strict JSON {@code {"insight":"..."}} with a short
 * plain-word paragraph. The numbers on the card are core's attribution single
 * source: the model only explains them — it must invent no metric, no threshold
 * and no fix that is absent from the facts block, so a hallucinated cause can
 * never masquerade as a measured one on the report page.
 * <p>The read-out is transient (shown on the report, re-generable): nothing is
 * persisted from this leg and it never rewrites the pass/fail verdict.
 */
@Component
public class PerfInsightPromptTemplate {

    /** Built-in conservative contract (system turn; the upgraded head replaces
     * this text when a published template row is in place, see
     * PerfInsightServiceImpl#directivesHead). */
    public static final String SYSTEM = """
            你是接口压测结果的归因解读助手。用户会给出一段压测归因事实（结论、分位耗时、错误率、吞吐、错误分类计数、瓶颈线索）。
            你的任务只用简体中文把这些事实解释成一段平实易懂的说明：说清压力表现如何、主要问题集中在哪里、下一步值得排查什么。
            数字、门禁阈值、错误分类计数一律只能原样引用事实中出现过的内容，禁止编造或换算事实里没有的指标；
            结论通过与否以事实给出的结论为准，禁止改写判定。
            只输出一个 JSON 对象，不要输出其他文字，格式：
            {"insight":"一段不超过200字的中文解读"}
            """;

    /**
     * Assemble the dialogue (system head + user facts block verbatim).
     * {@code systemHead} = caller-assembled behaviour-kernel head (shared L0
     * assembly); the built-in {@link #SYSTEM} contract is its conservative
     * fallback, never a second vocabulary owned by this component.
     */
    public List<ChatMessage> messages(PerfInsightRequest request, String systemHead) {
        String user = "压测归因事实：\n" + request.factsBlock().trim()
                + "\n请按契约输出解读 JSON。";
        return List.of(new ChatMessage("system", systemHead), new ChatMessage("user", user));
    }
}
