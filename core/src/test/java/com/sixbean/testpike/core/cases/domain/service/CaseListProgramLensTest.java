package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case list through the program lens (backend filtering segment, first consumer of the
 * ProgramLensScope single source).
 *
 * <p>The lens is an optional narrowing on the list endpoint: programId &lt;= 0 keeps the
 * legacy behaviour untouched; a lens narrows to its projects ∩ the caller's visibility;
 * combining the lens with an explicit project stays conjunctive; a lens the caller can
 * not reach returns an honest empty page, never other projects' rows and never 403.
 *
 * <p>Lens/project/user fixture assembly and all fixture-row teardown come from
 * ProgramLensFixture (single source, R40); only rows under fixture-named projects of
 * this chain's own slot DB are touched (no TRUNCATE of shipped tables).
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class CaseListProgramLensTest {

    @Autowired
    private CaseService caseService;

    private CaseCreateInput input(long projectId, String title) {
        return ProgramLensFixture.caseInput(projectId, title);
    }

    private CaseQuery crossProjectLensQuery(long programId) {
        return new CaseQuery(0, null, null, null, null, null, null, null, null, null,
                1, 50, null, programId);
    }

    @Test
    void lensNarrowsTheCrossProjectListToItsOwnProjects(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        caseService.create(ProgramLensFixture.OPERATOR, input(s[0], "lens case a"));
        caseService.create(ProgramLensFixture.OPERATOR, input(s[1], "lens case b"));
        long outsideCase = caseService.create(ProgramLensFixture.OPERATOR,
                input(s[2], "outside case")).id();

        PageResult<CaseInfo> got = caseService.list(crossProjectLensQuery(s[3]),
                ProgramLensFixture.OPERATOR);

        assertEquals(2, got.total(), "both lens projects, none outside");
        assertTrue(got.list().stream().noneMatch(c -> c.id() == outsideCase));
    }

    @Test
    void lensAndExplicitProjectStayConjunctive(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        long lens = fixture.lensWithProjects(a, b);
        caseService.create(ProgramLensFixture.OPERATOR, input(a, "conjunctive a"));
        caseService.create(ProgramLensFixture.OPERATOR, input(b, "conjunctive b"));

        PageResult<CaseInfo> got = caseService.list(
                new CaseQuery(a, null, null, null, null, null, null, null, null, null,
                        1, 50, null, lens),
                ProgramLensFixture.OPERATOR);

        assertEquals(1, got.total(), "explicit project wins within the lens");
        assertEquals("conjunctive a", got.list().get(0).title());
    }

    @Test
    void memberOfOneLensProject_seesOnlyThatProjectThroughTheLens(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject();
        caseService.create(ProgramLensFixture.OPERATOR, input(s[0], "member sees"));
        caseService.create(ProgramLensFixture.OPERATOR, input(s[1], "member must not see"));

        PageResult<CaseInfo> got = caseService.list(crossProjectLensQuery(s[2]), s[3]);

        assertEquals(1, got.total(), "visibility intersects the lens down to project a");
        assertEquals("member sees", got.list().get(0).title());
    }

    @Test
    void unreachableLens_returnsEmptyPageNotAnError(ProgramLensFixture fixture) {
        long lens = fixture.lensWithProjects(fixture.newProject());
        long stranger = fixture.testLeadUser();

        PageResult<CaseInfo> got = caseService.list(crossProjectLensQuery(lens), stranger);

        assertEquals(0, got.total(), "no leak, no 403 — an honest empty page");
    }

    @Test
    void legacyCallersWithoutLensKeepCurrentBehaviour(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        caseService.create(ProgramLensFixture.OPERATOR, input(a, "legacy case"));

        PageResult<CaseInfo> got = caseService.list(
                new CaseQuery(a, null, null, null, null, null, null, null, null, null,
                        1, 50),
                ProgramLensFixture.OPERATOR);

        assertEquals(1, got.total(), "programId absent behaves exactly as before");
    }
}
