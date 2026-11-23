package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Field-level version diff for the contract endpoint GET /cases/{id}/versions/{v}/diff.
 * <p>fromVersion is the baseline version (explicitly specified via ?baseVersion=, default=current version), and toVersion is the target version (path {v}).
 * changes contains only fields whose values differ between the two versions, matching the registered semantics that diff returns field-level before/after.
 * <p>truncated is set to true when the comparison spans more than 20 versions, with the frontend hint that too many versions are compared and adjacent versions are recommended.
 *
 */
public record CaseVersionDiff(long caseId, int fromVersion, int toVersion, boolean truncated,
                              List<CaseVersionFieldChange> changes) {
}
