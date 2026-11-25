package com.sixbean.testpike.core.reportcenter.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.program.ProgramLensFixture;
import com.sixbean.testpike.core.program.ProgramLensFixtureExtension;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportListItem;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Report snapshot list through the program lens (project-set narrowing, fifth domain
 * consumer of the ProgramLensScope single source after case/batch/defect/API/perf).
 *
 * <p>Contract under test: programId &lt;= 0 keeps the legacy cross-scope view untouched;
 * a lens keeps only project-dimension snapshots whose scope_id sits inside
 * lens projects ∩ caller visibility (global rows scope_id=0 naturally fall outside);
 * lens plus explicit scopeId stays conjunctive; a lens the caller cannot reach yields
 * an honest empty page, never other projects' rows and never an error.
 *
 * <p>Lens/project/user fixture assembly and teardown come from ProgramLensFixture
 * (single source, R40); snapshot rows are created through the real generate channel.
 * Shipped tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@ExtendWith(ProgramLensFixtureExtension.class)
class ReportListProgramLensTest {

    @Autowired
    private ReportCenterService reportCenterService;

    /** Last complete calendar week [previous Monday 00:00, this Monday 00:00). */
    private static String[] lastWeek() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new String[]{monday.minusWeeks(1).atStartOfDay().toString(),
                monday.atStartOfDay().toString()};
    }

    private long seedProjectWeekly(long projectId) {
        String[] w = lastWeek();
        return reportCenterService.generate(ProgramLensFixture.OPERATOR,
                new ReportGenerateInput(1, 1, projectId, w[0], w[1])).id();
    }

    private long seedGlobalMonthly() {
        LocalDateTime mStart = LocalDateTime.now().withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0).minusMonths(1);
        return reportCenterService.generate(ProgramLensFixture.OPERATOR,
                new ReportGenerateInput(2, 2, null, mStart.toString(),
                        mStart.plusMonths(1).toString())).id();
    }

    private static ReportQuery lensList(long programId) {
        return new ReportQuery(1, 50, null, null, null, null, null, null, null, programId);
    }

    @Test
    void lensKeepsOnlyItsOwnProjectSnapshotsAndLeavesGlobalRowsOut(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        seedProjectWeekly(s[0]);
        seedProjectWeekly(s[1]);
        seedProjectWeekly(s[2]);
        seedGlobalMonthly();

        PageResult<ReportListItem> got = reportCenterService.list(
                lensList(s[3]), ProgramLensFixture.OPERATOR);

        assertEquals(2, got.total(), "both lens projects, no outside project, no global row");
        assertTrue(got.list().stream().allMatch(r -> r.scopeType() == 1
                && (r.scopeId() == s[0] || r.scopeId() == s[1])));
    }

    @Test
    void lensAndExplicitScopeIdStayConjunctive(ProgramLensFixture fixture) {
        long a = fixture.newProject();
        long b = fixture.newProject();
        long lens = fixture.lensWithProjects(a, b);
        seedProjectWeekly(a);
        seedProjectWeekly(b);

        PageResult<ReportListItem> got = reportCenterService.list(
                new ReportQuery(1, 50, null, null, a, null, null, null, null, lens),
                ProgramLensFixture.OPERATOR);

        assertEquals(1, got.total(), "the picked project inside the lens only");
        assertEquals(a, got.list().get(0).scopeId());
    }

    @Test
    void memberOfOneLensProjectSeesOnlyThatSliceThroughTheLens(ProgramLensFixture fixture) {
        long[] s = fixture.lensWithMemberOfFirstProject();
        seedProjectWeekly(s[0]);
        seedProjectWeekly(s[1]);

        PageResult<ReportListItem> got = reportCenterService.list(lensList(s[2]), s[3]);

        assertEquals(1, got.total(), "visibility narrows the lens to project a");
        assertEquals(s[0], got.list().get(0).scopeId());
    }

    @Test
    void lensTheCallerCannotReachYieldsAnHonestEmptyPage(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        seedProjectWeekly(s[0]);
        long nobody = fixture.testLeadUser();

        PageResult<ReportListItem> got = reportCenterService.list(lensList(s[3]), nobody);

        assertEquals(0, got.total(), "unreachable lens never leaks foreign rows");
        assertTrue(got.list().isEmpty());
    }

    @Test
    void noLensKeepsTheLegacyCrossScopeViewUntouched(ProgramLensFixture fixture) {
        long[] s = fixture.lensOverTwoOfThreeProjects();
        List<Long> seeded = List.of(seedProjectWeekly(s[0]), seedProjectWeekly(s[1]),
                seedProjectWeekly(s[2]), seedGlobalMonthly());

        // the legacy window orders by reporting period, so a freshly seeded
        // fixture row (especially the old global-monthly one) can legitimately
        // sit behind live-period rows on page one; the untouched-view contract
        // is that the whole ledger stays reachable — verified by walking pages
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long total = -1;
        for (int page = 1; page <= 20; page++) {
            PageResult<ReportListItem> got = reportCenterService.list(
                    new ReportQuery(page, 50, null, null, null, null, null, null, null, 0L),
                    ProgramLensFixture.OPERATOR);
            if (total < 0) {
                total = got.total();
            }
            got.list().forEach(r -> seen.add(r.id()));
            if (got.list().isEmpty() || seen.size() >= total) {
                break;
            }
        }
        assertTrue(seen.containsAll(seeded),
                "programId=0 leaves the historical unfiltered view intact");
    }
}
