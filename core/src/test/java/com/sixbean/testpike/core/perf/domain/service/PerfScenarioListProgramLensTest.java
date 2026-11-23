package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioPageView;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paged perf-scenario list through the program lens (project-set narrowing, fourth
 * consumer of the ProgramLensScope single source after case/batch/defect/API-test).
 *
 * <p>Contract under test: programId &lt;= 0 keeps legacy behaviour untouched (one scope
 * anchor — explicit project OR lens — is now required, neither stays a 400); a lens
 * narrows to its projects ∩ the caller's visibility; lens plus explicit project stays
 * conjunctive; an unreachable lens yields an honest empty page, never other projects'
 * rows and never 403. Rows ride in through the real create door (env ownership and
 * pressure-model validation are exercised exactly as production).
 *
 * <p>Lens/project/user/env fixture assembly and all fixture-row teardown come from
 * ProgramLensFixture (single source, R40); this class only wires domain rows through
 * the real service channel. Shipped tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class PerfScenarioListProgramLensTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfDatasetService perfDatasetService;

    /** One CONSTANT_RPS scenario created through the real service door. */
    private long newScenario(long projectId, long envId, String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(name, envId,
                "CONSTANT_RPS", Map.of("targetRps", 50, "durationSeconds", 600),
                null, 5000, List.of(Map.of("action", "http.request", "method", "POST",
                        "url", "{{ENV:BASE}}/orders")), null));
    }

    private PerfScenarioPageView lensPage(long operatorId, long programId) {
        return perfScenarioService.listPaged(operatorId, programId, 0L, 1, 50, null, null);
    }

    @Test
    void lensNarrowsTheCrossProjectScenarioListToItsOwnProjects(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreePerfProjects();
        newScenario(s[0], s[4], "lens scenario a");
        newScenario(s[1], s[5], "lens scenario b");
        newScenario(s[2], s[6], "outside scenario");

        PerfScenarioPageView got = lensPage(ProgramLensFixture.OPERATOR, s[3]);

        assertEquals(2, got.total(), "both lens projects, none outside");
        assertTrue(got.list().stream().noneMatch(x -> "outside scenario".equals(x.get("name"))));
    }

    @Test
    void lensAndExplicitProjectStayConjunctive(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreePerfProjects();
        newScenario(s[0], s[4], "conjunctive a");
        newScenario(s[1], s[5], "conjunctive b");

        PerfScenarioPageView got = perfScenarioService.listPaged(
                ProgramLensFixture.OPERATOR, s[3], s[0], 1, 50, null, null);

        assertEquals(1, got.total(), "explicit project wins within the lens");
        assertEquals("conjunctive a", got.list().get(0).get("name"));
    }

    @Test
    void memberOfOneLensProject_seesOnlyThatProjectThroughTheLens(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject();
        newScenario(s[0], fixture.perfEnv(s[0]), "member sees");
        newScenario(s[1], fixture.perfEnv(s[1]), "member must not see");

        PerfScenarioPageView got = lensPage(s[3], s[2]);

        assertEquals(1, got.total(), "visibility intersects the lens down to project a");
        assertEquals("member sees", got.list().get(0).get("name"));
    }

    @Test
    void unreachableLens_returnsEmptyPageNotAnError(ProgramLensFixture fixture) {
        long lens = fixture.lensWithProjects(fixture.newProject());
        long stranger = fixture.testLeadUser();

        PerfScenarioPageView got = lensPage(stranger, lens);

        assertEquals(0, got.total(), "no leak, no 403 — an honest empty page");
    }

    @Test
    void legacyCallersWithoutLensKeepCurrentBehaviour(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        newScenario(a, fixture.perfEnv(a), "legacy scenario");
        newScenario(b, fixture.perfEnv(b), "other project scenario");

        PerfScenarioPageView got = perfScenarioService.listPaged(
                ProgramLensFixture.OPERATOR, 0L, a, 1, 20, null, null);

        assertEquals(1, got.total(), "programId absent behaves exactly as before");
        assertEquals("legacy scenario", got.list().get(0).get("name"));
    }

    @Test
    void missingProjectAndMissingLens_isStillRejected(ProgramLensFixture fixture) {
        // The anchor rule relaxes to "one scope anchor required": neither project nor
        // lens stays an honest validation error, never a cross-tenant dump.
        assertThrows(ServiceException.class, () -> perfScenarioService.listPaged(
                ProgramLensFixture.OPERATOR, 0L, 0L, 1, 20, null, null));
    }

    @Test
    void lensNarrowsTheCrossProjectDatasetListToItsOwnProjects(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreePerfProjects();
        fixture.perfDataset(s[0], "lens pool a");
        fixture.perfDataset(s[1], "lens pool b");
        fixture.perfDataset(s[2], "outside pool");

        PerfDatasetPageView got = perfDatasetService.listPaged(
                ProgramLensFixture.OPERATOR, s[3], 0L, 1, 50, null, null);

        assertEquals(2, got.total(), "both lens projects, none outside");
        assertTrue(got.list().stream().noneMatch(x -> "outside pool".equals(x.get("name"))));
    }

    @Test
    void datasetUnreachableLens_returnsEmptyPageNotAnError(ProgramLensFixture fixture) {
        long lens = fixture.lensWithProjects(fixture.newProject());
        long stranger = fixture.testLeadUser();

        PerfDatasetPageView got = perfDatasetService.listPaged(stranger, lens, 0L, 1, 20, null, null);

        assertEquals(0, got.total(), "no leak, no 403 — an honest empty page");
    }
}
