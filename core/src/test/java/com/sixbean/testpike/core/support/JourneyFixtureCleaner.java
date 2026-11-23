package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.project.domain.service.ProjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Shared cleanup ledger for journey-domain test fixtures (R40 single source —
 * the list, run-expansion and segment-pin suites all consume this one teardown).
 * Collects the project, program and asset ids a suite creates, then replays the
 * identical sequence: delete the notifications and asset pins that hang off the
 * tracked programs' journeys, remove the journey lineage (run segments, runs,
 * plan segments, journeys) under those programs only, drop tracked asset rows,
 * purge each tracked project through the real project service, delete each
 * tracked program row and reset the ledger. Nothing is ever truncated (R37) —
 * rows outside the tracked lineage, including concurrent suites' rows, stay
 * untouched.
 */
public class JourneyFixtureCleaner {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ProjectService projectService;
    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> programIds = new ArrayList<>();
    private final List<Long> assetIds = new ArrayList<>();

    public JourneyFixtureCleaner(JdbcTemplate jdbc, ProjectService projectService) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.projectService = projectService;
    }

    /** Keep one ledger instance per suite across the BeforeEach/AfterEach pair. */
    public static JourneyFixtureCleaner forTestRun(JourneyFixtureCleaner current,
                                                   JdbcTemplate jdbc,
                                                   ProjectService projectService) {
        return current == null ? new JourneyFixtureCleaner(jdbc, projectService) : current;
    }

    /** Create and track a program row with a suite-prefixed unique name. */
    public long createProgram(String namePrefix) {
        Long id = jdbc.queryForObject("INSERT INTO tp_program (name, created_by) "
                + "VALUES (?, 1) RETURNING id", Long.class, namePrefix + UUID.randomUUID());
        trackProgram(id);
        return id;
    }

    /** Combined teardown: purge the tracked programs' lineage, then the ids ledger. */
    public void teardownSuiteTables() {
        purgeJourneyLineage();
        cleanupTracked();
    }

    /**
     * Delete every journey-domain row created under the tracked programs, in
     * FK-safe order: notifications first (their dedup keys reference the run
     * and batch ids that are about to disappear), then the lineage itself.
     */
    public void purgeJourneyLineage() {
        if (!programIds.isEmpty()) {
            // List values (not Object[] — that would bind as a single bigint[]
            // parameter) make NamedParameterJdbcTemplate expand IN (:progs).
            MapSqlParameterSource progs = new MapSqlParameterSource("progs", programIds);
            // Run-finished notifications carry dedup key "JOURNEY_RUN_FINISHED:<run id>"
            // — pruned strictly along the tracked programs' lineage, never by sweep.
            named.update("DELETE FROM tp_notification WHERE dedup_key IN ("
                            + "SELECT 'JOURNEY_RUN_FINISHED:' || r.id FROM tp_journey_run r "
                            + "JOIN tp_journey j ON j.id = r.journey_id "
                            + "WHERE j.program_id IN (:progs))", progs);
            named.update("DELETE FROM tp_notification n WHERE n.event_type = "
                            + "'JOURNEY_SEGMENT_FAILED' AND EXISTS ("
                            + "SELECT 1 FROM tp_journey_run_segment s "
                            + "JOIN tp_journey_run r ON r.id = s.run_id "
                            + "JOIN tp_journey j ON j.id = r.journey_id "
                            + "WHERE j.program_id IN (:progs) AND s.batch_id IS NOT NULL "
                            + "AND n.dedup_key LIKE 'JOURNEY_SEGMENT_FAILED:' "
                            + "|| s.batch_id || ':%')", progs);
            // Segment pins were registered by the shared reference source with
            // referrer_kind "journey_segment" — drop them with their segments.
            named.update("DELETE FROM tp_asset_reference ar WHERE ar.referrer_kind = "
                            + "'journey_segment' AND EXISTS ("
                            + "SELECT 1 FROM tp_journey_segment s JOIN tp_journey j "
                            + "ON j.id = s.journey_id WHERE s.id = ar.referrer_id "
                            + "AND j.program_id IN (:progs))", progs);
            named.update("DELETE FROM tp_journey_run_segment WHERE run_id IN ("
                            + "SELECT r.id FROM tp_journey_run r JOIN tp_journey j "
                            + "ON j.id = r.journey_id WHERE j.program_id IN (:progs))", progs);
            named.update("DELETE FROM tp_journey_run WHERE journey_id IN ("
                            + "SELECT id FROM tp_journey WHERE program_id IN (:progs))", progs);
            named.update("DELETE FROM tp_journey_segment WHERE journey_id IN ("
                            + "SELECT id FROM tp_journey WHERE program_id IN (:progs))", progs);
            named.update("DELETE FROM tp_journey WHERE program_id IN (:progs)", progs);
        }
        if (!assetIds.isEmpty()) {
            MapSqlParameterSource assets = new MapSqlParameterSource("assets", assetIds);
            named.update("DELETE FROM tp_asset_reference WHERE asset_row_id IN (:assets)", assets);
            named.update("DELETE FROM tp_asset WHERE id IN (:assets)", assets);
        }
    }

    public void trackProject(long id) {
        projectIds.add(id);
    }

    public void trackProgram(long id) {
        programIds.add(id);
    }

    public void trackAsset(long id) {
        assetIds.add(id);
    }

    /** Purge every tracked row strictly by collected id and reset the ledger. */
    public void cleanupTracked() {
        for (Long id : projectIds) {
            projectService.purge(1L, id, true);
        }
        projectIds.clear();
        for (Long id : programIds) {
            jdbc.update("DELETE FROM tp_program WHERE id = " + id);
        }
        programIds.clear();
        assetIds.clear();
    }
}
