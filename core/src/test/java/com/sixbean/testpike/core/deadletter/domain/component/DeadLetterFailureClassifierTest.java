package com.sixbean.testpike.core.deadletter.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * every consumer of the dead-letter ledger — the ops page, the replay gate and
 * the auto-replay loop — must resolve the same class from the same inputs, so
 * the classification rules live here and nowhere else.
 * <p>Inputs are the plain-language cause-chain text captured at rejection time
 * plus the x-death reason when the broker provides one. Vocabulary below is
 * harvested from the real LEFE "Caused by" distribution in the tick live log
 * (entity-gone family ≈70%, embedding config ≈112, no-chunk ≈44, provider
 * transient/timeout sporadic) — not invented.
 */
class DeadLetterFailureClassifierTest {

    private final DeadLetterFailureClassifier classifier = new DeadLetterFailureClassifier();

    @Test
    void classifiesServiceExceptionEntityGoneMessages() {
        // Real shapes seen in core/ai-service messages (grep counts 2026-11-06):
        assertEquals(DeadLetterFailureClass.ENTITY_GONE,
                classifier.classify("com.sixbean.testpike.common.exception.ServiceException: 文档不存在（或已删除）", null));
        assertEquals(DeadLetterFailureClass.ENTITY_GONE,
                classifier.classify("ServiceException: 项目不存在（id=42）", null));
        assertEquals(DeadLetterFailureClass.ENTITY_GONE,
                classifier.classify("ServiceException: 用例不存在或已被删除", null));
        assertEquals(DeadLetterFailureClass.ENTITY_GONE,
                classifier.classify("ServiceException: AI 草稿任务不存在或已被删除", null));
    }

    @Test
    void missingEmbeddingConfigIsConfigMissing() {
        // EmbeddingRouter fallback family + admin-side missing config rejections.
        assertEquals(DeadLetterFailureClass.CONFIG_MISSING,
                classifier.classify("ServiceException: embedding 模型未配置，无法向量化", null));
        assertEquals(DeadLetterFailureClass.CONFIG_MISSING,
                classifier.classify("IllegalStateException: 未配置 embedding 清单（llm.embedding.model）", null));
    }

    @Test
    void providerTransientNetworkFailuresAreTransientNotTimeout() {
        // ConnectException x52 / ClosedChannelException x52 / Broken pipe x2 in live log.
        assertEquals(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                classifier.classify("Caused by: java.net.ConnectException: Connection refused", null));
        assertEquals(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                classifier.classify("Caused by: java.nio.channels.ClosedChannelException: null", null));
        assertEquals(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                classifier.classify("java.net.UnknownHostException: api.provider.invalid", null));
    }

    @Test
    void llmTimeoutBeatsGenericTransient() {
        assertEquals(DeadLetterFailureClass.LLM_TIMEOUT,
                classifier.classify("Caused by: java.net.SocketTimeoutException: read timed out", null));
        assertEquals(DeadLetterFailureClass.LLM_TIMEOUT,
                classifier.classify("ServiceException: 模型调用超时，请稍后重试", null));
    }

    @Test
    void deserializationDamageIsItsOwnClass() {
        // Corrupted payloads go straight to the DLQ .
        assertEquals(DeadLetterFailureClass.DESERIALIZATION_FAIL,
                classifier.classify("org.springframework.amqp.support.converter.MessageConversionException: Failed to convert", null));
        assertEquals(DeadLetterFailureClass.DESERIALIZATION_FAIL,
                classifier.classify("com.fasterxml.jackson.databind.exc.MismatchedInputException: Cannot deserialize value of type", null));
    }

    @Test
    void entityGoneTakesPriorityOverTrailingNoise() {
        // A wrapped chain can mention both; the actionable class is entity-gone
        // (replay is forbidden for it, so misclassification burns retry quota).
        assertEquals(DeadLetterFailureClass.ENTITY_GONE,
                classifier.classify("ListenerExecutionFailedException: failed; Caused by: ServiceException: 项目不存在", null));
    }

    @Test
    void psqlMissingRelationIsNotEntityGone() {
        // "relation \"tp_journey\" does not exist" is a schema defect, UNKNOWN for ops triage.
        assertEquals(DeadLetterFailureClass.UNKNOWN,
                classifier.classify("org.postgresql.util.PSQLException: ERROR: relation \"tp_journey\" does not exist", null));
    }

    @Test
    void xDeathReasonSupportsFallbackClassification() {
        // When the rejection carried no capture-able text, the broker's x-death
        // reason still separates expired from rejected/delivery_limit.
        assertEquals(DeadLetterFailureClass.UNKNOWN, classifier.classify("", "expired"));
        assertEquals(DeadLetterFailureClass.UNKNOWN, classifier.classify(null, null));
    }

    @Test
    void unknownIsTheSafeFallback() {
        assertEquals(DeadLetterFailureClass.UNKNOWN,
                classifier.classify("java.lang.IllegalStateException: something unclassified happened", null));
    }
}
