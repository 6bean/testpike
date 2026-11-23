package com.sixbean.testpike.core.defects.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.defects.api.dto.DefectQuery;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defect list through the program lens (project-set narrowing, third consumer of the
 * ProgramLensScope single source after the case and batch domains).
 *
 * <p>Contract under test: programId &lt;= 0 keeps legacy behaviour untouched (a missing
 * project AND a missing lens stays a 400 — only one of them is now required); a lens
 * narrows to its projects ∩ the caller's visibility; lens plus explicit project stays
 * conjunctive; an unreachable lens yields an honest empty page, never other projects'
 * rows and never 403. The shared DefectFilters source keeps list and export on one
 * WHERE — export does not eat the lens this wave (it passes null).
 *
 * <p>Lens/project/user fixture assembly and all fixture-row teardown come from
 * ProgramLensFixture (single source, R40); this class only wires domain rows through
 * the real service channel. Shipped tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class DefectListProgramLensTest {

    @Autowired
    private DefectLibraryService defectLibraryService;

    /** One defect created through the real service (title is the only required field). */
    private long newDefect(long projectId, String title) {
        return defectLibraryService.create(ProgramLensFixture.OPERATOR,
                new DefectCreateInput(projectId, null, null, title, null, null, null, null, null)).id();
    }

    private DefectQuery crossProjectLensQuery(long programId) {
        return new DefectQuery(0L, null, null, null, null, null, 1, 50, null, null, programId);
    }

    @Test
    void lensNarrowsTheCrossProjectDefectListToItsOwnProjects(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        newDefect(s[0], "lens defect a");
        newDefect(s[1], "lens defect b");
        newDefect(s[2], "outside defect");

        PageResult<DefectLibraryInfo> got =
                defectLibraryService.list(ProgramLensFixture.OPERATOR, crossProjectLensQuery(s[3]));

        assertEquals(2, got.total(), "both lens projects, none outside");
        assertTrue(got.list().stream().noneMatch(x -> "outside defect".equals(x.title())));
    }

    @Test
    void lensAndExplicitProjectStayConjunctive(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        long lens = fixture.lensWithProjects(a, b);
        newDefect(a, "conjunctive a");
        newDefect(b, "conjunctive b");

        PageResult<DefectLibraryInfo> got = defectLibraryService.list(ProgramLensFixture.OPERATOR,
                new DefectQuery(a, null, null, null, null, null, 1, 50, null, null, lens));

        assertEquals(1, got.total(), "explicit project wins within the lens");
        assertEquals("conjunctive a", got.list().get(0).title());
    }

    @Test
    void memberOfOneLensProject_seesOnlyThatProjectThroughTheLens(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject();
        newDefect(s[0], "member sees");
        newDefect(s[1], "member must not see");

        PageResult<DefectLibraryInfo> got =
                defectLibraryService.list(s[3], crossProjectLensQuery(s[2]));

        assertEquals(1, got.total(), "visibility intersects the lens down to project a");
        assertEquals("member sees", got.list().get(0).title());
    }

    @Test
    void unreachableLens_returnsEmptyPageNotAnError(ProgramLensFixture fixture) {
        long lens = fixture.lensWithProjects(fixture.newProject());
        long stranger = fixture.testLeadUser();

        PageResult<DefectLibraryInfo> got = defectLibraryService.list(stranger, crossProjectLensQuery(lens));

        assertEquals(0, got.total(), "no leak, no 403 — an honest empty page");
    }

    @Test
    void legacyCallersWithoutLensKeepCurrentBehaviour(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        newDefect(a, "legacy defect");
        newDefect(b, "other project defect");

        PageResult<DefectLibraryInfo> got = defectLibraryService.list(ProgramLensFixture.OPERATOR,
                new DefectQuery(a, null, null, null, null, null, 1, 20));

        assertEquals(1, got.total(), "programId absent behaves exactly as before");
        assertEquals("legacy defect", got.list().get(0).title());
    }

    @Test
    void missingProjectAndMissingLens_isStillRejected(ProgramLensFixture fixture) {
        // The 400 only relaxes to "one scope anchor required": neither project nor lens
        // stays an honest validation error, never a cross-tenant dump.
        assertThrows(ServiceException.class, () -> defectLibraryService.list(
                ProgramLensFixture.OPERATOR,
                new DefectQuery(0L, null, null, null, null, null, 1, 20, null, null, 0L)));
    }
}
