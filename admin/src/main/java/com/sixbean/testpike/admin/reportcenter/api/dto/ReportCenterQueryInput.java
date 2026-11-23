package com.sixbean.testpike.admin.reportcenter.api.dto;

/**
 *
 * @param sortBy Sort column key (whitelist authority lives in core; the bridge forwards it untouched) — null = default order
 * @param sortDir asc/desc, only meaningful together with sortBy
 * @param programId Program lens (project set) id; 0 = no lens
 */
public record ReportCenterQueryInput(int page, int size, Integer reportType, Integer scopeType,
                                     Long scopeId, String periodFrom, String periodTo,
                                     String sortBy, String sortDir, long programId) {

    /** Legacy 7-arg entry: no sort pair, no lens. */
    public ReportCenterQueryInput(int page, int size, Integer reportType, Integer scopeType,
                                  Long scopeId, String periodFrom, String periodTo) {
        this(page, size, reportType, scopeType, scopeId, periodFrom, periodTo, null, null, 0L);
    }

    /** Pre-lens 9-arg entry: programId=0 keeps the unfiltered legacy view. */
    public ReportCenterQueryInput(int page, int size, Integer reportType, Integer scopeType,
                                  Long scopeId, String periodFrom, String periodTo,
                                  String sortBy, String sortDir) {
        this(page, size, reportType, scopeType, scopeId, periodFrom, periodTo,
                sortBy, sortDir, 0L);
    }
}
