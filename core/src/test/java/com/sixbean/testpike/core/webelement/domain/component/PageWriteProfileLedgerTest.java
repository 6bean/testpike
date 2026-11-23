package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Page write-risk ledger (site-survey write guard, server half): a page frame
 * whose browser guard observed baseline-outside write requests must keep one
 * durable risk row per (project, folded page key) so the snapshot-only
 * downgrade outlives the run. The ledger books only risk-bearing frames (a
 * clean page creates no census row), keeps the sticky flag ratcheted (a later
 * clean window never unlocks deep probing), replaces the per-column window
 * with the latest frame (bounded row size instead of unbounded history) and
 * never fails the step it observes (malformed payloads degrade to a WARN).
 * Real dev database, no mocks. Cleanup deletes only rows created here.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PageWriteProfileLedgerTest {

    @Autowired
    private PageWriteProfileLedger ledger;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;

    private final List<Long> projects = new ArrayList<>();

    private long project() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L, projects,
                "页面写型风险账" + tag, tag);
    }

    @AfterEach
    void drainFixtures() {
        ProjectFixtureRig.drainLedgerTables(jdbcTemplate, projects, "tp_page_write_profile");
    }

    private static String frame(String argumentsJson) {
        return "{\"name\":\"site-survey-page\",\"arguments\":" + argumentsJson + "}";
    }

    @Test
    void writeObservedFrameCreatesStickySnapshotOnlyRow() {
        long projectId = project();
        ledger.notePageFrame(projectId, frame(
                "{\"url\":\"http://write-profile.test/notes/new\","
                        + "\"status\":\"ok\","
                        + "\"writeEvents\":[{\"stepNo\":3,\"method\":\"POST\","
                        + "\"url\":\"http://write-profile.test/api/notes/draft\"}],"
                        + "\"writeSurface\":[\"POST\"],"
                        + "\"snapshotOnly\":true}"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_page_write_profile WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(1, rows);
        Boolean sticky = jdbcTemplate.queryForObject(
                "SELECT snapshot_only FROM tp_page_write_profile WHERE project_id = ?",
                Boolean.class, projectId);
        assertTrue(sticky);
        String surface = jdbcTemplate.queryForObject(
                "SELECT write_surface::text FROM tp_page_write_profile WHERE project_id = ?",
                String.class, projectId);
        assertTrue(surface.contains("POST"));
        String events = jdbcTemplate.queryForObject(
                "SELECT write_events::text FROM tp_page_write_profile WHERE project_id = ?",
                String.class, projectId);
        assertTrue(events.contains("draft"));
    }

    @Test
    void laterFailureWindowReplacesColumnsButStickyFlagNeverUnlocks() {
        long projectId = project();
        String page = "{\"url\":\"http://write-profile.test/notes/edit\",\"status\":\"ok\","
                + "\"writeEvents\":[{\"stepNo\":1,\"method\":\"PUT\","
                + "\"url\":\"http://write-profile.test/api/notes/1\"}],"
                + "\"writeSurface\":[\"PUT\"],\"snapshotOnly\":true}";
        ledger.notePageFrame(projectId, frame(page));
        // A later frame books only a failing endpoint window: the risk row is
        // ONE row (folded key identical), its columns ride the latest window,
        // and the deep-probe ban survives the cleaner frame.
        String followUp = "{\"url\":\"http://write-profile.test/notes/edit\","
                + "\"status\":\"ok\","
                + "\"netFailures\":[{\"url\":\"http://write-profile.test/api/search\","
                + "\"method\":\"POST\",\"status\":500}]}";
        ledger.notePageFrame(projectId, frame(followUp));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_page_write_profile WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(1, rows);
        Boolean sticky = jdbcTemplate.queryForObject(
                "SELECT snapshot_only FROM tp_page_write_profile WHERE project_id = ?",
                Boolean.class, projectId);
        assertTrue(sticky);
        String failures = jdbcTemplate.queryForObject(
                "SELECT net_failures::text FROM tp_page_write_profile WHERE project_id = ?",
                String.class, projectId);
        assertTrue(failures.contains("500"));
    }

    @Test
    void riskFreeFrameAndMalformedPayloadsStaySilent() {
        long projectId = project();
        ledger.notePageFrame(projectId, frame(
                "{\"url\":\"http://write-profile.test/catalog\",\"status\":\"ok\","
                        + "\"counters\":{\"pages_total\":1}}"));
        assertDoesNotThrow(() -> ledger.notePageFrame(projectId, "{not json"));
        assertDoesNotThrow(() -> ledger.notePageFrame(projectId, null));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_page_write_profile WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(0, rows);
    }
}
