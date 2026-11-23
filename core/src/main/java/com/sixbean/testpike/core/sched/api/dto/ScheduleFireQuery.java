package com.sixbean.testpike.core.sched.api.dto;

/**
 * Paged query for the schedule fire ledger (02 /schedule/fires GET, H1
 * project isolation).
 *
 * @param projectId owning project (required)
 * @param kind      trigger kind filter (suite/template, null = all)
 * @param status    outcome filter (fired/success/failed, null = all)
 * @param page      page number (default 1)
 * @param size      page size (default 20, capped at 200)
 * @param sortBy    whitelisted sort key; null/blank keeps the historical
 *                  fire_time DESC order — enforced fail-closed in core,
 *                  never interpolated raw into SQL
 * @param sortDir   asc|desc (anything else falls back to desc, same posture
 *                  as the mock scenario whitelist)
 */
public record ScheduleFireQuery(long projectId, String kind, String status, int page, int size,
                                String sortBy, String sortDir) {

    /**
     * Historical five-field form: every existing caller (internal controller
     * before this change, unit tests) keeps the default fire_time order with
     * zero behavioural difference.
     */
    public ScheduleFireQuery(long projectId, String kind, String status, int page, int size) {
        this(projectId, kind, status, page, size, null, null);
    }
}
