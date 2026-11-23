package com.sixbean.testpike.core.reportcenter.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.CaliberService;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.defects.api.dto.DefectBurnWindow;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.reportcenter.api.dto.ProgramOverview;
import com.sixbean.testpike.core.reportcenter.api.dto.ProjectPassRate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Program overview aggregation (project-set dashboard feed, aggregate report data
 * source for the middle-platform quality board).
 *
 * <p>Contract under test: the project segment comes from the ProgramLensScope single
 * source (lens projects ∩ caller visibility — an outsider gets an honest empty feed,
 * a partial member only their visible half); every visible project yields one live
 * pass-rate reading (NULL when the period holds no executions, never a fabricated
 * zero); footnotes are generated from the caliber registry itself rather than a
 * hand-written list; unknown program ids reuse ProgramService.get's 40401 and
 * non-positive ids get a 40001 validation error. Calibers run against the live
 * schema — no mocks, shipped tables never truncated (fixture rows are torn down
 * by ProgramLensFixtureExtension).
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ProgramOverviewServiceTest {

    @Autowired
    private ProgramOverviewService programOverviewService;

    /** Window preconditions need pinned defect timestamps; rows ride the fixture project
     *  so ProgramLensFixture.cleanup() (tp_defect branch) tears them down (R40 single source). */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Insert one defect with fully pinned state/time (the burndown read path stays the product one). */
    private void insertDefect(long projectId, int status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        jdbcTemplate.update("INSERT INTO tp_defect (project_id, title, severity, status, reporter_id,"
                        + " created_at, updated_at) VALUES (?, ?, 2, ?, ?, ?, ?)",
                projectId, "burn-" + createdAt + "-" + status, status,
                ProgramLensFixture.OPERATOR, createdAt, updatedAt);
    }

    private static DefectBurnWindow burnOf(ProgramOverview got, long projectId) {
        return got.defectBurn().stream().filter(b -> b.projectId() == projectId).findFirst()
                .orElseThrow(() -> new AssertionError("no burn block for project " + projectId));
    }

    private static List<Long> idsOf(ProgramOverview got) {
        return got.projects().stream().map(ProjectPassRate::projectId).toList();
    }

    @Test
    void lensFeedCarriesOneRowPerLensProjectAndRegistryFootnotes(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        ProgramOverview got = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, null, null);

        assertEquals(List.of(s[0], s[1]), idsOf(got));
        assertEquals(CaliberService.codes().size(), got.caliberRegistry().size());
        assertTrue(got.caliberRegistry().stream()
                .allMatch(f -> !f.code().isBlank() && !f.displayName().isBlank()
                        && !f.version().isBlank()));
        assertTrue(got.periodFrom().isBefore(got.periodTo()));
    }

    @Test
    void feedHonoursOperatorVisibilityIntersection(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject(); // {a, b, lens, member}
        ProgramOverview got = programOverviewService.overview(s[2], s[3], null, null);
        assertEquals(List.of(s[0]), idsOf(got));
    }

    @Test
    void outsiderSeesAnEmptyFeedNotAnError(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        // Realistic outsider: a genuine user holding a role on an unrelated project
        // only, so the lens intersection (set projects ∩ visible domain) is empty.
        // freshUser() stays out — it has no tp_user row (fixture doc pins this).
        long strangerProject = fixture.newProject();
        long outsider = fixture.testLeadUser();
        fixture.direct(strangerProject, outsider, 1);
        ProgramOverview got = programOverviewService.overview(s[3], outsider, null, null);
        assertEquals(List.of(), idsOf(got));
    }

    @Test
    void unknownProgramRejectedWithNotFound() {
        assertThrows(ServiceException.class, () -> programOverviewService.overview(
                900000007L, ProgramLensFixture.OPERATOR, null, null));
    }

    @Test
    void nonPositiveProgramIdRejected() {
        assertThrows(ServiceException.class, () -> programOverviewService.overview(
                0L, ProgramLensFixture.OPERATOR, null, null));
    }

    @Test
    void explicitPeriodIsKeptAndBadTextRejected(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        ProgramOverview got = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, "2026-10-01T00:00", "2026-11-01T00:00");
        assertEquals(LocalDateTime.parse("2026-10-01T00:00"), got.periodFrom());
        assertEquals(LocalDateTime.parse("2026-11-01T00:00"), got.periodTo());
        assertThrows(ServiceException.class, () -> programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, "last week", "2026-11-01T00:00"));
    }

    /**
     * Defect burndown block per visible project: open backlog at window start,
     * created inside the window, closed inside the window, open at window end —
     * computed from current rows (status 5 = closed, updated_at rides the closing
     * transition, same open caliber as the dashboard precedent status &lt;&gt; 5).
     * A project with no defects still yields an honest all-zero row (a count of
     * zero is a fact, unlike a pass rate without denominator); projects outside
     * the lens never appear.
     */
    @Test
    void defectBurnBlockCountsWindowScopedPerVisibleProject(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects(); // {a, b visible; c outside; lens}
        String fromText = "2026-11-01T00:00";
        String toText = "2026-11-08T00:00";
        LocalDateTime from = LocalDateTime.parse(fromText);
        LocalDateTime to = LocalDateTime.parse(toText);
        // Project A — one row per counting branch:
        insertDefect(s[0], 2, from.minusDays(12), from.minusDays(12));
        insertDefect(s[0], 5, from.minusDays(7), from.plusDays(2));
        insertDefect(s[0], 1, from.plusDays(1), from.plusDays(1));
        insertDefect(s[0], 5, from.plusDays(3), from.plusDays(5));
        insertDefect(s[0], 1, to.plusDays(1), to.plusDays(1));
        // Project C is outside the lens — its defect must not surface anywhere.
        insertDefect(s[2], 1, from.plusDays(1), from.plusDays(1));

        ProgramOverview got = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, fromText, toText);

        assertEquals(List.of(s[0], s[1]),
                got.defectBurn().stream().map(DefectBurnWindow::projectId).toList(),
                "one burn block per visible project, none outside the lens");
        DefectBurnWindow a = burnOf(got, s[0]);
        assertEquals(2, a.openAtStart(), "backlog open at the window start");
        assertEquals(2, a.createdWithin(), "both defects created inside the window");
        assertEquals(2, a.closedWithin(), "both defects closed inside the window");
        assertEquals(2, a.openAtEnd(), "defect created after the window stays out");
        assertEquals(a.openAtStart() + a.createdWithin() - a.closedWithin(), a.openAtEnd(),
                "burn invariant: end = start + created - closed");
        DefectBurnWindow b = burnOf(got, s[1]);
        assertEquals(0L, b.openAtStart() + b.createdWithin() + b.closedWithin() + b.openAtEnd(),
                "a defect-free project reports an honest zero row, never a missing one");
    }

    /**
     * Insert one journey + one owned segment + one run (optionally touching that
     * segment) with pinned start time. Rows ride the fixture projects/program so
     * ProgramLensFixture.cleanup() (journey branch) tears them down (pitfall 14).
     * Segment target_kind='case' with a sentinel case id — the aggregate reads
     * ownership/status only, never expands the target.
     */
    private void insertJourneyRun(long programId, long projectId, LocalDateTime runStartedAt,
                                  int runStatus, boolean withSegment) {
        String journeyName = "jl-" + programId + "-" + projectId + "-" + runStartedAt + "-" + runStatus
                + "-" + withSegment + "-" + System.nanoTime();
        Long journeyId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_journey (program_id, name, owner_user_id, status, created_by)"
                        + " VALUES (?, ?, ?, 1, ?) RETURNING id",
                Long.class, programId, journeyName, ProgramLensFixture.OPERATOR, ProgramLensFixture.OPERATOR);
        Long segmentId = null;
        if (withSegment) {
            segmentId = jdbcTemplate.queryForObject(
                    "INSERT INTO tp_journey_segment (journey_id, seq_no, project_id, target_kind, case_id)"
                            + " VALUES (?, 1, ?, 'case', -1) RETURNING id",
                    Long.class, journeyId, projectId);
        }
        Long runId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_journey_run (journey_id, status, started_at, finished_at)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, journeyId, runStatus, runStartedAt, runStartedAt.plusHours(1));
        if (withSegment) {
            jdbcTemplate.update("INSERT INTO tp_journey_run_segment"
                            + " (run_id, segment_id, project_id, seq_no, status, started_at, finished_at)"
                            + " VALUES (?, ?, ?, 1, ?, ?, ?)",
                    runId, segmentId, projectId, runStatus == 2 ? 2 : runStatus == 4 ? 4 : 3,
                    runStartedAt, runStartedAt.plusHours(1));
        }
    }

    private static com.sixbean.testpike.core.journey.api.dto.JourneyTrafficLight
    lightOf(ProgramOverview got, long projectId) {
        return got.journeyTrafficLight().stream().filter(l -> l.projectId() == projectId).findFirst()
                .orElseThrow(() -> new AssertionError("no traffic light for project " + projectId));
    }

    /**
     * Traffic light per visible project = the status of its LATEST journey run
     * inside the window (2 succeeded → green, 1 running → amber, 3 failed → red),
     * grey when no run touched the project in the window (a cancelled latest run
     * also reads grey — cancellation is not an outcome). Runs counted per window
     * are only those whose segments belong to the project; a journey outside the
     * lens never surfaces, and a journey-free project still gets an honest
     * all-zero grey row in the same order as the pass-rate rows.
     */
    @Test
    void journeyTrafficLightFollowsLatestRunPerProjectInWindow(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects(); // {a, b visible; c outside; lens}
        String fromText = "2026-11-01T00:00";
        String toText = "2026-11-08T00:00";
        LocalDateTime from = LocalDateTime.parse(fromText);
        LocalDateTime to = LocalDateTime.parse(toText);
        // Project A: older succeeded run, then a failed one — the latest decides (red).
        insertJourneyRun(s[3], s[0], from.plusDays(1), 2, true);
        insertJourneyRun(s[3], s[0], from.plusDays(4), 3, true);
        // Project B: a cancelled run before the window + a running one inside → amber.
        insertJourneyRun(s[3], s[1], from.minusDays(3), 4, true);
        insertJourneyRun(s[3], s[1], to.minusHours(6), 1, true);
        // Project C is outside the lens — its green run must never appear.
        insertJourneyRun(s[3], s[2], from.plusDays(2), 2, true);

        ProgramOverview got = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, fromText, toText);

        assertEquals(List.of(s[0], s[1]),
                got.journeyTrafficLight().stream()
                        .map(com.sixbean.testpike.core.journey.api.dto.JourneyTrafficLight::projectId).toList(),
                "one light per visible project, none outside the lens");
        var a = lightOf(got, s[0]);
        assertEquals("red", a.light(), "latest in-window run failed");
        assertEquals(2, a.journeyCount(), "one journey per run here — the count dedups journeys, not runs");
        assertEquals(2, a.segmentCount(), "each of A's journeys owns exactly one segment in project A");
        assertEquals(2, a.runsInWindow(), "both runs of project A started inside the window");
        var b = lightOf(got, s[1]);
        assertEquals("amber", b.light(), "latest run is still executing");
        assertEquals(1, b.runsInWindow(), "the pre-window cancelled run stays out of the count");
        assertEquals(java.time.LocalDateTime.parse(toText).minusHours(6), b.latestRunAt(),
                "the light carries the moment of the run that decided it");
    }

    @Test
    void journeyLightIsHonestZeroRowWithoutRunsAndEmptyForOutsider(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        // No journey rows for project B; project A gets a finished green run.
        insertJourneyRun(s[3], s[0], LocalDateTime.parse("2026-11-02T00:00"), 2, true);
        ProgramOverview inside = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, "2026-11-01T00:00", "2026-11-08T00:00");
        assertEquals("green", lightOf(inside, s[0]).light());
        var b = lightOf(inside, s[1]);
        assertEquals("grey", b.light(), "no run in the window is an honest grey, not a missing row");
        assertEquals(0, b.runsInWindow() + b.journeyCount());

        long strangerProject = fixture.newProject();
        long outsider = fixture.testLeadUser();
        fixture.direct(strangerProject, outsider, 1);
        ProgramOverview outside = programOverviewService.overview(
                s[3], outsider, "2026-11-01T00:00", "2026-11-08T00:00");
        assertEquals(List.of(), outside.journeyTrafficLight(), "lights share the visible slice — no leakage");
    }

    @Test
    void outsiderSeesAnEmptyBurnListNotAnError(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        insertDefect(s[0], 1, LocalDateTime.parse("2026-11-02T00:00"), LocalDateTime.parse("2026-11-02T00:00"));
        long strangerProject = fixture.newProject();
        long outsider = fixture.testLeadUser();
        fixture.direct(strangerProject, outsider, 1);

        ProgramOverview got = programOverviewService.overview(
                s[3], outsider, "2026-11-01T00:00", "2026-11-08T00:00");

        assertEquals(List.of(), idsOf(got));
        assertEquals(List.of(), got.defectBurn(), "burn blocks share the visible slice — no leakage");
    }

    /** Bare load-test scenario owned by the project (no run) — the structural count is window-independent. */
    private long insertPerfScenario(long projectId, long envId) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_perf_scenario (project_id, env_id, name, pressure_model,"
                        + " pressure_config, steps_json) VALUES (?, ?, ?, 'CONSTANT_RPS',"
                        + " '{\"targetRps\":50}'::jsonb, '[]'::jsonb) RETURNING id",
                Long.class, projectId, envId, "ovw-s-" + System.nanoTime());
        return id == null ? 0L : id;
    }

    /**
     * Scenario + one run with pinned start time and status, plus optional second-level
     * metric frames (each triple = rps / p95 ms / error rate). Rows ride the fixture
     * projects so ProgramLensFixture.cleanup() (perf branch) tears them down (pitfall 14).
     */
    private long insertPerfRun(long projectId, long envId, LocalDateTime startedAt, int status,
                               double[][] frames) {
        long scenarioId = insertPerfScenario(projectId, envId);
        Long runId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_perf_run (scenario_id, project_id, status, pressure_snapshot,"
                        + " started_at, finished_at) VALUES (?, ?, ?, '{}'::jsonb, ?, ?) RETURNING id",
                Long.class, scenarioId, projectId, status, startedAt, startedAt.plusMinutes(5));
        for (int i = 0; i < frames.length; i++) {
            jdbcTemplate.update("INSERT INTO tp_perf_metric (run_id, project_id, second_offset,"
                            + " rps, p95_ms, error_rate) VALUES (?, ?, ?, ?, ?, ?)",
                    runId, projectId, i, frames[i][0], frames[i][1], frames[i][2]);
        }
        return runId == null ? 0L : runId;
    }

    private static com.sixbean.testpike.core.perf.api.dto.PerfOverview
    perfOf(ProgramOverview got, long projectId) {
        return got.perfOverview().stream().filter(p -> p.projectId() == projectId).findFirst()
                .orElseThrow(() -> new AssertionError("no perf overview block for project " + projectId));
    }

    /**
 * Perf overview per visible project = the verdict of its LATEST load-test run
 * inside the window (2 normal end → green, 1 running → amber, 3 circuit-break stop
 * → red; 4 manually stopped and no run at all read grey — a manual stop is not an
 * outcome), plus scenario/run counts and the peak throughput / peak p95 / peak
 * error rate of the frames belonging to in-window runs only. Peak readings are the
 * worst frame seen in the window, never a mean, and frames of a pre-window run
 * stay out of them. Blocks share the visible segment in the same order as the other
 * blocks; a project outside the lens never surfaces.
 */
    @Test
    void perfOverviewFollowsLatestRunPerProjectInWindow(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreePerfProjects(); // {a, b, outside, lens, envA, envB, envOutside}
        LocalDateTime from = LocalDateTime.parse("2026-11-01T00:00");
        LocalDateTime to = LocalDateTime.parse("2026-11-08T00:00");
        // Project A: a normal-ended run, then a circuit-break run — the latest decides (red).
        insertPerfRun(s[0], s[4], from.plusDays(1), 2, new double[][] {{20, 120, 0.02}});
        insertPerfRun(s[0], s[4], from.plusDays(4), 3, new double[][] {{15, 200, 0.05}, {40, 300, 0.50}});
        // Project B: a finished run before the window (out of every reading) + a manually
        // stopped run inside → grey, and its 90 rps frame must not raise the peaks.
        insertPerfRun(s[1], s[5], from.minusDays(3), 2, new double[][] {{90, 900, 0.90}});
        insertPerfRun(s[1], s[5], to.minusHours(6), 4, new double[][] {{5, 50, 0.00}});
        // The project outside the lens has a green run — it must never surface.
        insertPerfRun(s[2], s[6], from.plusDays(2), 2, new double[][] {{30, 330, 0.10}});

        ProgramOverview got = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, "2026-11-01T00:00", "2026-11-08T00:00");

        assertEquals(List.of(s[0], s[1]),
                got.perfOverview().stream()
                        .map(com.sixbean.testpike.core.perf.api.dto.PerfOverview::projectId).toList(),
                "one perf block per visible project, none outside the lens");
        var a = perfOf(got, s[0]);
        assertEquals("red", a.light(), "the latest in-window run was stopped by the circuit breaker");
        assertEquals(2, a.runsInWindow(), "both runs of project A started inside the window");
        assertEquals(2, a.scenarioCount(), "each run came from its own scenario");
        assertEquals(from.plusDays(4), a.latestRunAt(), "the block carries the moment of the deciding run");
        assertEquals(40, a.peakRps().doubleValue(), 1e-6, "peak throughput of the window frames");
        assertEquals(300, a.peakP95Ms().doubleValue(), 1e-6, "worst p95 across the window frames");
        assertEquals(0.50, a.peakErrorRate().doubleValue(), 1e-6, "worst error rate across the window frames");
        var b = perfOf(got, s[1]);
        assertEquals("grey", b.light(), "a manual stop is not a verdict");
        assertEquals(1, b.runsInWindow(), "the pre-window run stays out of the count");
        assertEquals(to.minusHours(6), b.latestRunAt());
        assertEquals(5, b.peakRps().doubleValue(), 1e-6, "peak readings only cover in-window runs");
        assertEquals(0.00, b.peakErrorRate().doubleValue(), 1e-6);
    }

    /**
     * A project with scenarios but no run inside the window rides an honest quiet grey
     * row (structural scenario count kept, run readings zero, peaks null — a fact, not
     * a missing row), and an operator who cannot see any lens project gets an empty list.
     */
    @Test
    void perfOverviewIsHonestQuietRowWithoutRunsAndEmptyForOutsider(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreePerfProjects();
        insertPerfRun(s[0], s[4], LocalDateTime.parse("2026-11-02T00:00"), 2,
                new double[][] {{10, 100, 0.01}});
        insertPerfScenario(s[1], s[5]);
        insertPerfScenario(s[1], s[5]);

        ProgramOverview inside = programOverviewService.overview(
                s[3], ProgramLensFixture.OPERATOR, "2026-11-01T00:00", "2026-11-08T00:00");

        var a = perfOf(inside, s[0]);
        assertEquals("green", a.light(), "the only in-window run ended normally");
        assertNotNull(a.latestRunId(), "the block carries the deciding run id for drill-down");
        var b = perfOf(inside, s[1]);
        assertEquals("grey", b.light(), "no run in the window is an honest grey, not a missing row");
        assertEquals(2, b.scenarioCount(), "the structural count is independent of the window");
        assertEquals(0, b.runsInWindow());
        assertNull(b.latestRunId(), "no deciding run means no id to drill into");
        assertNull(b.latestRunAt());
        assertNull(b.peakRps(), "no frames means no reading, never a fabricated zero");
        assertNull(b.peakP95Ms());
        assertNull(b.peakErrorRate());

        long strangerProject = fixture.newProject();
        long outsider = fixture.testLeadUser();
        fixture.direct(strangerProject, outsider, 1);
        ProgramOverview outside = programOverviewService.overview(
                s[3], outsider, "2026-11-01T00:00", "2026-11-08T00:00");
        assertEquals(List.of(), outside.perfOverview(), "perf blocks share the visible slice — no leakage");
    }
}
