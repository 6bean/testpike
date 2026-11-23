package com.sixbean.testpike.admin.cases.api.dto;

import java.time.LocalDateTime;

/**
 *
 * @param priority 0=P0 1=P1 2=P2
 */
public record CaseRecycleView(long id, String caseNo, long projectId, String title, int priority,
                              int status, int version, Long deletedBy, String deletedByName,
                              LocalDateTime deletedAt) {
}
