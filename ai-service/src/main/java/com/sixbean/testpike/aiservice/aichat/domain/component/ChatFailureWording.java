package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

/**
 * One fold point turning any chat-run failure into the sentence the user
 * sees on the red-bordered line of the chat-awaiting contract).
 *
 * The wire carries technical text (parser messages such as "closed",
 * guard internals, upstream status codes); none of it is actionable for a
 * user and echoing it violates the error-surface rule (name the cause in
 * plain words + point at a recovery door that verifiably works). The dock's
 * failure line always renders the resend + copy controls for these classes,
 * E2E-proven to produce a new turn.
 *
 * Classification walks the cause chain to the nearest ServiceException with
 * a known code, so wrappers never push a real timeout/parse break into the
 * generic sentence.
 */
public final class ChatFailureWording {

    private ChatFailureWording() {
    }

    /**
     * The sentence journaled as the chat error frame for this failure.
     *
     * Two authorities, one fold:
     *  1. a sentence written FOR the user by a server-side door (it carries
     *     an action word pointing at a real page/control — the same shape
     *     the screen gate accepts) is passed through untouched, prefixed
     *     with the honest "failed" statement — the door owns its wording;
     *  2. anything technical (parser internals like "closed", guard
     *     internals, upstream status codes) never reaches the screen: the
     *     failure class from the nearest known code names it instead.
     */
    public static String screenSentence(Throwable cause) {
        ServiceException nearest = nearestServiceException(cause);
        if (nearest != null && isUserAuthored(nearest.getMessage())) {
            return "回答生成失败：" + nearest.getMessage();
        }
        Integer code = nearest != null && isKnown(nearest.getCode())
                ? nearest.getCode() : nearestKnownCode(cause);
        if (code != null && code == BizErrorCode.AI_TIMEOUT) {
            return TIMEOUT;
        }
        if (code != null && code == BizErrorCode.AI_FORMAT_ERROR) {
            return INTERRUPTED;
        }
        if (code != null && code == BizErrorCode.AI_PROVIDER_ERROR) {
            return PROVIDER;
        }
        return GENERIC;
    }

    /**
 * technical strings from the LLM layer carry none, which is what pushed
 * "closed" onto the screen before this fold existed.
 */
    private static boolean isUserAuthored(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("请") || message.contains("可在") || message.contains("前往")
                || message.contains("试试") || message.contains("建议")
                || message.contains("可点") || message.contains("点击");
    }

    private static ServiceException nearestServiceException(Throwable cause) {
        for (Throwable at = cause; at != null; at = at.getCause()) {
            if (at instanceof ServiceException service) {
                return service;
            }
        }
        return null;
    }

    private static Integer nearestKnownCode(Throwable cause) {
        for (Throwable at = cause; at != null; at = at.getCause()) {
            if (at instanceof ServiceException service
                    && service.getCode() != null && isKnown(service.getCode())) {
                return service.getCode();
            }
        }
        return null;
    }

    private static boolean isKnown(Integer code) {
        return code != null && (code == BizErrorCode.AI_TIMEOUT
                || code == BizErrorCode.AI_FORMAT_ERROR
                || code == BizErrorCode.AI_PROVIDER_ERROR);
    }

    /** Stream broke mid-delivery: partial text above stays, resend replays. */
    private static final String INTERRUPTED =
            "AI 回答在中途中断，已生成的部分保留在上方。可点击本条下方「重发」再问一次。";

    /** Nothing answered within the wait budget. */
    private static final String TIMEOUT =
            "等待模型响应超时，本条回答未能完成。可点击本条下方「重发」再问一次；若经常超时，可把问题拆小一些再发。";

    /** The model service itself returned an error. */
    private static final String PROVIDER =
            "模型服务返回了错误，本次回答未能完成。可点击本条下方「重发」再问一次，或稍后重试。";

    /** Anything else: honest, no invented specifics, recovery door named. */
    private static final String GENERIC =
            "本次回答未能完成。可点击本条下方「重发」再问一次，或用「复制原文」换个问法。";
}
