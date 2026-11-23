package com.sixbean.testpike.core.tool.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GA5 : one external /mcp call error text lands in exactly one of the
 * four operational buckets — refused / timeout / idempotent-conflict /
 * forbidden — or honestly stays UNCLASSIFIED. The classifier never guesses:
 * an unmatched text is reported as unmatched, which is the whole point of a
 * distribution the self-evolution loop can reason about without inventing
 * causes. Rule order encodes specificity (idempotent and forbidden wording
 * also contains refusal-shaped words, so they are matched first).
 * <p>Tokens are harvested from the real message sites of the external chain:
 * forward-read timeouts and SocketTimeout wording (timeout),
 * McpServerHandler TOOL_NOT_FOUND + ToolAccessGate FORBIDDEN wording
 * (forbidden), and the gate refusals — AI switch off with the R32 pointing
 * sentence, daily quota / rate limiter caps, spent confirm handles
 * (refused).
 */
class ExternalCallErrorClassifierTest {

    private final ExternalCallErrorClassifier classifier = new ExternalCallErrorClassifier();

    @Test
    void idempotentConflictsAreMatchedBeforePlainRefusals() {
        assertEquals(ExternalCallErrorClass.IDEMPOTENT_CONFLICT,
                classifier.classify("重复请求：相同 Idempotency-Key 的请求正在处理中，请勿重复提交"));
        assertEquals(ExternalCallErrorClass.IDEMPOTENT_CONFLICT,
                classifier.classify("Duplicate request: same Idempotency-Key is in progress"));
    }

    @Test
    void transportTimeoutsFormTheirOwnClass() {
        assertEquals(ExternalCallErrorClass.TIMEOUT,
                classifier.classify("org.springframework.web.client.ResourceAccessException: "
                        + "I/O error on POST request: Read timed out"));
        assertEquals(ExternalCallErrorClass.TIMEOUT,
                classifier.classify("网关转发超时（forward-read-timeout 880s 已用尽）"));
    }

    @Test
    void permissionDenialsAreForbiddenNotRefused() {
        assertEquals(ExternalCallErrorClass.FORBIDDEN,
                classifier.classify("Tool not found（工具不存在或当前 Token 无权限）"));
        assertEquals(ExternalCallErrorClass.FORBIDDEN,
                classifier.classify("目标用例不在当前身份可达项目集，越权访问被拒绝"));
    }

    @Test
    void gateRefusalsCountAsRefused() {
        // R32 shape: capability switched off, message names where to switch it on.
        assertEquals(ExternalCallErrorClass.REFUSED,
                classifier.classify("AI 脚本生成未开启，请在 系统设置→AI 功能 打开 ai.script.generate 后重试"));
        assertEquals(ExternalCallErrorClass.REFUSED,
                classifier.classify("该 Token 当日用量已达上限，明日自动恢复"));
        assertEquals(ExternalCallErrorClass.REFUSED,
                classifier.classify("请求过于频繁，请稍后重试"));
        assertEquals(ExternalCallErrorClass.REFUSED,
                classifier.classify("确认码已被消费或已过期，请重新发起草案"));
    }

    @Test
    void unmatchedAndBlankTextStaysUnclassified() {
        assertEquals(ExternalCallErrorClass.UNCLASSIFIED,
                classifier.classify("字段 case_id 必须为正整数"));
        assertEquals(ExternalCallErrorClass.UNCLASSIFIED, classifier.classify(null));
        assertEquals(ExternalCallErrorClass.UNCLASSIFIED, classifier.classify("   "));
    }
}
