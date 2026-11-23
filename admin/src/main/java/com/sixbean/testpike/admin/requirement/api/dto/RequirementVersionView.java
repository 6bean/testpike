package com.sixbean.testpike.admin.requirement.api.dto;

import java.time.LocalDateTime;

/**
 * Requirement version history row mirrored from core RequirementVersionInfo
 * (admin-&gt;core layering law: the view is owned by admin, never re-exported raw).
 */
public record RequirementVersionView(long id, long requirementId, int version, int changeType,
                                     Long operatorId, String operatorName, LocalDateTime createdAt,
                                     Integer changedFieldCount, boolean corrupted) {
}
