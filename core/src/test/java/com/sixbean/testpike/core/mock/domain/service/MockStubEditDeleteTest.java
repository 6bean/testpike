package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stub edit/delete segment (V240) — closure of the create-only gap: the rule
 * source finally has update and remove doors, gated by the SAME save-chain
 * single point create runs through, plus an optimistic lock against lost
 * updates. All against the real dev database (no mocks).
 * <p>Verified: (1) update persists every mutable column, bumps the row
 * version and moves the owning space's snapshot pin (the traffic plane picks
 * the edit up on its next poll); (2) a stale version echo is refused with the
 * plain-word VERSION_CONFLICT and the row keeps the first writer's values;
 * (3) provenance columns are immutable through the edit door even when the
 * payload claims otherwise; (4) illegal payloads fail exactly like create
 * (no second validation spectrum); (5) delete removes the row, moves the pin,
 * and keeps hit-ledger history; ghost ids are refused in plain words; (6) the
 * recent-hit impact count reads the ledger over its 7-day window and survives
 * the row it counted (count before delete = count after for the ledger side).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockStubEditDeleteTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "v240" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(String prefix) {
        String key = prefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(newProject(), new MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    private MockStubInput input(String name, String method, String pattern, String response) {
        return new MockStubInput(name, 100, method, "exact", pattern,
                "[]", response, null, null, null, "manual", "anchor-ref", true);
    }

    private long seedStub(long spaceId) {
        return stubService.create(spaceId, input("order rule", "GET", "/orders",
                "{\"status\":200,\"body\":\"first\"}"));
    }

    @Test
    void updatePersistsColumnsBumpsVersionAndMovesSpacePin() {
        long spaceId = newSpace("eu");
        // Published before the pin check: a draft snapshot serves an empty
        // stub set by design, so rule drift can only move a live pin here.
        spaceService.transition(spaceId, 1, 0);
        long stubId = seedStub(spaceId);
        MockStubInfo before = stubService.get(stubId);
        assertEquals(1, before.version().intValue(), "fresh rows carry version 1");
        String pinBefore = spaceService.snapshot(spaceKeyOf(spaceId)).version();

        MockStubInfo after = stubService.update(stubId, new MockStubInput(
                "renamed rule", 50, "POST", "exact", "/orders/{id}",
                "[{\"kind\":\"query\",\"key\":\"page\",\"matchType\":\"exact\",\"value\":\"2\"}]",
                "{\"status\":201,\"body\":\"second\"}", null, null, null,
                "manual", "forged-ref", true, before.version()));

        assertEquals("renamed rule", after.name());
        assertEquals(50, after.priority().intValue());
        assertEquals("POST", after.method());
        assertEquals("/orders/{id}", after.pathPattern());
        assertEquals(2, after.version().intValue(), "each update bumps the lock");
        assertNotNull(stubService.get(stubId).matchersJson());
        String pinAfter = spaceService.snapshot(spaceKeyOf(spaceId)).version();
        assertNotEquals(pinBefore, pinAfter, "an edit of a live rule must move the pin");
    }

    @Test
    void updateWithStaleVersionIsRefusedAndRowKeepsFirstWriterValues() {
        long spaceId = newSpace("ec");
        long stubId = seedStub(spaceId);
        int v = stubService.get(stubId).version();
        stubService.update(stubId, echo(v, "writer A"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> stubService.update(stubId, echo(v, "writer B")));
        assertEquals(BizErrorCode.VERSION_CONFLICT, ex.getCode().intValue());
        assertTrue(ex.getMessage().contains("「编辑」") && ex.getMessage().contains("仿真文档"),
                "stale-version refusal must name the re-open entry (R63), got: " + ex.getMessage());
        assertEquals("writer A", stubService.get(stubId).name(),
                "the racing loser must never overwrite the winner");
    }

    @Test
    void provenanceColumnsAreImmutableThroughTheEditDoor() {
        long spaceId = newSpace("pi");
        long stubId = seedStub(spaceId);
        MockStubInfo current = stubService.get(stubId);
        stubService.update(stubId, new MockStubInput(current.name(), current.priority(),
                current.method(), current.pathMatchType(), current.pathPattern(),
                "[]", "{\"status\":200}", null, null, null,
                "openapi", "forged-spec-id", true, current.version()));
        MockStubInfo after = stubService.get(stubId);
        assertEquals("manual", after.source(), "source is a birth fact, not an edit field");
        assertEquals("anchor-ref", after.sourceRef());
    }

    @Test
    void illegalEditsFailOnTheSameSinglePointAsCreate() {
        long spaceId = newSpace("il");
        long stubId = seedStub(spaceId);
        int v = stubService.get(stubId).version();
        // bad verb
        assertThrows(ServiceException.class, () -> stubService.update(stubId,
                new MockStubInput("n", 1, "BREW", "exact", "/x", "[]", "{}",
                        null, null, null, null, null, true, v)));
        // path must start with /
        assertThrows(ServiceException.class, () -> stubService.update(stubId,
                new MockStubInput("n", 1, "GET", "exact", "x/y", "[]", "{}",
                        null, null, null, null, null, true, v)));
        // orphan state columns without a scenario
        assertThrows(ServiceException.class, () -> stubService.update(stubId,
                new MockStubInput("n", 1, "GET", "exact", "/x", "[]", "{}",
                        null, "PAID", null, null, null, true, v)));
    }

    @Test
    void deleteRemovesRowMovesPinAndKeepsLedgerHistory() {
        long spaceId = newSpace("dl");
        spaceService.transition(spaceId, 1, 0); // live pin, same posture as above
        long stubId = seedStub(spaceId);
        String spaceKey = spaceKeyOf(spaceId);
        // one matched hit attributed to the rule
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now())", spaceId, stubId);
        assertEquals(1L, stubService.recentHitCount(stubId, 7),
                "the impact preview counts the rule's recent serves");

        String pinBefore = spaceService.snapshot(spaceKey).version();
        stubService.delete(stubId);
        String pinAfter = spaceService.snapshot(spaceKey).version();
        assertNotEquals(pinBefore, pinAfter, "removing a served rule must move the pin");
        assertThrows(ServiceException.class, () -> stubService.get(stubId),
                "ghost reads are refused in plain words");
        Integer ledger = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_hit_log WHERE stub_id = ?", Integer.class, stubId);
        assertEquals(1, ledger.intValue(), "traffic history survives rule churn");
    }

    @Test
    void ghostIdsRefusedForUpdateDeleteAndImpactRead() {
        assertThrows(ServiceException.class, () -> stubService.update(999_999_999L,
                input("n", "GET", "/x", "{}")));
        assertThrows(ServiceException.class, () -> stubService.delete(999_999_999L));
        assertThrows(ServiceException.class, () -> stubService.recentHitCount(999_999_999L, 7));
    }

    /** The pin of a draft space carries an empty stub list; still comparable. */
    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT space_key FROM tp_mock_space WHERE id = ?", String.class, spaceId);
    }

    private MockStubInput echo(int version, String name) {
        return new MockStubInput(name, 100, "GET", "exact", "/orders",
                "[]", "{\"status\":200}", null, null, null, "manual", null, true, version);
    }
}
