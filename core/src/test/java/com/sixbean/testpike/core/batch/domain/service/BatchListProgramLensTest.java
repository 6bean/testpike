package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchQuery;
import com.sixbean.testpike.core.batch.domain.service.impl.BatchServiceImpl;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
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
 * Batch list through the program lens (project-set narrowing, second consumer of the
 * ProgramLensScope single source after the case domain).
 *
 * <p>Contract under test: programId &lt;= 0 keeps legacy behaviour untouched; a lens
 * narrows to its projects ∩ the caller's visibility; lens plus explicit project stays
 * conjunctive; a lens the caller cannot reach yields an honest empty page, never other
 * projects' rows and never 403. Temporary batches (type=2) stay out of every view —
 * the lens must not change that semantics.
 *
 * <p>Lens/project/user fixture assembly and all fixture-row teardown come from
 * ProgramLensFixture (single source, R40) — this class only wires domain rows through
 * the real service channels. Shipped tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class BatchListProgramLensTest {

    @Autowired
    private BatchService batchService;

    /** Concrete type on purpose: the temporary-batch channel lives on the impl only. */
    @Autowired
    private BatchServiceImpl batchServiceImpl;

    @Autowired
    private CaseService caseService;

    /** Creates one case through the real service and returns its id. */
    private long newCase(long projectId, String title) {
        return caseService.create(ProgramLensFixture.OPERATOR,
                ProgramLensFixture.caseInput(projectId, title)).id();
    }

    /** Creates a formal draft batch through the real create channel (name + one case). */
    private long newBatch(long projectId, String name) {
        long caseId = newCase(projectId, name + " case");
        return batchService.create(ProgramLensFixture.OPERATOR,
                new BatchCreateInput(projectId, name, null, List.of(caseId), null)).id();
    }

    private BatchQuery crossProjectLensQuery(long programId) {
        return new BatchQuery(0, null, null, 1, 50, null, null, programId);
    }

    @Test
    void lensNarrowsTheCrossProjectListToItsOwnProjects(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        newBatch(s[0], "lens batch a");
        newBatch(s[1], "lens batch b");
        newBatch(s[2], "outside batch");

        PageResult<BatchInfo> got = batchService.list(crossProjectLensQuery(s[3]),
                ProgramLensFixture.OPERATOR);

        assertEquals(2, got.total(), "both lens projects, none outside");
        assertTrue(got.list().stream().noneMatch(x -> "outside batch".equals(x.name())));
    }

    @Test
    void lensAndExplicitProjectStayConjunctive(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        long lens = fixture.lensWithProjects(a, b);
        newBatch(a, "conjunctive a");
        newBatch(b, "conjunctive b");

        PageResult<BatchInfo> got = batchService.list(
                new BatchQuery(a, null, null, 1, 50, null, null, lens),
                ProgramLensFixture.OPERATOR);

        assertEquals(1, got.total(), "explicit project wins within the lens");
        assertEquals("conjunctive a", got.list().get(0).name());
    }

    @Test
    void memberOfOneLensProject_seesOnlyThatProjectThroughTheLens(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject();
        newBatch(s[0], "member sees");
        newBatch(s[1], "member must not see");

        PageResult<BatchInfo> got = batchService.list(crossProjectLensQuery(s[2]), s[3]);

        assertEquals(1, got.total(), "visibility intersects the lens down to project a");
        assertEquals("member sees", got.list().get(0).name());
    }

    @Test
    void unreachableLens_returnsEmptyPageNotAnError(ProgramLensFixture fixture) {
        long lens = fixture.lensWithProjects(fixture.newProject());
        long stranger = fixture.testLeadUser();

        PageResult<BatchInfo> got = batchService.list(crossProjectLensQuery(lens), stranger);

        assertEquals(0, got.total(), "no leak, no 403 — an honest empty page");
    }

    @Test
    void legacyCallersWithoutLensKeepCurrentBehaviour(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        newBatch(a, "legacy batch");

        PageResult<BatchInfo> got = batchService.list(
                new BatchQuery(a, null, null, 1, 50));

        assertEquals(1, got.total(), "programId absent behaves exactly as before");
    }

    @Test
    void tempBatchesStayHiddenUnderTheLens(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long lens = fixture.lensWithProjects(a);
        long formal = newBatch(a, "formal under lens");
        // Temporary batch (type=2) through the real fast-execution channel (same call
        // shape the executor uses) — the default view must keep hiding it under a lens.
        CaseInfo c = caseService.get(newCase(a, "temp carrier"));
        batchServiceImpl.createTempBatch(ProgramLensFixture.OPERATOR, c.id(), null, c);
        assertTrue(formal > 0);

        PageResult<BatchInfo> got = batchService.list(crossProjectLensQuery(lens),
                ProgramLensFixture.OPERATOR);

        assertEquals(1, got.total(), "the lens must not surface temporary batches");
        assertEquals("formal under lens", got.list().get(0).name());
    }
}
