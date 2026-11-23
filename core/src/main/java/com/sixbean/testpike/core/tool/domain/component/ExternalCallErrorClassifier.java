package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.common.component.TokenTextMatcher;
import org.springframework.stereotype.Component;

/**
 * Single-source classifier turning one recorded external-call failure text
 * ({@code tp_tool_invocation.error}, the plain-language message the /mcp
 * gateway captured) into an {@link ExternalCallErrorClass} (GA5, ).
 * <p>Rule order encodes specificity, not frequency: the idempotent-conflict
 * sentence also reads like a refusal and the permission sentence ends with
 * a denial word, so both are matched before the broader refusal vocabulary.
 * <p>Vocabulary is harvested from the real throw sites of the external
 * chain (IdempotentGuard/IdempotentAspect conflict message, McpServerHandler
 * TOOL_NOT_FOUND and ToolAccessGate FORBIDDEN wording, the R32 switch-off
 * pointing sentence, quota/rate caps, spent confirm handles) — matched
 * case-insensitively over the whole text. Text outside the vocabulary
 * stays UNCLASSIFIED: the classifier never guesses, and the honest tail is
 * what makes the distribution trustworthy for the evolution loop.
 * <p>Distinct from {@code DeadLetterFailureClassifier}: that one reasons
 * over MQ dead-letter causes (payload damage, routing config); this one
 * reasons over caller-facing gate answers. Different ledger, different
 * actionability, so they share no enum.
 */
@Component
public class ExternalCallErrorClassifier {

    private static final String[] IDEMPOTENT_CONFLICT_TOKENS = {
            "重复请求", "重复提交", "幂等", "idempotency-key", "duplicate request"
    };

    private static final String[] FORBIDDEN_TOKENS = {
            "无权", "越权", "forbidden"
    };

    private static final String[] TIMEOUT_TOKENS = {
            "超时", "timed out", "timeout"
    };

    private static final String[] REFUSED_TOKENS = {
            "未开启", "已达上限", "过于频繁", "确认码", "暂不可用"
    };

    /**
     * Classify one recorded failure text. Null/blank text is UNCLASSIFIED —
     * an unrecorded reason must not be invented.
     */
    public ExternalCallErrorClass classify(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return ExternalCallErrorClass.UNCLASSIFIED;
        }
        String text = errorText.toLowerCase();
        if (TokenTextMatcher.containsAny(text, IDEMPOTENT_CONFLICT_TOKENS)) {
            return ExternalCallErrorClass.IDEMPOTENT_CONFLICT;
        }
        if (TokenTextMatcher.containsAny(text, FORBIDDEN_TOKENS)) {
            return ExternalCallErrorClass.FORBIDDEN;
        }
        if (TokenTextMatcher.containsAny(text, TIMEOUT_TOKENS)) {
            return ExternalCallErrorClass.TIMEOUT;
        }
        if (TokenTextMatcher.containsAny(text, REFUSED_TOKENS)) {
            return ExternalCallErrorClass.REFUSED;
        }
        return ExternalCallErrorClass.UNCLASSIFIED;
    }

}
