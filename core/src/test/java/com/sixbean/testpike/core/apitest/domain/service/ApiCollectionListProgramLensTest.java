package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
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
 * API collection tree through the program lens (project-set narrowing, fourth
 * consumer of the ProgramLensScope single source after the case, batch and
 * defect domains).
 *
 * <p>Contract under test: programId &lt;= 0 keeps the legacy one-argument
 * behaviour untouched; a lens widens one endpoint across its projects ∩ the
 * caller's visibility, returning a multi-root forest (collections carry their
 * own projectId, two-level assembly stays per project); lens plus explicit
 * project stays conjunctive; an unreachable lens yields an honest empty forest,
 * never other projects' rows and never an error.
 *
 * <p>Lens/project/user fixture assembly and all fixture-row teardown come from
 * ProgramLensFixture (single source, R40); this class only wires domain rows
 * through the real service channel. Shipped tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ApiCollectionListProgramLensTest {

    @Autowired
    private ApiCollectionService apiCollectionService;

    /** One root collection created through the real service channel. */
    private long newCollection(long projectId, String name) {
        return apiCollectionService.create(ProgramLensFixture.OPERATOR,
                new CollectionSaveInput(projectId, null, name, null, null, null)).id();
    }

    /** One child collection mounted under a root (two-level tree cap). */
    private long newChild(long projectId, long parentId, String name) {
        return apiCollectionService.create(ProgramLensFixture.OPERATOR,
                new CollectionSaveInput(projectId, parentId, name, null, null, null)).id();
    }

    private List<ApiCollectionInfo> lensTree(long programId, long operatorId) {
        return apiCollectionService.list(0L, programId, operatorId);
    }

    @Test
    void lensNarrowsTheCrossProjectForestToItsOwnProjects(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        newCollection(s[0], "lens collection a");
        newCollection(s[1], "lens collection b");
        newCollection(s[2], "outside collection");

        List<ApiCollectionInfo> got = lensTree(s[3], ProgramLensFixture.OPERATOR);

        assertEquals(2, got.size(), "both lens projects, none outside");
        assertTrue(got.stream().noneMatch(x -> "outside collection".equals(x.name())));
    }

    @Test
    void lensAndExplicitProjectStayConjunctive(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        long lens = fixture.lensWithProjects(a, b);
        newCollection(a, "conjunctive a");
        newCollection(b, "conjunctive b");

        List<ApiCollectionInfo> got =
                apiCollectionService.list(a, lens, ProgramLensFixture.OPERATOR);

        assertEquals(1, got.size(), "explicit project wins within the lens");
        assertEquals("conjunctive a", got.get(0).name());
    }

    @Test
    void memberOfOneLensProject_seesOnlyThatProjectThroughTheLens(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject();
        newCollection(s[0], "member sees");
        newCollection(s[1], "member must not see");

        List<ApiCollectionInfo> got = lensTree(s[2], s[3]);

        assertEquals(1, got.size(), "visibility intersects the lens down to the member's project");
        assertEquals("member sees", got.get(0).name());
    }

    @Test
    void unreachableLens_returnsEmptyForestNotAnError(ProgramLensFixture fixture) {
        long lens = fixture.lensWithProjects(fixture.newProject());
        long stranger = fixture.testLeadUser();

        assertTrue(lensTree(lens, stranger).isEmpty(), "no leak, no error — an honest empty forest");
    }

    @Test
    void legacyCallersWithoutLensKeepCurrentBehaviour(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        newCollection(a, "legacy collection");
        newCollection(b, "other project collection");

        List<ApiCollectionInfo> got = apiCollectionService.list(a);

        assertEquals(1, got.size(), "one-argument form behaves exactly as before");
        assertEquals("legacy collection", got.get(0).name());
    }

    @Test
    void crossProjectLensKeepsTwoLevelAssemblyPerProjectRoot(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        long rootA = newCollection(s[0], "forest root a");
        newChild(s[0], rootA, "forest child a");
        long rootB = newCollection(s[1], "forest root b");
        newChild(s[1], rootB, "forest child b");

        List<ApiCollectionInfo> got = lensTree(s[3], ProgramLensFixture.OPERATOR);

        assertEquals(2, got.size(), "multi-root forest: one root per lens project");
        assertEquals(2, got.stream().filter(r -> r.children().size() == 1).count(),
                "two-level assembly survives across projects");
        assertTrue(got.stream().allMatch(r -> r.projectId() == s[0] || r.projectId() == s[1]),
                "every root carries its own project id");
    }
}
