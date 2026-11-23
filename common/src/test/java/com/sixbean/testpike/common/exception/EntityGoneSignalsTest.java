package com.sixbean.testpike.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entity-gone vocabulary contract ( channel, hardened after the 2026-11-06
 * L4 dead-letter spectrum false negative): a provider HTTP error body carries
 * "404 Not Found" wording too, so loose English substrings must never mark a
 * failure as entity-gone — real core rejections arrive either in Chinese
 * business wording or structurally with {@link BizErrorCode#NOT_FOUND}.
 * Dead letters must not be silently swallowed (iron rule).
 */
class EntityGoneSignalsTest {

    /** Aggregated provider failure as harvested live on 2026-11-06 (ticket L4 leg A). */
    private static final String PROVIDER_ERROR_TEXT =
            "需求点提取失败：AI 供应商调用失败: 404 Not Found: "
            + "\"{\"error\":{\"message\":\"litellm.BadRequestError: DashscopeException - "
            + "Unsupported model `text-embedding-v4` for OpenAI compatibility mode.\", "
            + "\"type\":\"invalid_request_error\"}}\"; "
            + "重试时间线：第1次 AI 供应商不可达: I/O error on POST request for "
            + "\"http://127.0.0.1:14555/v1/chat/completions\": null";

    @Test
    void providerHttp404BodyIsNotEntityGone() {
        assertFalse(EntityGoneSignals.matchesText(PROVIDER_ERROR_TEXT),
                "provider error wording must not be classified as entity-gone");
        assertFalse(EntityGoneSignals.matches(
                new ServiceException(PROVIDER_ERROR_TEXT, BizErrorCode.AI_PROVIDER_ERROR)));
    }

    @Test
    void realCoreEntityGoneWordingStillMatches() {
        assertTrue(EntityGoneSignals.matchesText("文档不存在（或已删除）"));
        assertTrue(EntityGoneSignals.matchesText("目标文档不存在或已被删除"));
        assertTrue(EntityGoneSignals.matchesText("用例已被删除"));
    }

    @Test
    void notFoundErrorCodeMatchesStructurallyEvenWithEnglishBody() {
        assertTrue(EntityGoneSignals.matches(
                new ServiceException("Not Found", BizErrorCode.NOT_FOUND)));
        RuntimeException wrapped = new RuntimeException(
                "listener failed", new ServiceException("entity missing", BizErrorCode.NOT_FOUND));
        assertTrue(EntityGoneSignals.matches(wrapped),
                "Feign wraps core rejections one level deeper — the code signal must survive");
    }

    @Test
    void otherErrorCodesDoNotInheritTheEnglishNotFoundShortcut() {
        assertFalse(EntityGoneSignals.matches(
                new ServiceException("downstream answered 404 Not Found on model endpoint",
                        BizErrorCode.AI_PROVIDER_ERROR)));
    }
}
