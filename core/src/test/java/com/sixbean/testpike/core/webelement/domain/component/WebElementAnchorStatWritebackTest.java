package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anchor-feedback writeback for the Page Element Library (WE , 03
 * protocol 2.4 trailing key elementAnchorHits). The executor records raw
 * per-attempt outcomes; this component is the only place that turns them into
 * the columnar stat account tp_web_element_anchor_stat (per element, viewport
 * bucket and anchor type — design §8.2: a desktop hit must never inflate a
 * broken mobile anchor) and into the recomputed element confidence
 * (hits + w/2) / (hits + misses + w) with the smoothing prior read from the
 * hot key element.confidence-smooth-prior-weight (seeded 4).
 * <p>Contracts nailed here:
 * ① one relay aggregates per (viewport, anchorType) and recomputes confidence,
 * the element-level hit/miss counters and their last-event timestamps;
 * ② columnar buckets stay apart — desktop and mobile rows never merge;
 * ③ honest filters: tried=false rows, the __truncated__ sentinel, rows without
 * an elementId, blank viewport/type and foreign (retired) element ids all
 * count for nothing and never break the relay chain;
 * ④ absent trailing key and non-browser task types keep the ledger untouched
 * (zero drift for every legacy relay shape);
 * ⑤ repeated relays accumulate on the same buckets (UPSERT, not overwrite);
 * ⑥ the smoothing formula is pure: neutral 0.5 without feedback, w=0 gives
 * the raw rate, the 0/0 corner stays 0.5.
 * <p>Real chain: project and ledger rows are created through live services
 * against the chain-slot DB; fixtures are purged by project scope (stat rows
 * follow through the V550 FK ON DELETE CASCADE).
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementAnchorStatWritebackTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private WebElementAnchorStatWriteback writeback;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private WebElementService webElementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void seedProject() {
        long tag = SEQ.incrementAndGet();
        String code = "WA" + System.currentTimeMillis() % 100000000L + tag;
        projectId = projectService.create(1L, new ProjectCreateInput(
                "锚点回写项目" + code, code, "zh-CN", "P1", null));
    }

    @AfterEach
    void removeFixtures() {
        // Stat rows vanish with their elements (V550 FK ON DELETE CASCADE) —
        // no second cleanup path (R57 terminal-anchor deletion). The project
        // create call also mints a membership row; it must go before the
        // project itself (fixture teardown precedent in the document suite).
        jdbcTemplate.update("DELETE FROM tp_web_element WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long element(String name) {
        return webElementService.create(new WebElementInput(projectId, name,
                "https://shop.example.com/orders",
                List.of(new WebElementAnchorInput("css", ".ord", 0.9, null)))).id();
    }

    private ExecTaskInfo task(long id, int taskType) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("id", id);
        shape.put("projectId", projectId);
        shape.put("taskType", taskType);
        return objectMapper.convertValue(shape, ExecTaskInfo.class);
    }

    private ExecResultInput relay(Object anchorHits) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("taskId", 1L);
        shape.put("result", "pass");
        if (anchorHits != null) {
            shape.put("elementAnchorHits", anchorHits);
        }
        return objectMapper.convertValue(shape, ExecResultInput.class);
    }

    private Map<String, Object> hitRow(long elementId, String viewport, String anchorType,
                                       boolean hit, boolean tried) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("elementId", elementId);
        row.put("name", "下单按钮");
        row.put("viewport", viewport);
        row.put("anchorType", anchorType);
        row.put("hit", hit);
        row.put("tried", tried);
        return row;
    }

    private long[] statRow(long elementId, String viewport, String anchorType) {
        return jdbcTemplate.queryForObject(
                "SELECT hits, misses FROM tp_web_element_anchor_stat "
                        + "WHERE element_id = ? AND viewport = ? AND anchor_type = ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)},
                elementId, viewport, anchorType);
    }

    private Map<String, Object> elementRow(long elementId) {
        return jdbcTemplate.queryForMap(
                "SELECT confidence, hit_count, miss_count, last_hit_at, last_miss_at "
                        + "FROM tp_web_element WHERE id = ?", elementId);
    }

    @Test
    void aggregatesPerBucketAndRecomputesConfidenceAndCounters() {
        long elementId = element("下单按钮A");

        writeback.onResultSubmitted(task(1L, 1), relay(List.of(
                hitRow(elementId, "1440x900", "css", true, true),
                hitRow(elementId, "1440x900", "css", true, true),
                hitRow(elementId, "1440x900", "css", true, true),
                hitRow(elementId, "1440x900", "xpath", false, true))));

        assertArrayEquals2(new long[]{3, 0}, statRow(elementId, "1440x900", "css"));
        assertArrayEquals2(new long[]{0, 1}, statRow(elementId, "1440x900", "xpath"));
        // w=4 prior from the seed key: (3 + 2) / (3 + 1 + 4) = 0.625
        Map<String, Object> row = elementRow(elementId);
        assertEquals(0.625, ((Number) row.get("confidence")).doubleValue(), 1e-9);
        assertEquals(3L, ((Number) row.get("hit_count")).longValue());
        assertEquals(1L, ((Number) row.get("miss_count")).longValue());
        assertTrue(row.get("last_hit_at") != null && row.get("last_miss_at") != null,
                "both last-event stamps refreshed on their outcome kind");
    }

    @Test
    void viewportBucketsStayColumnar() {
        long elementId = element("下单按钮B");

        writeback.onResultSubmitted(task(2L, 1), relay(List.of(
                hitRow(elementId, "1440x900", "css", true, true),
                hitRow(elementId, "390x844", "css", false, true))));

        assertArrayEquals2(new long[]{1, 0}, statRow(elementId, "1440x900", "css"));
        assertArrayEquals2(new long[]{0, 1}, statRow(elementId, "390x844", "css"));
        // Aggregate across buckets stays neutral with one hit and one miss:
        // (1 + 2) / (1 + 1 + 4) = 0.5 — the columnar split never merges rows.
        assertEquals(0.5, ((Number) elementRow(elementId).get("confidence")).doubleValue(), 1e-9);
    }

    @Test
    void filtersUntriedSentinelsBlankAndForeignRowsWithoutBreakingTheChain() {
        long elementId = element("下单按钮C");
        long foreignId = 999_999_999L;
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(hitRow(elementId, "1440x900", "css", true, true));
        rows.add(hitRow(elementId, "1440x900", "rel", false, false));      // untried
        rows.add(hitRow(elementId, "1440x900", "__truncated__", false, true)); // sentinel
        rows.add(hitRow(0L, "1440x900", "css", true, true));               // elementId null below
        rows.get(3).put("elementId", null);
        rows.add(hitRow(elementId, "  ", "css", true, true));              // blank viewport
        rows.add(hitRow(foreignId, "1440x900", "css", true, true));        // retired element

        assertDoesNotThrow(() -> writeback.onResultSubmitted(task(3L, 1), relay(rows)));

        assertArrayEquals2(new long[]{1, 0}, statRow(elementId, "1440x900", "css"));
        Integer strays = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element_anchor_stat WHERE element_id <> ?",
                Integer.class, elementId);
        assertEquals(0, strays, "filtered and foreign rows never reach the stat account");
    }

    @Test
    void absentKeyAndNonBrowserTasksKeepLedgerUntouched() {
        long elementId = element("下单按钮D");

        writeback.onResultSubmitted(task(4L, 1), relay(null));
        writeback.onResultSubmitted(task(5L, 2), relay(List.of(
                hitRow(elementId, "1440x900", "css", true, true))));
        writeback.onResultSubmitted(task(6L, 4), relay(List.of(
                hitRow(elementId, "1440x900", "css", true, true))));

        Integer statRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_web_element_anchor_stat WHERE element_id = ?",
                Integer.class, elementId);
        assertEquals(0, statRows, "zero-drift for legacy relays and non-browser task types");
        Map<String, Object> row = elementRow(elementId);
        assertEquals(0.5, ((Number) row.get("confidence")).doubleValue(), 1e-9);
        assertEquals(0L, ((Number) row.get("hit_count")).longValue());
    }

    @Test
    void repeatedRelaysAccumulateOnTheSameBuckets() {
        long elementId = element("下单按钮E");
        List<Map<String, Object>> payload = List.of(
                hitRow(elementId, "1440x900", "css", true, true),
                hitRow(elementId, "1440x900", "xpath", false, true));

        writeback.onResultSubmitted(task(7L, 1), relay(payload));
        writeback.onResultSubmitted(task(8L, 1), relay(payload));

        assertArrayEquals2(new long[]{2, 0}, statRow(elementId, "1440x900", "css"));
        assertArrayEquals2(new long[]{0, 2}, statRow(elementId, "1440x900", "xpath"));
        // (2 + 2) / (2 + 2 + 4) = 0.5 — accumulation is UPSERT, not overwrite.
        assertEquals(0.5, ((Number) elementRow(elementId).get("confidence")).doubleValue(), 1e-9);
    }

    @Test
    void smoothingFormulaIsPureAndNeutralWithoutFeedback() {
        assertEquals(0.5, WebElementAnchorStatWriteback.smoothedConfidence(0, 0, 4), 1e-9);
        assertEquals(0.5, WebElementAnchorStatWriteback.smoothedConfidence(1, 1, 4), 1e-9);
        assertEquals(12.0 / 14.0, WebElementAnchorStatWriteback.smoothedConfidence(10, 0, 4), 1e-9);
        assertEquals(0.75, WebElementAnchorStatWriteback.smoothedConfidence(3, 1, 0), 1e-9);
        assertEquals(0.5, WebElementAnchorStatWriteback.smoothedConfidence(0, 0, 0), 1e-9,
                "the 0/0 corner must not produce NaN");
    }

    private static void assertArrayEquals2(long[] expected, long[] actual) {
        assertEquals(expected[0], actual[0], "hits");
        assertEquals(expected[1], actual[1], "misses");
    }
}
