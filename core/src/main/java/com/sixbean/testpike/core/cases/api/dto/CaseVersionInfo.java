package com.sixbean.testpike.core.cases.api.dto;

import java.time.LocalDateTime;

/**
 * Test case version history entry (Q1; 01 tp_case_version, 02 contract GET /cases/{id}/versions).
 * <p>operatorName=operator display name (display_name fallback username, same semantics as review history);
 * changedFieldCount=number of changed fields relative to the previous version (list 'changed field count' column,;
 * Oldest baseline=0; snapshot corrupted=null and corrupted=true—mark and skip diff/rollback).
 *
 */
public record CaseVersionInfo(long id, long caseId, int version, int changeType,
                              Long operatorId, String operatorName, LocalDateTime createdAt,
                              Integer changedFieldCount, boolean corrupted) {

 /** change_type constants (01: 1=edit, 2=review status change, 3=draft, 4=rollback). */
    public static final int CHANGE_EDIT = 1;
    public static final int CHANGE_REVIEW = 2;
    public static final int CHANGE_DRAFT = 3;
    public static final int CHANGE_ROLLBACK = 4;
}
