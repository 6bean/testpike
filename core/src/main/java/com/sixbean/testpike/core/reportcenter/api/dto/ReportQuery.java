package com.sixbean.testpike.core.reportcenter.api.dto;

/**
 *
 * @param sortBy Sort column key from the core whitelist (an off-list key gets a 400); null = default order
 * @param sortDir asc/desc (case-insensitive, anything else orders desc; only meaningful with sortBy)
 * @param programId Program lens (project set) id; 0 = no lens. A positive value narrows
 * the list to snapshots of lens projects ∩ caller visibility
 */
public record ReportQuery(int page, int size, Integer reportType, Integer scopeType,
                          Long scopeId, String periodFrom, String periodTo,
                          String sortBy, String sortDir, long programId) {

    /** Legacy 7-arg entry: no sort pair, no lens = the historical default order and view. */
    public ReportQuery(int page, int size, Integer reportType, Integer scopeType,
                       Long scopeId, String periodFrom, String periodTo) {
        this(page, size, reportType, scopeType, scopeId, periodFrom, periodTo, null, null, 0L);
    }

    /** Pre-lens 9-arg entry: programId=0 keeps the unfiltered legacy view. */
    public ReportQuery(int page, int size, Integer reportType, Integer scopeType,
                       Long scopeId, String periodFrom, String periodTo,
                       String sortBy, String sortDir) {
        this(page, size, reportType, scopeType, scopeId, periodFrom, periodTo,
                sortBy, sortDir, 0L);
    }
}
