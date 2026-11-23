package com.sixbean.testpike.aiservice.taskstream.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source for the shape of a terminal failure event, adjudicated
 * after the live defect "a long steps-gen run failed with no feedback".
 * Every job family's failure branch routes its cause through this
 * one formatter instead of assembling its own sentence, so the last event of
 * any dead run always carries the same three parts the user needs:
 * plus a machine detail face ({@code reasonCategory/suggestion/retryEntry})
 * the viewer can key a retry affordance on.
 * <p>Classification is a closed decision list, evaluated in order: a cause
 * text signature first (the families' own human wording is the most reliable
 * evidence), then the {@link ServiceException} business code, and finally
 * environment side as the conservative default — an unclassifiable outage is
 * reported as an infrastructure problem rather than silently pinned on the
 * model or on the user's input.
 * <p>The formatter never throws and never returns a blank summary: the event
 * door behind it rejects blank rows, and a formatter outage must not be the
 * reason a failure event goes missing.
 */
public final class TaskFailureEventFormatter {

    /** Closed vocabulary of failure categories shown to the viewer. */
    public static final String CATEGORY_MODEL = "模型侧";
    public static final String CATEGORY_VALIDATION = "校验侧";
    public static final String CATEGORY_ENVIRONMENT = "环境侧";

    /** Longest cause fragment kept verbatim before the tail is trimmed. */
    private static final int CAUSE_WIDTH = 180;

    private static final List<String> MODEL_SIGNATURES = List.of(
            "模型", "大模型", "LLM", "供应商", "provider", "LiteLLM",
            "流式", "stream", "超时", "timeout", "限流", "rate limit",
            "token", "无可见输出", "空输出");
    private static final List<String> VALIDATION_SIGNATURES = List.of(
            "校验", "拒收", "拒绝", "不合", "格式", "解析", "未生成", "为空",
            "不合法", "超限", "上限", "JSON", "json");
    private static final List<String> ENVIRONMENT_SIGNATURES = List.of(
            "服务异常", "服务不可用", "连接", "connection", "不可达", "unreachable",
            "数据库", "Redis", "redis", "队列", "MQ", "存储", "503", "502");

    private static final Map<String, String> SUGGESTIONS = Map.of(
            CATEGORY_MODEL, "模型服务繁忙或配置异常，可稍后直接重试；连续失败请到 系统设置→模型接入 检查供应商与超时参数",
            CATEGORY_VALIDATION, "输入内容或模型输出未通过校验，请按上面点名的事实调整内容后重新发起",
            CATEGORY_ENVIRONMENT, "依赖的服务或网络暂时不可用，可稍后重试；持续失败请检查相关服务运行状态");

    /**
     * Model-output-shape failures (the ring exhausted even after the system's
     * own re-rolls and targeted field fills) are never fixed by touching the
     * input — telling the user to adjust it is a false road (R63). This
     * suggestion names what the system already did, the honest next click
     * (retry, unchanged input), and the escape hatch that actually changes
     * the generating side (a different model, hot-applied). Keyed on the
     * business code, not the cause text, so the wording the ring carries
     * stays byte-identical for the L3 grep anchors.
     */
    private static final String SUGGESTION_MODEL_OUTPUT_FORMAT =
            "模型输出格式经系统自动重生成与字段补全后仍未收敛，直接点重试即可（无需调整输入内容）；"
                    + "连续同因失败请到 系统设置→模型接入 更换模型后立即生效";

    /**
     * The script stage (stage 2) re-rolls but has no targeted field fill, so
     * its honest variant claims only what actually ran (R63: the advice must
     * match the system's real actions, not the neighbouring stage's).
     */
    private static final String SUGGESTION_MODEL_SCRIPT_FORMAT =
            "脚本格式经系统自动重生成后仍未收敛，直接点重试即可（无需调整输入内容）；"
                    + "连续同因失败请到 系统设置→模型接入 更换模型后立即生效";

