package com.sixbean.testpike.core.common.domain.component;

import java.util.StringJoiner;

/**
 * Common component that generates SQL IN placeholders (?, ?, ... with n question marks).
 * Extract <p>—test case export (CaseExportSupport/CaseServiceImpl) and defect export use the same logic.
 * Hard rule: code repeated two or more times must be extracted into a common component (the original private duplicate in plans.PlanServiceImpl is also consolidated).
 *
 */
public final class SqlPlaceholders {

    private SqlPlaceholders() {
    }

    /** Provides n comma-separated placeholders (used to build IN (?, ?, ?); n >= 1). */
    public static String in(int n) {
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < n; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }
}
