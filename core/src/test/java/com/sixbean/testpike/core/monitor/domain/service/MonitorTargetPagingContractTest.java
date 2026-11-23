package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInfo;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetPageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paging contract for the monitor target list (S-group worst-scale contract: every
 * list endpoint consumed by a wall page must carry page/pageSize).
 * <p>Verified semantics (server-side sort precedent form):
 * ① zero drift — the legacy whole-list accessor and the paged default window return the
 * identical id sequence;
 * ② windows walk every row exactly once in the requested order, and {@code total}
 * always travels as the whole filtered count (never the current window size);
 * ③ sort columns are whitelist-validated — an injected column name is rejected with a
 * 400 naming the whitelist and the rows stay intact;
 * ④ window parameters are validated/clamped — page/pageSize below 1 are rejected with
 * human-readable messages, pageSize above the hard cap is clamped to the cap.
 * <p>Real dev database via {@code @SpringBootTest} (no mocks); fixture rows are created
 * through the service itself and removed by project id after each test (factory baseline
 * tables are never truncated).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorTargetPagingContractTest {

    private static final String PROJECT_NAME = "pgwall-proj";

    @Autowired
    private MonitorTargetService monitorTargetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedProject() {
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                PROJECT_NAME, "pgwall");
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE name = ?", Long.class, PROJECT_NAME);
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, ?, 1, 'http://127.0.0.1:1/')", projectId, "pgwall-env");
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND name = ?",
                Long.class, projectId, "pgwall-env");
    }

    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long createTarget(String name) {
        return monitorTargetService.create(projectId, new MonitorTargetInput(name, envId, "HTTP",
                Map.of("url", "http://127.0.0.1:1/ping"), 60, true));
    }

    private List<Long> idsOf(MonitorTargetPageView view) {
        return view.list().stream().map(MonitorTargetInfo::id).toList();
    }

    @Test
    void defaultWindowEqualsLegacyWholeList() {
        for (int i = 0; i < 7; i++) {
            createTarget("pgwall-" + (char) ('a' + i));
        }
        List<Long> legacy = monitorTargetService.list(projectId).stream()
                .map(MonitorTargetInfo::id).toList();
        MonitorTargetPageView wide =
                monitorTargetService.page(projectId, 1, MonitorTargetService.MAX_PAGE_SIZE, null, null);
        assertEquals(7, legacy.size(), "fixture must land 7 rows");
        assertEquals(legacy, idsOf(wide), "legacy list and paged default window must agree exactly");
        assertEquals(7L, wide.total());
    }

    @Test
    void pagesWalkAllRowsOnceWithWholeTotal() {
        for (int i = 0; i < 5; i++) {
            createTarget("pgwall-" + (char) ('a' + i));
        }
        List<Long> walked = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            MonitorTargetPageView view = monitorTargetService.page(projectId, page, 2, "name", null);
            assertEquals(5L, view.total(), "total is the whole filtered count on every window");
            view.list().forEach(row -> walked.add(row.id()));
        }
        assertEquals(5, walked.size(), "three windows of 2+2+1 must cover the fixture once");
        assertEquals(walked.stream().distinct().toList(), walked, "no row may repeat across windows");
    }

    @Test
    void descendingSortReversesTheWindow() {
        for (int i = 0; i < 4; i++) {
            createTarget("pgwall-" + (char) ('a' + i));
        }
        MonitorTargetPageView asc = monitorTargetService.page(projectId, 1, 4, "name", "asc");
        MonitorTargetPageView desc = monitorTargetService.page(projectId, 1, 4, "name", "desc");
        assertEquals("pgwall-a", asc.list().get(0).name());
        assertEquals("pgwall-d", desc.list().get(0).name());
    }

    @Test
    void unknownSortColumnRejectedAndRowsIntact() {
        for (int i = 0; i < 3; i++) {
            createTarget("pgwall-" + (char) ('a' + i));
        }
        ServiceException rejected = assertThrows(ServiceException.class, () ->
                monitorTargetService.page(projectId, 1, 10, "name; DROP TABLE tp_monitor_target", null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("name"),
                "rejection must name the legal whitelist, was: " + rejected.getMessage());
        assertEquals(3, monitorTargetService.list(projectId).size(), "rejection must not touch rows");
    }

    @Test
    void invalidWindowRejectedAndOversizedPageSizeClamped() {
        assertThrows(ServiceException.class,
                () -> monitorTargetService.page(projectId, 0, 10, null, null));
        assertThrows(ServiceException.class,
                () -> monitorTargetService.page(projectId, 1, 0, null, null));
        MonitorTargetPageView clamped = monitorTargetService.page(projectId, 1, 5000, null, null);
        assertEquals(MonitorTargetService.MAX_PAGE_SIZE, clamped.pageSize(),
                "pageSize above the hard cap must echo the cap");
    }

    // ---- server-side filter contract (wall-page channel: status/type/keyword) ----

    private long createWithType(String name, String type) {
        return monitorTargetService.create(projectId, new MonitorTargetInput(name, envId, type,
                Map.of("url", "http://127.0.0.1:1/ping"), 60, true));
    }

    /** Fixture-only state seeding: probe results are produced by the scheduler in
     * production; here the state column is set directly to shape the wall tiles. */
    private void forceState(long id, String state) {
        jdbcTemplate.update("UPDATE tp_monitor_target SET last_state = ? WHERE id = ?", state, id);
    }

    private MonitorTargetPageView filtered(String status, String targetType, String keyword) {
        return monitorTargetService.page(projectId, 1, 50, null, null, status, targetType, keyword);
    }

    @Test
    void statusFilterMirrorsWallTilePriority() {
        long disabled = createTarget("st-disabled");
        monitorTargetService.updateEnabled(disabled, false);
        long silenced = createTarget("st-silenced");
        monitorTargetService.silence(silenced, 60, "release window");
        long down = createTarget("st-down");
        forceState(down, "DOWN");
        long up = createTarget("st-up");
        forceState(up, "UP");

        MonitorTargetPageView downOnly = filtered("DOWN", null, null);
        assertEquals(List.of(down), idsOf(downOnly));
        assertEquals(1L, downOnly.total(), "total counts the filtered set, never the raw fixture");
        assertEquals(List.of(silenced), idsOf(filtered("SILENCED", null, null)));
        assertEquals(List.of(disabled), idsOf(filtered("DISABLED", null, null)));
        assertEquals(List.of(up), idsOf(filtered("UP", null, null)));
        // unknown = enabled, never probed yet, not silenced; disabled/silenced rows are
        // excluded by the same precedence the wall tiles render with.
        assertEquals(List.of(), idsOf(filtered("UNKNOWN", null, null)));
        // filter values are case-insensitive on the api surface
        assertEquals(List.of(down), idsOf(filtered("down", null, null)));
    }

    @Test
    void targetTypeAndKeywordFiltersNarrowTheWindow() {
        long http = createTarget("tf-http-main");
        long online = createWithType("tf-online-main", "ONLINE");
        assertEquals(List.of(online), idsOf(filtered(null, "ONLINE", null)));
        MonitorTargetPageView byKeyword = filtered(null, null, "main");
        assertEquals(List.of(http, online), idsOf(byKeyword));
        assertEquals(2L, byKeyword.total());
        assertEquals(List.of(), idsOf(filtered(null, null, "no-such-name")));
    }

    @Test
    void keywordWildcardsStayLiteral() {
        long weird = createTarget("lit-%_x");
        createTarget("lit-axb");
        // unescaped % or _ would act as ILIKE wildcards and wrongly match the plain row
        assertEquals(List.of(weird), idsOf(filtered(null, null, "%")));
        assertEquals(List.of(weird), idsOf(filtered(null, null, "_")));
    }

    @Test
    void unknownFilterValuesRejectedAndRowsIntact() {
        createTarget("rg-1");
        ServiceException badStatus = assertThrows(ServiceException.class,
                () -> filtered("BOGUS", null, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badStatus.getCode());
        assertTrue(badStatus.getMessage().contains("DOWN"),
                "status rejection must name the legal choices, was: " + badStatus.getMessage());
        ServiceException badType = assertThrows(ServiceException.class,
                () -> filtered(null, "BOGUS", null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badType.getCode());
        assertTrue(badType.getMessage().contains("HTTP"),
                "type rejection must reuse the save-side whitelist message, was: " + badType.getMessage());
        assertEquals(1, monitorTargetService.list(projectId).size(), "rejection must not touch rows");
    }

    @Test
    void combinedFiltersWalkPagesWithFilteredTotal() {
        List<Long> downs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long id = createTarget("cw-down-" + i);
            forceState(id, "DOWN");
            downs.add(id);
        }
        for (int i = 0; i < 2; i++) {
            long id = createTarget("cw-up-" + i);
            forceState(id, "UP");
        }
        List<Long> walked = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            MonitorTargetPageView view =
                    monitorTargetService.page(projectId, page, 1, "name", "asc", "DOWN", null, null);
            assertEquals(4L, view.total(), "every window must echo the whole filtered total");
            view.list().forEach(row -> walked.add(row.id()));
        }
        assertEquals(downs, walked, "one-row windows must walk the filtered set in order, once");
    }
}
