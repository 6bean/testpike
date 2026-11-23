package com.sixbean.testpike.core.webelement.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementHealthView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.domain.component.WebElementAnchorStatWriteback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step-health account contracts (WE S7 8.3, package 1). The daily rollup
 * table tp_web_element_health_daily (V609) is the only time-series source;
 * every contract below is a pure SQL window read — zero model calls.
 * <p>Contracts nailed here:
 * ① badge tiers green/yellow/red follow the hot keys
 * element.health.miss-warn / miss-bad (percent ints, live read);
 * ② an element with no outcome in the window reads as unreached and the
 * drift alert files nothing at all — silent pages must never alarm
 * ③ the drift ladder (two consecutive window climbs, current window not a
 * total failure) flips aboutToBreak, appends one element-ledger audit
 * event and stations one notice per owner per day — a same-day re-trigger
 * stays silent (dedup cooldown);
 * ④ a 100% miss window is already broken, not "about to break" (badge red,
 * no drift event); a falling rate never alerts;
 * ⑤ the result relay rolls today's outcome into the daily bucket and prunes
 * rows past horizon = window-days * 4 in the same hook (R57 bounded
 * lifetime at the creation point).
 * <p>Real chain against the chain-slot DB: project and ledger rows through
 * live services; daily fixtures inserted by jdbc (time travel has no API
 * door on purpose). Fixtures purged by project scope — daily and stat rows
 * follow through the FK cascade, notification/audit rows by explicit anchor.
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementHealthServiceTest {

    private static final AtomicLong SEQ = new AtomicLong();
    private static final String DRIFT_EVENT_TYPE = "WEB_ELEMENT_DRIFT";

    @Autowired
    private WebElementHealthService healthService;
    @Autowired
    private WebElementAnchorStatWriteback writeback;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigCacheHolder configCache;
    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void seedProject() {
        long tag = SEQ.incrementAndGet();
        String code = "WH" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "健康度项目" + code, code, "zh-CN", "P1", null));
    }

    @AfterEach
    void removeFixtures() {
        List<String> refs = jdbcTemplate.queryForList(
                "SELECT CAST(id AS VARCHAR) FROM tp_web_element WHERE project_id = ?",
                String.class, projectId);
        if (!refs.isEmpty()) {
            String csv = refs.stream()
                    .map(r -> "'" + r.replace("'", "''") + "'")
                    .collect(java.util.stream.Collectors.joining(","));
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                    + " AND job_ref IN (" + csv + ")");
        }
        jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long element(String name) {
        return webElementService.create(new WebElementInput(projectId, name,
                "https://shop.example.com/orders",
                List.of(new WebElementAnchorInput("css", ".ord", 0.9, null)))).id();
    }

    private void seedDaily(long elementId, int daysAgo, long hits, long misses) {
        jdbcTemplate.update("INSERT INTO tp_web_element_health_daily"
                        + " (bucket_date, element_id, viewport, hits, misses)"
                        + " VALUES (CURRENT_DATE - ?, ?, '1440x900', ?, ?)",
                daysAgo, elementId, hits, misses);
    }

    private WebElementHealthView health(long elementId) {
        return healthService.healthFor(List.of(elementId)).get(elementId);
    }

    @Test
    void badgeTiersFollowTheHotThresholdKeys() {
        long green = element("绿色档");
        long yellow = element("黄色档");
        long red = element("红色档");
        seedDaily(green, 1, 95, 5);
        seedDaily(yellow, 1, 70, 30);
        seedDaily(red, 1, 50, 50);
        assertEquals(WebElementHealthView.BADGE_GREEN, health(green).badge());
        assertEquals(WebElementHealthView.BADGE_YELLOW, health(yellow).badge());
        assertEquals(WebElementHealthView.BADGE_RED, health(red).badge());
        assertEquals(30, health(yellow).missRatePercent());
    }

    @Test
    void unreachedElementReadsNeutralAndAlertsNothing() {
        long silent = element("沉寂页元素");
        WebElementHealthView view = health(silent);
        assertFalse(view.reached());
        assertEquals(WebElementHealthView.BADGE_NONE, view.badge());
        assertFalse(view.aboutToBreak());
        assertEquals(0, healthService.evaluateDrift(silent));
        Integer notices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE project_id = ?",
                Integer.class, projectId);
        assertEquals(0, notices);
    }

    @Test
    void driftLadderFlipsBadgeAndAlertsOncePerDay() {
        long elementId = element("将坏元素");
        seedDaily(elementId, 15, 95, 5);
        seedDaily(elementId, 8, 85, 15);
        seedDaily(elementId, 1, 55, 45);
        WebElementHealthView view = health(elementId);
        assertTrue(view.aboutToBreak());
        assertEquals(WebElementHealthView.BADGE_YELLOW, view.badge());
        assertTrue(healthService.evaluateDrift(elementId) > 0);
        Integer notices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE project_id = ?"
                        + " AND event_type = ?", Integer.class, projectId, DRIFT_EVENT_TYPE);
        assertEquals(1, notices);
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                        + " AND job_ref = ? AND summary LIKE '%将坏%'",
                Integer.class, String.valueOf(elementId));
        assertEquals(1, auditRows);
        // Same-day re-trigger stays silent (cooldown, not a second bell ring).
        assertEquals(0, healthService.evaluateDrift(elementId));
    }

    @Test
    void totalFailureIsBrokenNotAboutToBreak() {
        long elementId = element("全灭元素");
        seedDaily(elementId, 15, 95, 5);
        seedDaily(elementId, 8, 85, 15);
        seedDaily(elementId, 1, 0, 60);
        WebElementHealthView view = health(elementId);
        assertEquals(WebElementHealthView.BADGE_RED, view.badge());
        assertFalse(view.aboutToBreak());
        assertEquals(0, healthService.evaluateDrift(elementId));
    }

    @Test
    void fallingRateNeverAlerts() {
        long elementId = element("回稳元素");
        seedDaily(elementId, 15, 55, 45);
        seedDaily(elementId, 8, 70, 30);
        seedDaily(elementId, 1, 95, 5);
        assertFalse(health(elementId).aboutToBreak());
        assertEquals(0, healthService.evaluateDrift(elementId));
    }

    @Test
    void thresholdChangeIsLiveWithoutRefeedingData() {
        long elementId = element("热更档位");
        seedDaily(elementId, 1, 70, 30);
        assertEquals(WebElementHealthView.BADGE_YELLOW, health(elementId).badge());
        try {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = '40'"
                    + " WHERE config_key = 'element.health.miss-warn'");
            // The settings door refreshes the config cache on save; the
            // fixture writes the column directly, so it refreshes here.
            configCache.loadAll(jdbcTemplate);
            assertEquals(WebElementHealthView.BADGE_GREEN, health(elementId).badge());
        } finally {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = '20'"
                    + " WHERE config_key = 'element.health.miss-warn'");
            configCache.loadAll(jdbcTemplate);
        }
    }

    @Test
    void relayRollsTodayBucketAndPrunesPastHorizon() {
        long elementId = element("中继滚动");
        seedDaily(elementId, 40, 10, 1);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("elementId", elementId);
        row.put("name", "中继滚动");
        row.put("viewport", "390x844");
        row.put("anchorType", "css");
        row.put("hit", true);
        row.put("tried", true);
        Map<String, Object> missRow = new LinkedHashMap<>(row);
        missRow.put("hit", false);
        ExecTaskInfo task = objectMapper.convertValue(Map.of(
                "id", 900001L, "projectId", projectId, "taskType", 1), ExecTaskInfo.class);
        Map<String, Object> relayShape = new LinkedHashMap<>();
        relayShape.put("taskId", 900001L);
        relayShape.put("result", "pass");
        relayShape.put("elementAnchorHits", List.of(row, missRow, missRow));
        writeback.onResultSubmitted(task, objectMapper.convertValue(relayShape, ExecResultInput.class));
        Integer today = jdbcTemplate.queryForObject(
                "SELECT hits + misses FROM tp_web_element_health_daily"
                        + " WHERE element_id = ? AND viewport = '390x844'"
                        + " AND bucket_date = CURRENT_DATE", Integer.class, elementId);
        assertEquals(3, today);
        Integer stale = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element_health_daily"
                        + " WHERE element_id = ? AND bucket_date < CURRENT_DATE - 28",
                Integer.class, elementId);
        assertEquals(0, stale, "rows past horizon must not outlive the relay that wrote them");
    }
}
