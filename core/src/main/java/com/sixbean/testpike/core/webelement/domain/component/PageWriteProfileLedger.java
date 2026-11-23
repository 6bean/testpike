package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Page write-risk ledger .
 *
 * <p>The explore browser guard books, per page, every write-type request that
 * fires outside the page load baseline (autosave POSTs and friends) plus the
 * HTTP failure window it observed. Those facts ride the site page-frame step
 * door that the survey relay already journals through (single ingest door,
 * never a second collector); this component turns them into one row per
 * {@code (project, folded page key)} on {@code tp_page_write_profile} so a
 * page that writes on input keeps a durable risk fact across runs: the
 * conservative snapshot-only downgrade stays sticky, the kernel prompt can
 * annotate known write surfaces before planning any probe, and the query
 * surface later lists risk pages without re-running a survey.
 *
 * <p>Frame decoding is consumed from {@link SiteSurveyRunLedger#pageArguments}
 * — the single source that already tells survey page frames apart from every
 * other step — so the two per-page ledgers can never drift on what a frame
 * IS. Posture matches the sibling run ledger exactly: bookkeeping never fails
 * the step it observes — any parse or write outage degrades to a WARN. The
 * frame window is the unit of truth (latest frame wins per column) so rows
 * stay bounded by one page window instead of accumulating history forever;
 * only the sticky {@code snapshot_only} flag ratchets, because a page that
 * once wrote must never be probed deeply again on the strength of one later
 * clean frame. Page keys fold through {@link WebElementPageKeys} — the same
 * single source the element ledger and the survey run tally use — so one
 * physical page is one row everywhere and the two per-page maps never
 * fragment apart.
 */
@Component
public class PageWriteProfileLedger {

    private static final Logger log = LoggerFactory.getLogger(PageWriteProfileLedger.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SiteSurveyRunLedger runLedger;
    private final WebElementPageKeys pageKeys;

    public PageWriteProfileLedger(NamedParameterJdbcTemplate jdbc, SiteSurveyRunLedger runLedger,
                                  WebElementPageKeys pageKeys) {
        this.jdbc = jdbc;
        this.runLedger = runLedger;
        this.pageKeys = pageKeys;
    }

    /**
     * Apply one step write-back of a page frame to the project's risk ledger.
     * Never throws: frames without any guard rows or run evidence are silent
     * no-ops (clean pages create no row — the ledger is a risk book, not a
     * page census).
     *
     * @param projectId  owning project of the explore session
     * @param stepAction raw stepAction JSON of the write-back
     */
    public void notePageFrame(Long projectId, String stepAction) {
        if (projectId == null) {
            return;
        }
        try {
            JsonNode args = runLedger.pageArguments(stepAction);
            if (args == null) {
                return;
            }
            JsonNode writeEvents = args.path("writeEvents");
            JsonNode writeSurface = args.path("writeSurface");
            JsonNode netFailures = args.path("netFailures");
            boolean snapshotOnly = args.path("snapshotOnly").asBoolean(false);
            if (!hasRisk(writeEvents, writeSurface, netFailures, snapshotOnly)) {
                return;
            }
            String url = args.path("url").asText("");
            if (url.isBlank()) {
                return;
            }
            String pageKey = pageKeys.pageKey(url);
            jdbc.update("""
                    INSERT INTO tp_page_write_profile
                        (project_id, page_key, page_url, write_surface, write_events,
                         net_failures, snapshot_only, first_seen_at, last_seen_at)
                    VALUES (:projectId, :pageKey, :url, CAST(:surface AS JSONB),
                            CAST(:events AS JSONB), CAST(:failures AS JSONB),
                            :snapshotOnly, now(), now())
                    ON CONFLICT (project_id, page_key) DO UPDATE
                       SET page_url = EXCLUDED.page_url,
                           write_surface = EXCLUDED.write_surface,
                           write_events = EXCLUDED.write_events,
                           net_failures = EXCLUDED.net_failures,
                           snapshot_only = tp_page_write_profile.snapshot_only
                                       OR EXCLUDED.snapshot_only,
                           last_seen_at = now()
                    """, new MapSqlParameterSource()
                    .addValue("projectId", projectId)
                    .addValue("pageKey", pageKey)
                    .addValue("url", url)
                    .addValue("surface", jsonArrayOrEmpty(writeSurface))
                    .addValue("events", jsonArrayOrEmpty(writeEvents))
                    .addValue("failures", jsonArrayOrEmpty(netFailures))
                    .addValue("snapshotOnly", snapshotOnly));
        } catch (Exception bookkeepingFailure) {
            log.warn("page write-profile ledger write-back skipped (step fact unaffected)"
                    + " projectId={}: {}", projectId, bookkeepingFailure.getMessage());
        }
    }

    /** True when the frame carries any observed risk fact worth booking. */
    private static boolean hasRisk(JsonNode writeEvents, JsonNode writeSurface,
                                   JsonNode netFailures, boolean snapshotOnly) {
        return snapshotOnly
                || (writeEvents.isArray() && !writeEvents.isEmpty())
                || (writeSurface.isArray() && !writeSurface.isEmpty())
                || (netFailures.isArray() && !netFailures.isEmpty());
    }

    private static String jsonArrayOrEmpty(JsonNode node) {
        return node.isArray() ? node.toString() : "[]";
    }
}
