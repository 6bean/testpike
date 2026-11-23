package com.sixbean.testpike.common.webelement;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of the per-page network write-risk ledger , shared
 * by the core service that reads {@code tp_page_write_profile} and the admin
 * bridge that mirrors it to the browser.
 *
 * <p>The guard columns ride as decoded JSON so the wire shape stays exactly
 * what the ledger booked — the write verbs observed in the load window, the
 * baseline-outside write events attributed to probe actions, and the HTTP
 * failure triples — without inventing a second, lossier DTO per module.
 * {@code snapshotOnly} is the sticky conservative downgrade: once true, the
 * page stays read-only for later probes and no clean frame unlocks it.
 */
public record PageWriteProfileView(
        Long id,
        Long projectId,
        String pageKey,
        String pageUrl,
        List<String> writeSurface,
        List<JsonNode> writeEvents,
        List<JsonNode> netFailures,
        boolean snapshotOnly,
        Long lastRunId,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt) {

    /** Bean-style alias of the record component, for getter-shaped callers. */
    public Long getId() {
        return id;
    }

    /** True when probing must stay read-only on this page (sticky downgrade). */
    public boolean writesOnInput() {
        return snapshotOnly;
    }
}