    /** Text face separating the script-stage format failure from the case draft stage. */
    private static final String SCRIPT_STAGE_MARK = "脚本格式校验失败";

    private static final com.fasterxml.jackson.databind.ObjectMapper DETAIL_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private TaskFailureEventFormatter() {
    }

    /** Format the terminal event of a run that died on {@code error}. */
    public static TaskFailureEvent format(Throwable error, String retryEntry) {
        String cause = error == null ? null
                : (error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName() : error.getMessage());
        return format(cause, codeOf(error), retryEntry);
    }

    /** Format the terminal event of a run that died on a textual cause the
     *  caller already humanised (no exception object at hand). */
    public static TaskFailureEvent format(String rawCause, String retryEntry) {
        return format(rawCause, null, retryEntry);
    }

    private static TaskFailureEvent format(String rawCause, Integer bizCode, String retryEntry) {
        String cause = rawCause == null || rawCause.isBlank() ? "未知原因"
                : (rawCause.length() > CAUSE_WIDTH ? rawCause.substring(0, CAUSE_WIDTH) + "…" : rawCause);
        String category = classify(cause, bizCode);
        String suggestion = suggestionFor(category, cause, bizCode);
        String entry = retryEntry == null || retryEntry.isBlank() ? "任务详情页的重新发起入口" : retryEntry;
        String summary = "失败：【" + category + "】" + cause
                + "｜建议：" + suggestion + "｜重试入口：" + entry;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reasonCategory", category);
        fields.put("suggestion", suggestion);
        fields.put("retryEntry", entry);
        return new TaskFailureEvent(category, suggestion, entry, summary, writeJsonQuiet(fields));
    }

    /**
     * Suggestion pick: AI-output-format failures get the system-action
     * wording (never the "adjust your input" false road, R63), split by the
     * stage that actually ran its recovery; every other category rides the
     * per-category table.
     */
    private static String suggestionFor(String category, String cause, Integer bizCode) {
        if (bizCode != null && bizCode == BizErrorCode.AI_FORMAT_ERROR) {
            return cause.contains(SCRIPT_STAGE_MARK)
                    ? SUGGESTION_MODEL_SCRIPT_FORMAT
                    : SUGGESTION_MODEL_OUTPUT_FORMAT;
        }
        return SUGGESTIONS.getOrDefault(category, SUGGESTIONS.get(CATEGORY_ENVIRONMENT));
    }

    /** Closed decision list — text signature, then business code, then the
     *  conservative environment-side default. */
    private static String classify(String causeText, Integer bizCode) {
        if (containsAny(causeText, MODEL_SIGNATURES)) {
            return CATEGORY_MODEL;
        }
        if (containsAny(causeText, VALIDATION_SIGNATURES)) {
            return CATEGORY_VALIDATION;
        }
        if (containsAny(causeText, ENVIRONMENT_SIGNATURES)) {
            return CATEGORY_ENVIRONMENT;
        }
        if (bizCode != null) {
            if (bizCode == BizErrorCode.AI_TIMEOUT || bizCode == BizErrorCode.AI_PROVIDER_ERROR) {
                return CATEGORY_MODEL;
            }
            if (bizCode == BizErrorCode.AI_FORMAT_ERROR) {
                return CATEGORY_VALIDATION;
            }
        }
        return CATEGORY_ENVIRONMENT;
    }

    private static boolean containsAny(String text, List<String> signatures) {
        for (String signature : signatures) {
            if (text.contains(signature)) {
                return true;
            }
        }
        return false;
    }

    private static Integer codeOf(Throwable error) {
        return error instanceof ServiceException se ? se.getCode() : null;
    }

    private static String writeJsonQuiet(Map<String, Object> fields) {
        try {
            return DETAIL_MAPPER.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            return "{\"reasonCategory\":\"" + CATEGORY_ENVIRONMENT + "\"}";
        }
    }
}
