package com.sixbean.testpike.core.testreport.api.dto;

/**
 *
 * @param sortBy Sort column key from the core whitelist (an off-list key gets a 400); null = default order
 * @param sortDir asc/desc (case-insensitive, anything else orders desc; only meaningful with sortBy)
 */
public record ReportQuery(long projectId, String keyword, int page, int size,
                          String sortBy, String sortDir) {

    /** Legacy 4-arg entry: no sort pair = the historical id DESC default order. */
    public ReportQuery(long projectId, String keyword, int page, int size) {
        this(projectId, keyword, page, size, null, null);
    }
}
