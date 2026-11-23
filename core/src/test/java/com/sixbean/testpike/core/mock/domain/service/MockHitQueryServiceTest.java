package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockHitInfo;
import com.sixbean.testpike.core.mock.api.dto.MockHitPageView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request-inspector read side : the inspector page walks
 * tp_mock_hit_log per space, newest first, with an optional hit/miss filter —
 * all against the real dev database, rows seeded through the landing service
 * itself chain), never through hand-written INSERTs.
 * <p>Verified spectrum: (1) time-descending order with id tie-break;
 * (2) matched=true returns hit rows with the winner stub, matched=false
 * returns miss rows (stub_id NULL = the "why didn't my mock fire" evidence),
 * matched=null returns both; (3) the pagination window pages cleanly with a
 * stable total; (4) a space that does not exist is refused in plain words —
 * an empty list for a ghost id would pretend the ledger is healthy;
 * (5) the row keeps the masked snapshot and the verdict trail exactly as the
 * ledger stored them (the read side never re-masks, never rewrites).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockHitQueryServiceTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockHitLedgerService ledgerService;

    @Autowired
    private MockHitQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t358q" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    /** A dedicated space keeps every paging assertion isolated from other rows. */
    private long newSpaceId() {
        long projectId = newProject();
        String key = "iq-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId, new MockSpaceInput("Inspector " + key, key, null,
                null, "prefix", "none", null, null, 100, null));
    }

    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject("SELECT space_key FROM tp_mock_space WHERE id = ?",
                String.class, spaceId);
    }

    private MockHitRowMessage hitRow(String path, long occurredAt) {
        return new MockHitRowMessage("GET", path, Map.of(), Map.of("authorization", "***"),
                null, 201, Map.of("x-order", "o-1"), "{}",
                true, 7L, "order stub",
                "[{\"stub\":\"order stub\",\"step\":\"path\",\"outcome\":\"pass\",\"detail\":\"\"}]",
                5, null, occurredAt);
    }

    private MockHitRowMessage missRow(String path, long occurredAt) {
        return new MockHitRowMessage("GET", path, Map.of(), Map.of(), null,
                null, null, null,
                false, null, null, "[{\"stub\":\"x\",\"step\":\"method\",\"outcome\":\"fail\",\"detail\":\"GET != POST\"}]",
                2, null, occurredAt);
    }

    private void land(long spaceId, MockHitRowMessage... rows) {
        ledgerService.landBatch(new MockHitBatchMessage(spaceKeyOf(spaceId), "v1",
                "batch-" + UUID.randomUUID(), List.of(rows), System.currentTimeMillis()));
    }

    @Test
    void rowsComeBackNewestFirstWithIdTieBreak() {
        long spaceId = newSpaceId();
        long base = 1_700_000_000_000L;
        land(spaceId, hitRow("/old", base), hitRow("/new", base + 60_000), hitRow("/mid", base + 30_000));

        MockHitPageView page = queryService.listBySpace(spaceId, null, 1, 10);
        assertEquals(3, page.total());
        assertEquals(List.of("/new", "/mid", "/old"),
                page.list().stream()
                        .map(h -> (String) h.requestSnapshot().get("path"))
                        .toList(),
                "the inspector timeline is newest-first by serve time");
        page.list().forEach(h -> assertEquals(spaceId, h.spaceId()));
    }

    @Test
    void responseSnapshotReadsBackAsParsedMapWithHonestEmptyDegrade() {
        long spaceId = newSpaceId();
        long base = 1_700_000_400_000L;
        land(spaceId, hitRow("/paid", base), missRow("/ghost", base + 1000));

        MockHitPageView page = queryService.listBySpace(spaceId, null, 1, 10);
        Map<String, Object> served = page.list().stream()
                .filter(h -> Boolean.TRUE.equals(h.matched()))
                .findFirst().orElseThrow().responseSnapshot();
        assertEquals(201, ((Number) served.get("status")).intValue(),
                "the served status arrives parsed — the bridge never re-implements JSON handling");
        assertEquals("o-1", ((Map<?, ?>) served.get("headers")).get("x-order"));
        assertEquals("{}", served.get("body"));

        Map<String, Object> absent = page.list().stream()
                .filter(h -> Boolean.FALSE.equals(h.matched()))
                .findFirst().orElseThrow().responseSnapshot();
        assertTrue(absent.isEmpty(), "a NULL response column honestly degrades to an empty map, row kept");
    }

    @Test
    void matchedFilterSplitsHitsAndMisses() {
        long spaceId = newSpaceId();
        long base = 1_700_000_100_000L;
        land(spaceId, hitRow("/h1", base), missRow("/m1", base + 1000), hitRow("/h2", base + 2000));

        MockHitPageView hits = queryService.listBySpace(spaceId, true, 1, 10);
        assertEquals(2, hits.total(), "matched=true counts only hit rows");
        hits.list().forEach(h -> {
            assertEquals(Boolean.TRUE, h.matched());
            assertEquals(7L, h.stubId());
        });

        MockHitPageView misses = queryService.listBySpace(spaceId, false, 1, 10);
        assertEquals(1, misses.total(), "matched=false isolates the miss evidence");
        assertNull(misses.list().get(0).stubId(), "miss rows stay stub-less by design");
        assertTrue(String.valueOf(misses.list().get(0).matchTrace()).contains("fail"),
                "the verdict trail rides along — that is the whole point of the miss row");

        MockHitPageView all = queryService.listBySpace(spaceId, null, 1, 10);
        assertEquals(3, all.total(), "no filter returns both spectra");
    }

    @Test
    void pagingWindowStaysStableAcrossPages() {
        long spaceId = newSpaceId();
        long base = 1_700_000_200_000L;
        for (int i = 0; i < 5; i++) {
            land(spaceId, hitRow("/p" + i, base + i * 1000));
        }

        MockHitPageView first = queryService.listBySpace(spaceId, null, 1, 2);
        assertEquals(2, first.list().size());
        assertEquals(5, first.total());
        MockHitPageView third = queryService.listBySpace(spaceId, null, 3, 2);
        assertEquals(1, third.list().size(), "the tail page carries the remainder, total unchanged");
        assertEquals(5, third.total());
        assertEquals("/p0", third.list().get(0).requestSnapshot().get("path"),
                "oldest row must sit on the last page");
    }

    @Test
    void unknownSpaceIsRefusedNotAnsweredWithAFakeEmpty() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> queryService.listBySpace(999_999_999L, null, 1, 20));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("空间"), "rejection speaks of the missing space in plain words");
    }

    @Test
    void countRespectsSinceWindowAndMatchedSpectrum() {
        // CI verdict door : "how many hits did my run cause"
        // must count from a since instant and can ask one spectrum only.
        long spaceId = newSpaceId();
        long base = 1_700_000_400_000L;
        land(spaceId, hitRow("/c1", base), hitRow("/c2", base + 60_000), missRow("/c3", base + 30_000));
        java.time.LocalDateTime since = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(base + 15_000), java.time.ZoneId.systemDefault());

        assertEquals(2, queryService.countBySpace(spaceId, since, null),
                "rows older than the window must not leak into the count");
        assertEquals(1, queryService.countBySpace(spaceId, since, Boolean.TRUE),
                "matched=true counts only served hits inside the window");
        assertEquals(1, queryService.countBySpace(spaceId, since, Boolean.FALSE),
                "matched=false counts only misses inside the window");
        assertEquals(3, queryService.countBySpace(spaceId, null, null),
                "no since = the whole ledger of this space");
    }

    @Test
    void countRefusesGhostSpaceInsteadOfAnsweringZero() {
        // A zero for a space that never existed would let a CI verdict pass on
        // a typo'd space id — refuse loudly, same posture as the page query.
        ServiceException ex = assertThrows(ServiceException.class,
                () -> queryService.countBySpace(999_999_998L, null, null));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("空间"));
    }

    /** Full-shape row builder for the sorting spectrum (explicit verb/latency/name). */
    private MockHitRowMessage rowWith(String method, String path, boolean matched, Integer latency,
                                      String stubName, long occurredAt) {
        return new MockHitRowMessage(method, path, Map.of(), Map.of(), null,
                matched ? 200 : null, matched ? Map.of() : null, matched ? "{}" : null,
                matched, matched ? 7L : null, stubName,
                "[]", latency, null, occurredAt);
    }

    private List<String> pathsOf(MockHitPageView page) {
        return page.list().stream()
                .map(h -> (String) h.requestSnapshot().get("path")).toList();
    }

    private List<String> methodsOf(MockHitPageView page) {
        return page.list().stream()
                .map(h -> (String) h.requestSnapshot().get("method")).toList();
    }

    @Test
    void ledgerSortsByWhitelistedColumnsInBothDirections() {
        // page 2: the inspector orders in SQL over a physical/
        // jsonb-expression whitelist. Three rows give path, method, latency,
        // matched and stub_name five different orders to pin.
        long spaceId = newSpaceId();
        long base = 1_700_000_500_000L;
        land(spaceId,
                rowWith("PUT", "/u", false, 9, null, base),
                rowWith("GET", "/g", true, 5, "zeta stub", base + 1000),
                rowWith("POST", "/p", true, 2, "alpha stub", base + 2000));

        assertEquals(List.of("/g", "/p", "/u"),
                pathsOf(queryService.listBySpace(spaceId, null, 1, 10, "path", "asc")),
                "path (jsonb facet) asc must reorder server-side");
        assertEquals(List.of("/u", "/p", "/g"),
                pathsOf(queryService.listBySpace(spaceId, null, 1, 10, "path", "desc")),
                "path desc mirrors asc");
        assertEquals(List.of("GET", "POST", "PUT"),
                methodsOf(queryService.listBySpace(spaceId, null, 1, 10, "method", "asc")),
                "method asc orders independently of the timeline");
        assertEquals(List.of(2, 5, 9),
                queryService.listBySpace(spaceId, null, 1, 10, "latency_ms", "asc")
                        .list().stream().map(MockHitInfo::latencyMs).toList(),
                "latency_ms asc sorts the physical column by value");
        assertEquals(List.of("/u", "/g", "/p"),
                pathsOf(queryService.listBySpace(spaceId, null, 1, 10, "hit_at", "asc")),
                "hit_at asc flips the historical newest-first timeline (oldest first)");

        var matchedAsc = queryService.listBySpace(spaceId, null, 1, 10, "matched", "asc");
        assertEquals(Boolean.FALSE, matchedAsc.list().get(0).matched(),
                "false sorts before true — misses surface first on matched asc");

        var stubAsc = queryService.listBySpace(spaceId, null, 1, 10, "stub_name", "asc");
        assertEquals(List.of("alpha stub", "zeta stub"),
                stubAsc.list().stream().limit(2)
                        .map(h -> (String) h.requestSnapshot().get("stubName")).toList(),
                "stub_name asc orders the jsonb facet; the miss (null) stays last");

        // No sort requested = the historical newest-first timeline, untouched.
        assertEquals(List.of("/p", "/g", "/u"),
                pathsOf(queryService.listBySpace(spaceId, null, 1, 10, null, null)));

        // Filters still stack with sorting (same where, different order).
        var hitsByPath = queryService.listBySpace(spaceId, true, 1, 10, "path", "asc");
        assertEquals(2, hitsByPath.total());
        assertEquals(List.of("/g", "/p"), pathsOf(hitsByPath),
                "matched=true narrows before the whitelisted order applies");
    }

    @Test
    void ledgerSortByInjectionShapedKeyRejected400() {
        long spaceId = newSpaceId();
        land(spaceId, hitRow("/control", 1_700_000_600_000L));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> queryService.listBySpace(spaceId, null, 1, 10, "hit_at; DROP TABLE x", "asc"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不在白名单"), ex.getMessage());

        // Off-list raw column names are refused too — only map values reach SQL.
        assertThrows(ServiceException.class,
                () -> queryService.listBySpace(spaceId, null, 1, 10, "request_snapshot", "asc"));
    }

    @Test
    void provenanceSourceReadsBackFaithfullyWithHonestNullForLegacyRows() {
        // the inspector's purple provenance tag consumes the
        // ledger's source column on the read side — an llm-served row must say
        // llm all the way to the DTO, and a legacy-shaped row (captured through
        // the pre-provenance constructor) must read back NULL, never a
        // fabricated "stub" default.
        long spaceId = newSpaceId();
        long base = 1_700_000_700_000L;
        MockHitRowMessage llmRow = new MockHitRowMessage("GET", "/llm", Map.of(), Map.of(), null,
                200, Map.of(), "{}", true, 7L, "gen stub", "[]", 4, null, base, null, "llm");
        land(spaceId, llmRow, hitRow("/legacy", base + 1000));

        MockHitPageView page = queryService.listBySpace(spaceId, null, 1, 10);
        assertEquals(2, page.total());
        MockHitInfo llm = page.list().stream()
                .filter(h -> "/llm".equals(h.requestSnapshot().get("path")))
                .findFirst().orElseThrow();
        assertEquals("llm", llm.source(), "the honest provenance must ride to the inspector row");
        MockHitInfo legacy = page.list().stream()
                .filter(h -> "/legacy".equals(h.requestSnapshot().get("path")))
                .findFirst().orElseThrow();
        assertNull(legacy.source(), "a pre-provenance row stays NULL — no fabricated default");
    }

    @Test
    void rowKeepsMaskedSnapshotAndTrailAsStored() {
        long spaceId = newSpaceId();
        land(spaceId, hitRow("/keep", 1_700_000_300_000L));

        MockHitInfo row = queryService.listBySpace(spaceId, null, 1, 10).list().get(0);
        assertNotNull(row.hitAt(), "serve instant drives the inspector sort");
        assertEquals("GET", row.requestSnapshot().get("method"));
        Map<?, ?> headers = (Map<?, ?>) row.requestSnapshot().get("headers");
        assertEquals("***", headers.get("authorization"), "masked value passes through verbatim");
        assertEquals(5, row.latencyMs());
        assertNull(row.sessionId());
        assertNull(row.faultApplied(), "no fault was applied on this row — the column stays honest");
    }
}
