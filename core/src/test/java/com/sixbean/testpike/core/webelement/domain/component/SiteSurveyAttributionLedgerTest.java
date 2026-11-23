package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.perf.infrastructure.remote.client.AiServicePerfInsightClient;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.sixbean.testpike.core.perf.api.dto.PerfRunInsightRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch-close attribution read-out leg for whole-site survey runs: the sweep
 * selects terminal runs that carry risk facts (observed out-of-baseline write
 * requests or network-level failures) but no summary yet, assembles the
 * numbers core-side from the run counters and the page write-risk ledger,
 * hands them to the existing insight door without a run anchor, and stores
 * the returned paragraph on the run row.
 * <p>Pinned contracts: one door hop per run and never a second one after the
 * summary is stored (idempotent replay); facts block numbers come from the
 * ledger (the model never re-derives them); a closed switch costs zero hops;
 * a refusing door leaves the row untouched for the next sweep instead of
 * failing the sweep. Real dev database; the ai-service hop is isolated with
 * a stub client (infrastructure seam only, L3 covers the live door).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class SiteSurveyAttributionLedgerTest {

    @Autowired
    private SiteSurveyAttributionLedger ledger;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;
    @MockBean
    private AiServicePerfInsightClient insightClient;
    @Autowired
    private ConfigCacheHolder configCache;

    private final List<Long> projects = new ArrayList<>();
    private final List<String> configRestore = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String restore : configRestore) {
            jdbcTemplate.update(restore);
        }
        if (!configRestore.isEmpty()) {
            configCache.remove("ai.perf.insight");
        }
        configRestore.clear();
        for (Long projectId : projects) {
            jdbcTemplate.update("DELETE FROM tp_page_write_profile WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM tp_site_survey_run WHERE project_id = ?", projectId);
        }
        projects.clear();
    }

    private long newProject() {
        return ProjectFixtureRig.createTracked(jdbcTemplate, projectService, projects, "探查归因摘要");
    }

    private long insertTerminalRun(long projectId) {
        jdbcTemplate.update("""
                INSERT INTO tp_site_survey_run (profile_id, project_id, status,
                    pages_total, pages_ok, pages_failed, started_at, finished_at)
                VALUES (-1, ?, 'partial', 20, 17, 3, now() - interval '2 hours', now() - interval '1 hour')
                """, projectId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_site_survey_run WHERE project_id = ?", Long.class, projectId);
    }

    private void insertRiskPage(long projectId, String pageUrl, String writeEvents, String netFailures,
                                boolean snapshotOnly) {
        jdbcTemplate.update("""
                INSERT INTO tp_page_write_profile (project_id, page_key, page_url, write_surface,
                    write_events, net_failures, snapshot_only, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, '[]'::jsonb, ?::jsonb, ?::jsonb, ?,
                        now() - interval '2 hours', now() - interval '1 hour')
                """, projectId, pageUrl, pageUrl, writeEvents, netFailures, snapshotOnly);
    }

    @Test
    void sweepWritesFactsBlockAndStoresSummaryOncePerRun() {
        reset(insightClient);
        long projectId = newProject();
        long runId = insertTerminalRun(projectId);
        insertRiskPage(projectId, "http://fixture.local/form",
                "[{\"url\":\"http://fixture.local/form/save\",\"method\":\"POST\",\"status\":200,\"stepNo\":3}]",
                "[{\"url\":\"http://fixture.local/api/list\",\"method\":\"GET\",\"status\":500,\"errorType\":1}]",
                true);
        when(insightClient.explain(any())).thenReturn(Map.of("insight", "表单页输入会直接保存，列表接口有 500。"));

        int written = ledger.sweepOnce();

        assertEquals(1, written);
        ArgumentCaptor<PerfRunInsightRequest> captor =
                ArgumentCaptor.forClass(PerfRunInsightRequest.class);
        verify(insightClient, times(1)).explain(captor.capture());
        PerfRunInsightRequest sent = captor.getValue();
        assertEquals(projectId, sent.projectId());
        assertNull(sent.runId(), "survey read-out must not anchor into the perfinsight event ledger");
        String facts = sent.factsBlock();
        assertTrue(facts.contains("运行 #" + runId), facts);
        assertTrue(facts.contains("POST"), facts);
        assertTrue(facts.contains("500"), facts);
        assertTrue(facts.contains("20"), facts);
        String stored = jdbcTemplate.queryForObject(
                "SELECT attribution_summary FROM tp_site_survey_run WHERE id = ?", String.class, runId);
        assertEquals("表单页输入会直接保存，列表接口有 500。", stored);

        // Idempotent replay: a second sweep never re-hops the stored run.
        reset(insightClient);
        assertEquals(0, ledger.sweepOnce());
        verify(insightClient, times(0)).explain(any());
    }

    @Test
    void cleanRunWithoutRiskFactsIsNeverHopped() {
        reset(insightClient);
        long projectId = newProject();
        insertTerminalRun(projectId);
        assertEquals(0, ledger.sweepOnce());
        verify(insightClient, times(0)).explain(any());
    }

    @Test
    void refusedDoorLeavesTheRowUntouchedForTheNextSweep() {
        reset(insightClient);
        long projectId = newProject();
        long runId = insertTerminalRun(projectId);
        insertRiskPage(projectId, "http://fixture.local/save-on-input",
                "[{\"url\":\"http://fixture.local/save-on-input\",\"method\":\"PUT\",\"status\":204,\"stepNo\":2}]",
                "[]", false);
        when(insightClient.explain(any()))
                .thenThrow(new IllegalStateException("insight door unreachable in isolation"));

        assertEquals(0, ledger.sweepOnce());
        String stored = jdbcTemplate.queryForObject(
                "SELECT attribution_summary FROM tp_site_survey_run WHERE id = ?", String.class, runId);
        assertNull(stored);
    }

    @Test
    void closedSwitchCostsZeroHops() {
        reset(insightClient);
        long projectId = newProject();
        insertTerminalRun(projectId);
        insertRiskPage(projectId, "http://fixture.local/form",
                "[{\"url\":\"http://fixture.local/form/save\",\"method\":\"POST\",\"status\":200,\"stepNo\":3}]",
                "[]", false);
        String previous = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'ai.perf.insight'", String.class);
        configRestore.add("UPDATE tp_sys_config SET config_value = '" + previous + "' WHERE config_key = 'ai.perf.insight'");
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'false' WHERE config_key = 'ai.perf.insight'");
        // The gate reads the in-memory config holder; a raw SQL flip only
        // lands once the stale entry is dropped (the holder re-reads on miss).
        configCache.remove("ai.perf.insight");

        assertEquals(0, ledger.sweepOnce());
        verify(insightClient, times(0)).explain(any());
    }
}
