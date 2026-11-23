package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Case list query (GET /cases: filtering by all nine statuses, keyword, sorting, and pagination).
 *
 * @param projectId Project ID (required, isolation dimension).
 * @param status Status 1 to 9 (null means all).
 * @param keyword Keyword (matches title, precondition, expected result, or steps_text, using PostgreSQL LIKE fallback).
 * @param priority Priority (null means all).
 * @param confidence Confidence (null means all).
 * @param assigneeId Assignee (null means all; data source for the My Pending Review queue).
 * @param phase Case phase: 1=smoke, 2=functional, 3=regression, 4=release validation (null means all; 0=unassigned, used for list filtering).
 * @param tagIds Tag ID list (filter cases by tag, empty means all; tags must belong to projectId).
 * @param sortBy Sort field whitelist: priority / created_at / confidence (invalid values fallback to created_at).
 * @param sortDir Sort direction: asc / desc (invalid values fallback to desc).
 * @param page Page number (>= 1).
 * @param size Page size (1 to 100).
 * @param excellentOnly Only excellent cases (true adds AND excellent_flag=1 and uses the migration partial index; null/false means all).
 * @param programId Program lens (project set) id; 0 means no lens. A positive value narrows
 * the query to the lens projects the caller can see (ProgramLensScope),
 * intersected with the explicit projectId when one is also given.
 * @param sourceFacet Provenance facet quick filter (chat/off_agent/import/manual; null=all).
 * Values are validated and folded into the WHERE by CaseFilters.
 * @param evidenceGrade Evidence grade quick filter 0..3 (null=all); out-of-range values are
 * refused in plain language .
 */
public record CaseQuery(long projectId, Integer status, String keyword, Integer priority,
                        Integer confidence, Long assigneeId, Integer phase, List<Long> tagIds,
                        String sortBy, String sortDir, int page, int size,
                        Boolean excellentOnly, long programId,
                        String sourceFacet, Integer evidenceGrade) {

    /** Keeps the existing 14-parameter canonical calls compiling (facet and grade null = no filtering). */
    public CaseQuery(long projectId, Integer status, String keyword, Integer priority,
                     Integer confidence, Long assigneeId, Integer phase, List<Long> tagIds,
                     String sortBy, String sortDir, int page, int size,
                     Boolean excellentOnly, long programId) {
        this(projectId, status, keyword, priority, confidence, assigneeId, phase, tagIds,
                sortBy, sortDir, page, size, excellentOnly, programId, null, null);
    }

    /** Maintains compatibility with the existing 12-parameter constructor (excellentOnly=null means no filtering; avoids ripple effects for tests and legacy callers). */
    public CaseQuery(long projectId, Integer status, String keyword, Integer priority,
                     Integer confidence, Long assigneeId, Integer phase, List<Long> tagIds,
                     String sortBy, String sortDir, int page, int size) {
        this(projectId, status, keyword, priority, confidence, assigneeId, phase, tagIds,
                sortBy, sortDir, page, size, null, 0L);
    }

    /** Compatibility with the 13-parameter form (programId=0 means no lens; legacy tests and callers keep compiling). */
    public CaseQuery(long projectId, Integer status, String keyword, Integer priority,
                     Integer confidence, Long assigneeId, Integer phase, List<Long> tagIds,
                     String sortBy, String sortDir, int page, int size,
                     Boolean excellentOnly) {
        this(projectId, status, keyword, priority, confidence, assigneeId, phase, tagIds,
                sortBy, sortDir, page, size, excellentOnly, 0L);
    }
}
