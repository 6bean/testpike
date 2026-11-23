package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * One leaf difference between two JSON responses, or one line difference in
 * TEXT mode (admin mirror of core ResponseDiffEntry — field names lock to the
 * wire shape; entries never invent a second diff vocabulary).
 *
 * @param path        JSONPath-style location ($, $.a.b, $.a[0].x) or "line:N"
 *                    for the text lane
 * @param kind        four-color classification (text mode uses VALUE_CHANGED)
 * @param leftType    node type on the baseline side ("absent" when added)
 * @param rightType   node type on the compared side ("absent" when removed)
 * @param leftSummary bounded value/shape summary of the baseline side (null when absent)
 * @param rightSummary bounded value/shape summary of the compared side (null when absent)
 */
public record ResponseDiffEntry(String path,
                                ResponseDiffKind kind,
                                String leftType,
                                String rightType,
                                String leftSummary,
                                String rightSummary) {
}
