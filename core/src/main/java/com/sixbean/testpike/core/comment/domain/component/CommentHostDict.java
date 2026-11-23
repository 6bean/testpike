package com.sixbean.testpike.core.comment.domain.component;

import java.util.Map;

/**
 * Comment host dictionary (migration artifact/single source of truth; four-host whitelist with human-readable Chinese names, shared logic for frontend badges and notification titles).
 * <p>Final decision A: the review host is the review workbench (tp_review_log is the fact entity, host_id=case_id, and creating a new tp_review table is prohibited).
 */
public final class CommentHostDict {

    /** Maps host type to human-readable Chinese name (for notification title/400 error message concatenation, redline 10). */
    public static final Map<String, String> HOSTS = Map.of(
            "review", "评审工作台",
            "case", "用例",
            "batch", "批次报告",
            "defect", "缺陷");

    private CommentHostDict() {
    }

    /** Checks whether the host type is valid. */
    public static boolean isKnown(String hostType) {
        return hostType != null && HOSTS.containsKey(hostType);
    }

    /** Host Chinese name (unknown types use the original value as fallback—display concatenation only; validation runs first and will always match). */
    public static String label(String hostType) {
        return HOSTS.getOrDefault(hostType, String.valueOf(hostType));
    }
}
