package com.sixbean.testpike.core.requirement.api.dto;

import java.time.LocalDateTime;

/**
 * Requirement version history entry (read surface of the tp_requirement_version chain,
 * data source of GET /requirements/{id}/versions for the ledger page version dialog).
 * <p>operatorName = operator display name (display_name fallback username, same semantics
 * as the case version list); changedFieldCount = number of snapshot keys that moved
 * against the previous version (oldest baseline = 0; a corrupted snapshot yields null);
 * corrupted = snapshot undecodable - the row is shown for audit but diff fields stay blank.
 */
public record RequirementVersionInfo(long id, long requirementId, int version, int changeType,
                                     Long operatorId, String operatorName, LocalDateTime createdAt,
                                     Integer changedFieldCount, boolean corrupted) {
}
