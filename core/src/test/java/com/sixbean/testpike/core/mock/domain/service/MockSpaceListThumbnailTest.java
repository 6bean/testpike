package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Space list thumbnails : the mock-binding selector in the
 * case/suite editor needs to show, per space, how many rules it holds and how
 * healthy its served traffic is — without opening the space first. The list
 * read side therefore carries two extra projection columns:
 * <p>ruleCount = rows in tp_mock_stub for the space (enabled and disabled both
 * counted — it is a configuration inventory, not the traffic-plane snapshot,
 * which deliberately hides disabled rules);
 * <p>hitRate = matched rows / all ledger rows for the space, sourced through
 * {@link MockHitQueryService#countBySpace} so the hit/miss spectrum has exactly
 * one definition in the codebase (red line 6 — no second spectrum in the space
 * service);
 * <p>a space with no ledger rows yet gets hitRate = null (no sample is not a
 * zero rate — a friendly 0% would read as "this mock always misses").
 * <p>Single-row reads (get/transition) keep the columns null: the projection
 * belongs to the list, and nulls stay null instead of bridge-invented numbers.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockSpaceListThumbnailTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private MockHitLedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t364b" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpaceId(long projectId, String prefix) {
        String key = prefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId, new MockSpaceInput("Space " + key, key, null,
                null, "prefix", "none", null, null, 100, null));
    }

    private MockStubInput stub(String name, boolean enabled) {
        return new MockStubInput(name, 100, "GET", "exact", "/" + name,
                "[]", "{}", null, null, null, "manual", null, enabled);
    }

    private MockHitRowMessage hitRow(long spaceId, long occurredAt) {
        return new MockHitRowMessage("GET", "/thumb", Map.of(), Map.of(), null,
                200, Map.of(), "{}",
                true, 7L, "thumb stub",
                "[{\"stub\":\"thumb stub\",\"step\":\"path\",\"outcome\":\"pass\",\"detail\":\"\"}]",
                3, null, occurredAt);
    }

    private MockHitRowMessage missRow(long occurredAt) {
        return new MockHitRowMessage("GET", "/ghost", Map.of(), Map.of(), null,
                null, null, null,
                false, null, null,
                "[{\"stub\":\"x\",\"step\":\"method\",\"outcome\":\"fail\",\"detail\":\"GET != POST\"}]",
                2, null, occurredAt);
    }

    private void land(long spaceId, MockHitRowMessage... rows) {
        String spaceKey = jdbcTemplate.queryForObject(
                "SELECT space_key FROM tp_mock_space WHERE id = ?", String.class, spaceId);
        ledgerService.landBatch(new MockHitBatchMessage(spaceKey, "v1",
                "batch-" + UUID.randomUUID(), List.of(rows), System.currentTimeMillis()));
    }

    @Test
    void listCarriesRuleCountAndHitRatePerSpace() {
        long projectId = newProject();
        long spaceId = newSpaceId(projectId, "thumb");
        stubService.create(spaceId, stub("on-" + System.nanoTime(), true));
        stubService.create(spaceId, stub("off-" + System.nanoTime(), false));

        long base = 1_700_000_900_000L;
        land(spaceId, hitRow(spaceId, base), hitRow(spaceId, base + 1000), missRow(base + 2000));

        List<MockSpaceInfo> rows = spaceService.listByProject(projectId);
        assertEquals(1, rows.size());
        MockSpaceInfo info = rows.get(0);
        assertEquals(2, info.ruleCount().intValue(),
                "rule count = configuration inventory, disabled rules included");
        assertEquals(2.0 / 3.0, info.hitRate(), 1e-9,
                "hit rate = matched/all over the ledger spectrum, same source as the inspector count");
    }

    @Test
    void spaceWithoutTrafficHoldsZeroRulesAndNullRate() {
        long projectId = newProject();
        newSpaceId(projectId, "quiet");

        MockSpaceInfo info = spaceService.listByProject(projectId).get(0);
        assertEquals(0, info.ruleCount().intValue(), "no rules yet is an honest zero");
        assertNull(info.hitRate(), "no sample must not be dressed up as a 0% miss rate");
    }

    @Test
    void singleRowReadKeepsThumbnailColumnsNull() {
        long projectId = newProject();
        long spaceId = newSpaceId(projectId, "single");
        stubService.create(spaceId, stub("one-" + System.nanoTime(), true));

        assertNull(spaceService.get(spaceId).ruleCount(),
                "the projection belongs to the list; single reads stay null, never bridge-invented");
        assertNull(spaceService.get(spaceId).hitRate());
    }
}
