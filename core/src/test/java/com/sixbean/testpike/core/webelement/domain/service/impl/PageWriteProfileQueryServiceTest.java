package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.sixbean.testpike.common.webelement.PageWriteProfilePageView;
import com.sixbean.testpike.common.webelement.PageWriteProfileView;
import com.sixbean.testpike.common.webelement.PageWriteRiskView;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.webelement.domain.service.PageWriteProfileQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Page write-risk ledger query surface : the screen
 * and the kernel prompt must be able to list a project's known write-surface
 * pages without re-running a survey. Contracts pinned here: newest activity
 * first, honest paged totals (S-group worst-scale), page-url keyword filter,
 * strict project isolation (another project's rows never leak into the segment),
 * and the booked JSON windows decoded to wire shape with the sticky
 * snapshot-only flag intact. Real dev database, no mocks. Cleanup deletes
 * only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PageWriteProfileQueryServiceTest {

    @Autowired
    private PageWriteProfileQueryService queryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;

    private final List<Long> projects = new ArrayList<>();

    private long riskProject() {
        return ProjectFixtureRig.createTracked(jdbcTemplate, projectService, projects, "写风险账查询");
    }

    private void book(long projectId, String path, String writeEvents, String netFailures,
                      boolean snapshotOnly, int minutesAgo) {
        jdbcTemplate.update("""
                INSERT INTO tp_page_write_profile
                    (project_id, page_key, page_url, write_surface, write_events,
                     net_failures, snapshot_only, last_seen_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, now() - (? * interval '1 minute'))
                """,
                projectId, "http://risk-ledger.test" + path, "http://risk-ledger.test" + path,
                snapshotOnly ? "[\"POST\"]" : "[]", writeEvents, netFailures, snapshotOnly,
                minutesAgo);
    }

    @AfterEach
    void drainFixtures() {
        ProjectFixtureRig.drainLedgerTables(jdbcTemplate, projects, "tp_page_write_profile");
    }

    @Test
    void pagedSliceIsNewestFirstWithHonestTotalsAndKeywordFilter() {
        long projectId = riskProject();
        book(projectId, "/notes", "[{\"stepNo\":3,\"method\":\"POST\"}]", "[]", true, 1);
        book(projectId, "/drafts", "[]", "[{\"url\":\"http://risk-ledger.test/api/search\",\"status\":500}]", false, 2);
        book(projectId, "/archive", "[]", "[]", false, 3);

        PageWriteProfilePageView firstPage = queryService.pageByProject(projectId, 1, 2, null);
        assertEquals(3, firstPage.total());
        assertEquals(2, firstPage.list().size());
        assertTrue(firstPage.list().get(0).pageUrl().endsWith("/notes"));
        assertTrue(firstPage.list().get(1).pageUrl().endsWith("/drafts"));
        assertEquals(1, firstPage.page());
        assertEquals(2, firstPage.pageSize());

        PageWriteProfilePageView secondPage = queryService.pageByProject(projectId, 2, 2, null);
        assertEquals(3, secondPage.total());
        assertEquals(1, secondPage.list().size());
        assertTrue(secondPage.list().get(0).pageUrl().endsWith("/archive"));

        PageWriteProfilePageView filtered = queryService.pageByProject(projectId, 1, 50, "draft");
        assertEquals(1, filtered.total());
        PageWriteProfileView row = filtered.list().get(0);
        assertTrue(row.pageUrl().endsWith("/drafts"));
        assertFalse(row.snapshotOnly());
        assertEquals(500, row.netFailures().get(0).get("status").asInt());

        PageWriteProfileView riskRow = queryService.pageByProject(projectId, 1, 50, "/notes")
                .list().get(0);
        assertTrue(riskRow.writesOnInput());
        assertEquals("POST", riskRow.writeSurface().get(0));
        assertEquals(3, riskRow.writeEvents().get(0).get("stepNo").asInt());
    }

    @Test
    void riskDoorListsOnlyWritePagesInsideTheSlice() {
        long mine = riskProject();
        long theirs = riskProject();
        book(mine, "/checkout", "[{\"method\":\"POST\"}]", "[]", true, 1);
        book(mine, "/archive", "[]", "[]", false, 2);
        jdbcTemplate.update("""
                INSERT INTO tp_page_write_profile
                    (project_id, page_key, page_url, write_surface, write_events,
                     net_failures, snapshot_only, last_seen_at)
                VALUES (?, ?, ?, ?::jsonb, '[]'::jsonb, '[]'::jsonb, false, now())
                """,
                mine, "http://risk-ledger.test/profile", "http://risk-ledger.test/profile",
                "[\"PUT\"]");
        book(theirs, "/foreign", "[]", "[]", true, 1);

        List<PageWriteRiskView> risk = queryService.riskPagesForProject(mine);
        assertEquals(2, risk.size(), "sticky downgrade plus booked write surface qualify; "
                + "the clean row and the foreign slice never appear");
        assertTrue(risk.get(0).pageUrl().endsWith("/profile"), "newest activity first");
        assertTrue(risk.get(1).pageUrl().endsWith("/checkout"));
        assertEquals("http://risk-ledger.test/profile", risk.get(0).pageKey());

        List<PageWriteRiskView> foreignRisk = queryService.riskPagesForProject(theirs);
        assertEquals(1, foreignRisk.size());
        assertTrue(foreignRisk.get(0).pageUrl().endsWith("/foreign"));
    }

    @Test
    void foreignProjectRowsNeverLeakIntoTheSlice() {
        long mine = riskProject();
        long theirs = riskProject();
        book(theirs, "/foreign-write", "[{\"method\":\"PUT\"}]", "[]", true, 1);
        book(mine, "/mine", "[]", "[]", false, 2);

        PageWriteProfilePageView view = queryService.pageByProject(mine, 1, 50, null);
        assertEquals(1, view.total());
        assertTrue(view.list().get(0).pageUrl().endsWith("/mine"));
    }

    @Test
    void emptyProjectSlicesHonestly() {
        long projectId = riskProject();
        PageWriteProfilePageView view = queryService.pageByProject(projectId, 1, 20, null);
        assertEquals(0, view.total());
        assertTrue(view.list().isEmpty());
    }
}
