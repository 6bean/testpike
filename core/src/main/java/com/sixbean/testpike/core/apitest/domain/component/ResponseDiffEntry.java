package com.sixbean.testpike.core.apitest.domain.component;

/**
 * One leaf difference between two JSON responses.
 *
 * @param path         JSONPath-style location ($, $.a.b, $.a[0].x)
 * @param kind         four-color classification
 * @param leftType     node type on the baseline side ("absent" when added)
 * @param rightType    node type on the compared side ("absent" when removed)
 * @param leftSummary  bounded value/shape summary of the baseline side (null when absent)
 * @param rightSummary bounded value/shape summary of the compared side (null when absent)
 */
public record ResponseDiffEntry(String path,
                                ResponseDiffKind kind,
                                String leftType,
                                String rightType,
                                String leftSummary,
                                String rightSummary) {
}
