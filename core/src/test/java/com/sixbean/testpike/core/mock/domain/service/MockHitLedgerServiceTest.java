package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hit ledger landing service : core is the
 * only writer of tp_mock_hit_log — the data plane ships masked rows over MQ
 * and this service turns one batch message into ledger rows.
 * <p>Verified spectrum against the real dev database (no mocks): (1) a batch
 * with one hit and one miss lands two rows — miss keeps stub_id NULL (the
 * "why is my mock not hitting" evidence), hit carries the winner stub;
 * (2) request_snapshot lands as a jsonb object of the already-masked row
 * facets (method/path/query/headers/body) exactly as the plane sent them —
 * core does NOT re-mask, and must never drop facets either;
 * (3) hit_at is the plane's occurredAt instant (ledger timeline = serve time,
 * not consume time — the inspector sorts by it);
 * (4) an unknown spaceKey rejects the whole batch with a NOT_FOUND style
 * ServiceException so the message ends up in the dead letter after retry —
 * silent loss is the forbidden alternative;
 * (5) a corrupt match_trace never poisons the row: the ledger keeps the
 * verdict trail as JSON text, falling back to an empty array honestly.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockHitLedgerServiceTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockHitLedgerService ledgerService;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t358c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    /** A dedicated space keeps every ledger assertion isolated from other rows. */
    private String newSpaceKey() {
        long projectId = newProject();
        String key = "hl-" + UUID.randomUUID().toString().substring(0, 10);
        spaceService.create(projectId, new MockSpaceInput("Ledger " + key, key, null, null,
                "prefix", "none", null, null, 100, null));
        return key;
    }

    private MockHitRowMessage hitRow() {
        return new MockHitRowMessage("POST", "/orders/1",
                Map.of("verbose", "true"), Map.of("x-trace", "abc", "authorization", "***"),
                "{\"total\":10}",
                200, Map.of("content-type", "application/json"), "{\"ok\":true}",
                true, 42L, "order stub",
                "[{\"stub\":\"order stub\",\"step\":\"path\",\"outcome\":\"pass\",\"detail\":\"\"}]",
                17, "sess-9", 1_700_000_000_123L, "status");
    }

    private MockHitRowMessage missRow() {
        return new MockHitRowMessage("GET", "/ghost", Map.of(), Map.of(), null,
                null, null, null,
                false, null, null, "[]", 3, null, 1_700_000_001_456L);
    }

    private List<Map<String, Object>> rowsOf(String spaceKey) {
        return jdbcTemplate.queryForList("""
                SELECT h.stub_id, h.matched, h.request_snapshot::text AS snapshot,
                       h.response_snapshot::text AS response_snapshot,
                       h.match_trace::text AS trace, h.latency_ms, h.fault_applied,
                       h.session_id, h.source, h.hit_at
                FROM tp_mock_hit_log h
                JOIN tp_mock_space s ON s.id = h.space_id
                WHERE s.space_key = ? ORDER BY h.id""", spaceKey);
    }

    @Test
    void honestSourceLabelLandsAsItsOwnColumnAndStaysNullForPreM10Rows() {
        // V241 (M10 : the ledger must be able to say WHO answered —
        // a real stub, an AI fallback, a passthrough. Rows written through the
        // legacy shape keep NULL (absent and 'stub' must stay tellable apart).
        String spaceKey = newSpaceKey();
        MockHitRowMessage llmRow = new MockHitRowMessage("GET", "/ghost/ai", Map.of(), Map.of(), null,
                200, null, "{\"generated\":true}",
                false, null, null, "[]", 800, null, 1_700_000_002_000L, null, "llm");
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(llmRow, hitRow()), System.currentTimeMillis()));

        List<Map<String, Object>> rows = rowsOf(spaceKey);
        assertEquals("llm", rows.get(0).get("source"),
                "the AI-fallback provenance the plane decided lands verbatim");
        assertNull(rows.get(1).get("source"),
                "rows built without the new facet keep NULL — never a fabricated default");
    }

    @Test
    void hitAndMissBatchLandsBothRowsWithMaskedSnapshot() {
        String spaceKey = newSpaceKey();
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(hitRow(), missRow()),
                System.currentTimeMillis()));

        List<Map<String, Object>> rows = rowsOf(spaceKey);
        assertEquals(2, rows.size(), "one hit + one miss must both land — misses are inspector evidence");

        Map<String, Object> hit = rows.get(0);
        assertEquals(Boolean.TRUE, hit.get("matched"));
        assertEquals(42L, ((Number) hit.get("stub_id")).longValue());
        String snapshot = (String) hit.get("snapshot");
        assertTrue(snapshot.contains("\"POST\"") && snapshot.contains("/orders/1")
                && snapshot.contains("verbose") && snapshot.contains("***")
                && snapshot.contains("total"), "snapshot keeps every masked facet as jsonb");
        assertFalse(snapshot.contains("Bearer "), "ledger must not hold raw bearer material");
        assertTrue(((String) hit.get("trace")).contains("order stub"), "verdict trail lands as jsonb");
        assertEquals(17, ((Number) hit.get("latency_ms")).intValue());
        assertEquals("sess-9", hit.get("session_id"));

        Map<String, Object> miss = rows.get(1);
        assertEquals(Boolean.FALSE, miss.get("matched"));
        assertNull(miss.get("stub_id"), "miss rows keep stub_id NULL by design");
        assertEquals("[]", miss.get("trace"));
        assertNull(miss.get("session_id"));
        assertEquals("status", hit.get("fault_applied"),
                "the fault label the plane decided lands as its own column — reports consume it");
        assertNull(miss.get("fault_applied"), "no fault rides a miss row");
    }

    @Test
    void hitAtFollowsServeTimeNotConsumeTime() {
        String spaceKey = newSpaceKey();
        // 2023-11-14T22:13:20.123Z — well before "now": the ledger timeline is
        // the plane's serve instant, not the moment core got around to consume.
        MockHitRowMessage old = new MockHitRowMessage("GET", "/old", Map.of(), Map.of(), null,
                null, null, null, false, null, null, "[]", 1, null, 1_700_000_000_123L);
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(old), System.currentTimeMillis()));

        List<Map<String, Object>> rows = rowsOf(spaceKey);
        assertEquals(1, rows.size());
        java.sql.Timestamp hitAt = (java.sql.Timestamp) rows.get(0).get("hit_at");
        assertNotNull(hitAt);
        assertTrue(hitAt.toLocalDateTime().isBefore(java.time.LocalDateTime.now().minusYears(1)),
                "hit_at must be the occurredAt instant (serve time), got " + hitAt);
    }

    @Test
    void responseSnapshotLandsWithFacetsAndStaysNullWhenAbsent() throws Exception {
        String spaceKey = newSpaceKey();
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(hitRow(), missRow()),
                System.currentTimeMillis()));

        List<Map<String, Object>> rows = rowsOf(spaceKey);
        assertEquals(2, rows.size());

        String served = (String) rows.get(0).get("response_snapshot");
        assertNotNull(served, "a served row must carry the response plane");
        // Parse the landed jsonb instead of substring-hunting: an embedded body
        // document arrives quote-escaped in ::text form, structure is the truth.
        com.fasterxml.jackson.databind.JsonNode doc =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(served);
        assertEquals(200, doc.get("status").asInt());
        assertEquals("application/json", doc.get("headers").get("content-type").asText());
        assertTrue(doc.get("body").asText().contains("\"ok\":true"),
                "response facets land as one jsonb document");

        assertNull(rows.get(1).get("response_snapshot"),
                "a row without response facets stores NULL, not an empty document — "
                        + "absent and empty must stay tellable apart");
    }

    @Test
    void unknownSpaceKeyRejectsWholeBatchInsteadOfSilentLoss() {
        ServiceException ex = assertThrows(ServiceException.class, () -> ledgerService.landBatch(
                new MockHitBatchMessage("ghost-space-" + UUID.randomUUID().toString().substring(0, 8),
                        "v1", "batch-" + UUID.randomUUID(), List.of(missRow()),
                        System.currentTimeMillis())));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("ghost-space"), "rejection names the space in plain words");
    }

    @Test
    void corruptMatchTraceLandsAsHonestEmptyArray() {
        String spaceKey = newSpaceKey();
        MockHitRowMessage broken = new MockHitRowMessage("GET", "/x", Map.of(), Map.of(), null,
                null, null, null, false, null, null, "{not json", 2, null, System.currentTimeMillis());
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(broken), System.currentTimeMillis()));

        List<Map<String, Object>> rows = rowsOf(spaceKey);
        assertEquals(1, rows.size(), "one bad trail must not lose the row itself");
        assertEquals("[]", rows.get(0).get("trace"));
    }

    /**
 * Body truncation bound :
 * oversized request AND response bodies must land capped with an honest
 * truncation tail note, small bodies land verbatim. One gate serves both
 * facets through MockBodyTruncator — the ledger writer never caps twice
 * and never lets a payload deluge bloat the inspector table.
 */
    @Test
    void oversizedBodiesAreCappedOnBothFacetsWithTruncationMarker() {
        String spaceKey = newSpaceKey();
        String big = "x".repeat(300);
        String bigResp = "y".repeat(300);
        MockHitRowMessage oversized = new MockHitRowMessage("POST", "/big", Map.of(), Map.of(), big,
                200, Map.of(), bigResp, true, 1L, "big stub", "[]", 5, null,
                System.currentTimeMillis());
        com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder holder =
                configCacheHolder;
        holder.put("mock.hit.max-body-chars", "32");
        try {
            ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                    "batch-" + UUID.randomUUID(), List.of(oversized), System.currentTimeMillis()));

            List<Map<String, Object>> rows = rowsOf(spaceKey);
            assertEquals(1, rows.size());
            String req = (String) rows.get(0).get("snapshot");
            String resp = (String) rows.get(0).get("response_snapshot");
            assertTrue(req.contains("x".repeat(32)), "request body keeps the cap-sized head");
            assertFalse(req.contains("x".repeat(33)), "request body must not exceed the cap");
            assertTrue(req.contains("[truncated, 300 chars]"),
                    "request truncation tail note names the original size, got: " + req);
            assertTrue(resp.contains("y".repeat(32)) && resp.contains("[truncated, 300 chars]"),
                    "response side shares the same cap in the same place");
        } finally {
            // Restore the SEEDED value (not a cache removal): the truncation
            // cap default here is 8192, but an earlier test in this context
            // may have left the singleton holder stale — removal would let the
            // code fall back to a default unrelated to the DB row (cross-test
            // bleed precedent: AppautoReservationApprovalTest restore pattern).
            holder.put("mock.hit.max-body-chars", "8192");
        }
    }

    @Test
    void bodiesWithinCapLandVerbatim() throws Exception {
        String spaceKey = newSpaceKey();
        MockHitRowMessage small = new MockHitRowMessage("POST", "/small", Map.of(), Map.of(),
                "{\"ok\":1}", 200, Map.of(), "{\"done\":2}", true, 2L, "small stub", "[]",
                5, null, System.currentTimeMillis());
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(small), System.currentTimeMillis()));

        // Parse the landed jsonb instead of substring-hunting: an embedded body
        // document arrives quote-escaped in ::text form (structure is the truth).
        String snapshot = (String) rowsOf(spaceKey).get(0).get("snapshot");
        com.fasterxml.jackson.databind.JsonNode doc =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapshot);
        assertEquals("{\"ok\":1}", doc.get("body").asText(),
                "under-limit bodies are never rewritten");
        assertFalse(snapshot.contains("truncated"));
    }

    @Test
    void blankSpaceKeyIsRefusedBeforeTouchingTheTable() {
        assertThrows(ServiceException.class, () -> ledgerService.landBatch(
                new MockHitBatchMessage("   ", "v1", "batch-x", List.of(missRow()), 0L)));
    }
}
