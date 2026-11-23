package com.sixbean.testpike.core.deadletter.domain.component;

import com.sixbean.testpike.common.exception.EntityGoneSignals;
import com.sixbean.testpike.core.common.component.TokenTextMatcher;
import org.springframework.stereotype.Component;

/**
 * Single-source classifier turning a dead-lettered message's cause-chain text
 * (plus the broker's x-death reason when present) into a
 * <p>Rule order encodes actionability, not frequency: entity-gone is checked
 * first because its misclassification would let the auto-replay loop burn retry
 * quota on messages that can never succeed; deserialization damage is checked
 * before transport noise because a corrupted payload dead-lettered with a
 * wrapped conversion failure is a data defect, not a provider outage.
 * <p>Vocabulary is harvested from the real LEFE "Caused by" distribution of the
 * tick live log and the ServiceException message grep of core/ai-service
 * (2026-11-06 counts in the RED test's javadoc) — patterns are matched
 * case-insensitively over the whole chain text, the "plain-language prefix"
 * requirement of the adjudication refers to these human-readable messages.
 */
@Component
public class DeadLetterFailureClassifier {

    /** Entity-gone vocabulary lives in one place (common EntityGoneSignals) shared
     *  with the consume-side noise reduction — no second token list here (R40). */

    private static final String[] CONFIG_MISSING_TOKENS = {
            "未配置", "配置缺失", "无清单配置", "not configured", "no configuration"
    };

    private static final String[] DESERIALIZATION_TOKENS = {
            "messageconversionexception", "cannot deserialize", "jsonparseexception",
            "mismatchedinputexception", "jsonprocessingexception", "反序列化"
    };

    private static final String[] LLM_TIMEOUT_TOKENS = {
            "sockettimeoutexception", "timeoutexception", "超时", "timed out"
    };

    private static final String[] PROVIDER_TRANSIENT_TOKENS = {
            "connectexception", "connection refused", "connection reset",
            "unknownhostexception", "closedchannelexception", "broken pipe",
            "i/o error on", "nohttpresponse"
    };

    /**
     * Classify one dead-letter row. {@code causeChainText} is the plain-language
     * exception chain captured for the rejection (may be null/blank when the
     * broker dead-lettered without a listener failure, e.g. unroutable publish
     * or TTL expiry); {@code xDeathReason} is the last x-death entry's reason
     * when available ("rejected"/"expired"/"publish"/"delivery_limit").
     * <p>Blank text + {@code publish} reason = unroutable message = a routing/
     * exchange configuration defect → CONFIG_MISSING. Everything else without
     * matching tokens stays UNKNOWN — the classifier never guesses.
     */
    public DeadLetterFailureClass classify(String causeChainText, String xDeathReason) {
        String text = causeChainText == null ? "" : causeChainText.toLowerCase();
        if (!text.isBlank()) {
            if (EntityGoneSignals.matchesText(text)) {
                return DeadLetterFailureClass.ENTITY_GONE;
            }
            if (TokenTextMatcher.containsAny(text, DESERIALIZATION_TOKENS)) {
                return DeadLetterFailureClass.DESERIALIZATION_FAIL;
            }
            if (TokenTextMatcher.containsAny(text, CONFIG_MISSING_TOKENS)) {
                return DeadLetterFailureClass.CONFIG_MISSING;
            }
            if (TokenTextMatcher.containsAny(text, LLM_TIMEOUT_TOKENS)) {
                return DeadLetterFailureClass.LLM_TIMEOUT;
            }
            if (TokenTextMatcher.containsAny(text, PROVIDER_TRANSIENT_TOKENS)) {
                return DeadLetterFailureClass.PROVIDER_TRANSIENT;
            }
        }
        // No capturable listener failure text: only the broker reason remains.
        if ("publish".equalsIgnoreCase(xDeathReason)) {
            return DeadLetterFailureClass.CONFIG_MISSING;
        }
        return DeadLetterFailureClass.UNKNOWN;
    }

}
