package com.sixbean.testpike.core.cases.api.dto;

/**
 * The daily report digest scan result is handled by the core internal scheduled task and registered as a server-side scheduled task.
 * No new external API is exposed.
 * <p>Metric definitions: scanned is the number of active projects with yesterday's four-element data; skipped is the number of projects skipped because there is no yesterday's data.
 * No empty report is sent; sent is the number of notifications actually generated in this round; deduped is the number skipped due to a same-day dedup_key hit.
 * The next round backfills without duplication; prefFiltered is the number not created due to single-point preference filtering, where create returns 0.
 * default_off DAILY_DIGEST is hit when there is no subscription record, and the generator does not duplicate the check.
 *
 */
public record DailyDigestResult(int scanned, int skipped, int sent, int deduped, int prefFiltered) {
}
