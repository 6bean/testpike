package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.RevisionStormView;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Revision-storm contracts (WE S7 A7, package 2). The stale-event ledger
 * (V610) is written only by the state-change choke point, and a page joins
 * the storm board when either signal reaches the configured entry count
 * (element.storm.entry-min, seed 3; window element.storm.window-hours,
 * seed 24) — the two signals never pool. The about-to-break read comes
 * from the step-health account, no second drift ladder anywhere (R40).
 * <p>Contracts:
 * ① three same-page stale transitions inside the window form one revision
 *   event carrying exactly those elements; a lone stale element on another
 *   page never storms;
 * ② three about-to-break elements on a page storm as the early warning
 *   with an empty stale side;
 * ③ 2 stale + 1 about-to-break on one page stays below threshold (no
 *   pooling across signals);
 * ④ stale events older than the window never storm;
 * ⑤ the insert prunes this project's rows past the account horizon
 *   (window-hours * 4) in the same call — bounded lifetime at the
 *   creation point (R57).
 * Real chain on the slot DB; daily fixtures via jdbc (time travel has no
 * API door on purpose); fixtures purged by project scope.
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementStormServiceTest {

    private static final String PAGE_A = "https://shop.example.com/orders";
    private static final String PAGE_B = "https://shop.example.com/cart";
    private static final String PAGE_C = "https://shop.example.com/checkout";
    private static final String PAGE_D = "https://shop.example.com/account";
    private static final String PAGE_E = "https://shop.example.com/reports";

    @Autowired
    private WebElementStormService stormService;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void seedProject() {
        String code = "WS" + System.currentTimeMillis() % 100000000L
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
        projectId = projectService.create(1L, new ProjectCreateInput(
                "风暴项目" + code, code, "zh-CN", "P1", null));
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
        jdbcTemplate.update("DELETE FROM tp_web_element_stale_event WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_notification WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private WebElementView element(String pageUrl, String name) {
        return webElementService.create(new WebElementInput(projectId, name, pageUrl,
                List.of(new WebElementAnchorInput("css", ".x" + name, 0.9, null))));
    }

    /** candidate → confirmed → stale through the real state door (hook path). */
    private long staleViaDoor(String pageUrl, String name) {
        WebElementView created = element(pageUrl, name);
        WebElementView confirmed = webElementService.changeState(
                created.id(), 1, created.revision());
        webElementService.changeState(confirmed.id(), 2, confirmed.revision());
        return confirmed.id();
    }

    /** confirmed element whose recent daily windows climb 0% → 20% → 40%. */
    private long aboutToBreakElement(String pageUrl, String name) {
        WebElementView created = element(pageUrl, name);
        webElementService.changeState(created.id(), 1, created.revision());
        seedDaily(created.id(), 1, 6, 4);
        seedDaily(created.id(), 8, 8, 2);
        seedDaily(created.id(), 15, 10, 0);
        return created.id();
    }

    private void seedDaily(long elementId, int daysAgo, long hits, long misses) {
        jdbcTemplate.update("INSERT INTO tp_web_element_health_daily"
                        + " (bucket_date, element_id, viewport, hits, misses)"
                        + " VALUES (CURRENT_DATE - ?, ?, '1440x900', ?, ?)",
                daysAgo, elementId, hits, misses);
    }

    private void seedStaleEvent(long elementId, String pageUrl, int hoursAgo) {
        String pageKey = webElementService.findById(elementId).pageKey();
        jdbcTemplate.update("INSERT INTO tp_web_element_stale_event"
                        + " (element_id, project_id, page_key, occurred_at)"
                        + " VALUES (?, ?, ?, CURRENT_TIMESTAMP - (? || ' hours')::interval)",
                elementId, projectId, pageKey, hoursAgo);
    }

    private Map<String, RevisionStormView> storms() {
        return stormService.activeStorms(projectId).stream()
                .collect(Collectors.toMap(RevisionStormView::pageKey, Function.identity()));
    }

    @Test
    void threeStaleTransitionsOnOnePageFormOneRevisionEvent() {
        long a1 = staleViaDoor(PAGE_A, "改1");
        long a2 = staleViaDoor(PAGE_A, "改2");
        long a3 = staleViaDoor(PAGE_A, "改3");
        long b1 = staleViaDoor(PAGE_B, "孤1");
        Map<String, RevisionStormView> views = storms();
        String pageA = webElementService.findById(a1).pageKey();
        String pageB = webElementService.findById(b1).pageKey();
        assertTrue(views.containsKey(pageA), "storm page missing");
        assertEquals(List.of(a1, a2, a3).stream().sorted().collect(Collectors.toList()),
                views.get(pageA).staleElementIds().stream().sorted()
                        .collect(Collectors.toList()));
        assertTrue(views.get(pageA).aboutToBreakElementIds().isEmpty());
        assertFalse(views.containsKey(pageB), "a lone stale element must not storm");
    }

    @Test
    void aboutToBreakPagesStormAsEarlyWarning() {
        long c1 = aboutToBreakElement(PAGE_C, "将1");
        long c2 = aboutToBreakElement(PAGE_C, "将2");
        long c3 = aboutToBreakElement(PAGE_C, "将3");
        Map<String, RevisionStormView> views = storms();
        String pageC = webElementService.findById(c1).pageKey();
        assertTrue(views.containsKey(pageC), "early-warning page missing");
        RevisionStormView view = views.get(pageC);
        assertEquals(3, view.aboutToBreakElementIds().size());
        assertTrue(view.staleElementIds().isEmpty());
    }

    @Test
    void theTwoSignalsNeverPoolIntoOneStorm() {
        long d1 = staleViaDoor(PAGE_D, "混1");
        long d2 = staleViaDoor(PAGE_D, "混2");
        long d3 = aboutToBreakElement(PAGE_D, "混3");
        String pageD = webElementService.findById(d1).pageKey();
        assertFalse(storms().containsKey(pageD),
                "2 stale + 1 about-to-break must stay below the entry count");
    }

    @Test
    void staleEventsOutsideTheWindowDoNotStorm() {
        long e1 = element(PAGE_E, "窗1").id();
        long e2 = element(PAGE_E, "窗2").id();
        long e3 = element(PAGE_E, "窗3").id();
        seedStaleEvent(e1, PAGE_E, 30);
        seedStaleEvent(e2, PAGE_E, 31);
        seedStaleEvent(e3, PAGE_E, 32);
        String pageE = webElementService.findById(e1).pageKey();
        assertFalse(storms().containsKey(pageE), "30h-old events exceed the 24h window");
        seedStaleEvent(e1, PAGE_E, 0);
        seedStaleEvent(e2, PAGE_E, 0);
        assertFalse(storms().containsKey(pageE),
                "two in-window rows stay below the entry count of three");
        seedStaleEvent(e3, PAGE_E, 0);
        assertTrue(storms().containsKey(pageE),
                "a third fresh in-window row reaches the entry count");
    }

    @Test
    void theWritePointPrunesRowsPastTheAccountHorizon() {
        long target = element(PAGE_E, "剪1").id();
        seedStaleEvent(target, PAGE_E, 100);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element_stale_event"
                        + " WHERE element_id = ?", Integer.class, target));
        stormService.recordStaleTransition(projectId,
                webElementService.findById(target).pageKey(), target);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element_stale_event"
                        + " WHERE element_id = ?", Integer.class, target),
                "the 100h-old row (horizon = 24*4 = 96h) must be gone; only the new row remains");
    }
}
