package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Inspector "make this request a stub" one click , against
 * the real dev database: rows are landed through the production ledger chain
 * (MQ batch consumer), never hand-written INSERTs — the promote door must see
 * exactly what the inspector sees.
 * <p>Verified spectrum: (1) an answered hit row becomes one exact rule with
 * provenance record + hit:{id} and the recorded status/body; (2) a second
 * click on the same row idempotently skips (the import channel's method+path
 * coverage), never a twin rule; (3) a row without a response plane is refused
 * in plain words — nothing was recorded to promote; (4) a hit id from another
 * space, or a ghost id, is refused (cross-space filing would corrupt the
 * space segment); (5) a forwarded miss row (matched=false, real upstream answer)
 * IS promotable — capturing the upstream's real answer is the point of the
 * one click.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockHitPromoteServiceTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockHitLedgerService ledgerService;

    @Autowired
    private MockHitPromoteService promoteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t362p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpaceId() {
        long projectId = newProject();
        String key = "pr-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId, new MockSpaceInput("Promote " + key, key, null,
                null, "prefix", "none", null, null, 100, null));
    }

    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject("SELECT space_key FROM tp_mock_space WHERE id = ?",
                String.class, spaceId);
    }

    /** Land one masked row through the production ledger door; return its row id. */
    private long landRow(long spaceId, String path, Integer status, String body, boolean matched) {
        MockHitRowMessage row = new MockHitRowMessage("GET", path, Map.of("page", "2"),
                Map.of("authorization", "***"), null,
                status, status == null ? null : Map.of("content-type", "application/json",
                        "authorization", "***", "x-trace", "keep"),
                body, matched, matched ? 7L : null, matched ? "old rule" : null,
                "[]", 9, null, System.currentTimeMillis());
        ledgerService.landBatch(new MockHitBatchMessage(spaceKeyOf(spaceId), "v1",
                "batch-" + UUID.randomUUID(), List.of(row), System.currentTimeMillis()));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_mock_hit_log WHERE space_id = ? AND request_snapshot ->> 'path' = ?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class, spaceId, path);
        assertNotNull(id, "row must be landed before promoting");
        return id;
    }

    @Test
    void answeredRowBecomesOneExactRuleRetracingToTheHit() throws Exception {
        long spaceId = newSpaceId();
        long hitId = landRow(spaceId, "/orders/42", 200, "{\"ok\":true}", true);

        MockStubImportResult result = promoteService.promote(spaceId, hitId);

        assertEquals(1, result.createdStubIds().size());
        Long stubId = result.createdStubIds().get(0);
        Map<String, Object> stub = jdbcTemplate.queryForMap(
                "SELECT name, method, path_match_type, path_pattern, source, source_ref,"
                        + " response_json::text AS response_json FROM tp_mock_stub WHERE id = ?",
                stubId);
        assertEquals("GET", stub.get("method"));
        assertEquals("exact", stub.get("path_match_type"));
        assertEquals("/orders/42", stub.get("path_pattern"), "query facet must be stripped");
        assertEquals("record", stub.get("source"));
        assertEquals("hit:" + hitId, stub.get("source_ref"));
        // response_json comes back as jsonb::text — normalized key order and
        // whitespace, so assert on parsed fields, never on raw substrings.
        com.fasterxml.jackson.databind.JsonNode doc =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree((String) stub.get("response_json"));
        assertEquals(200, doc.get("status").asInt());
        assertEquals("static", doc.get("bodyMode").asText());
        assertEquals("{\"ok\":true}", doc.get("body").asText(),
                "recorded body must ride into the stub verbatim");
        assertEquals("keep", doc.get("headers").get("x-trace").asText(), "innocuous header survives");
        assertTrue(doc.get("headers").get("authorization") == null
                        || !"***".equals(doc.get("headers").get("authorization").asText()),
                "masked sentinel must never ride into a stub");
        assertTrue(doc.get("headers").get("content-length") == null,
                "size-gated header must be dropped");
    }

    @Test
    void secondClickOnTheSameRowSkipsIdempotently() {
        long spaceId = newSpaceId();
        long hitId = landRow(spaceId, "/orders/43", 200, "{}", true);

        assertEquals(1, promoteService.promote(spaceId, hitId).createdStubIds().size());
        MockStubImportResult again = promoteService.promote(spaceId, hitId);

        assertTrue(again.createdStubIds().isEmpty(), "no twin rule on re-click");
        assertEquals(1, again.skippedEndpointKeys().size());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_stub WHERE space_id = ? AND path_pattern = '/orders/43'",
                Integer.class, spaceId).intValue(), "one rule only");
    }

    @Test
    void forwardedMissRowIsPromotable() {
        long spaceId = newSpaceId();
        long hitId = landRow(spaceId, "/upstream/real", 201, "created", false);

        MockStubImportResult result = promoteService.promote(spaceId, hitId);

        assertEquals(1, result.createdStubIds().size(),
                "a forwarded answer is exactly what users want to freeze as a stub");
    }

    @Test
    void rowWithoutResponsePlaneIsRefusedInPlainWords() {
        long spaceId = newSpaceId();
        long hitId = landRow(spaceId, "/dead/transport", null, null, false);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> promoteService.promote(spaceId, hitId));
        assertTrue(ex.getMessage().contains("响应"), ex.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_stub WHERE space_id = ?",
                Integer.class, spaceId).intValue(), "refusal must not file anything");
    }

    @Test
    void foreignOrGhostHitIdIsRefused() {
        long spaceA = newSpaceId();
        long spaceB = newSpaceId();
        long hitInA = landRow(spaceA, "/only/in-a", 200, "x", true);

        assertThrows(ServiceException.class, () -> promoteService.promote(spaceB, hitInA));
        ServiceException ghost = assertThrows(ServiceException.class,
                () -> promoteService.promote(spaceA, 999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, ghost.getCode());
    }
}
