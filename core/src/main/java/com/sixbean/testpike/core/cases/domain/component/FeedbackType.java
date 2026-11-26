package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * This defines review feedback issue types (six categories frozen in PRD D1; 17 contract enum values).
 * <p>code is the API transport value (feedbackTypes array element), and label is the stored value in tp_review_log.feedback_types.
 * This follows the same convention as storing Chinese labels in tp_review_log.action (01-Database Design).
 * The report feedback-reject-type distribution aggregates directly by label.
 */
public enum FeedbackType {

    STEP_INACCURATE("step_inaccurate", "步骤不准确"),
    EXPECTED_WRONG("expected_wrong", "预期结果错误"),
    SELECTOR_ISSUE("selector_issue", "选择器问题"),
    LOGIC_MISSING("logic_missing", "逻辑遗漏"),
    WORDING("wording", "措辞不准确"),
    LOCATE_ERROR("locate_error", "定位错误");

    private static final Map<String, FeedbackType> BY_CODE =
            java.util.Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(
                    FeedbackType::code, t -> t));

    private static final String LABEL_LIST = java.util.Arrays.stream(values())
            .map(FeedbackType::label).collect(Collectors.joining("/"));

    private final String code;
    private final String label;

    FeedbackType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    /** Review record stored values are Chinese and follow the same convention as the action column label. */
    public String label() {
        return label;
    }

 /** Parse by contract code; values outside the enum throw error 40001 with a human-readable message containing the Chinese valid list. */
    public static FeedbackType from(String code) {
        FeedbackType type = BY_CODE.get(code);
        if (type == null) {
            throw new ServiceException("问题类型仅支持：" + LABEL_LIST + "（当前提交：" + code + "）"
                    + "——请在「评审工作台」的「问题类型」勾选区改选上述选项之一（可多选）后重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return type;
    }

    /** The Chinese valid list is reused for required-field 400 prompts and is the single source per red line 6. */
    public static String labelList() {
        return LABEL_LIST;
    }

 /** Convert a code list to a Chinese label list (preserve order and deduplicate; null or empty returns an empty list; values outside the enum throw error 40001). */
    public static List<String> labelsOf(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return new java.util.ArrayList<>(new LinkedHashSet<>(codes).stream()
                .map(FeedbackType::from)
                .map(FeedbackType::label)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
}
