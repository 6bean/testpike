package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncRoundResult;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncSaveRequest;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP knowledge sync ledger read face .
 *
 * <p>What the subscription drawer renders comes from here: the paged list
 * carries the four bookkeeping columns the scheduler writes back each round
 * (last sync moment, new/skipped entry counts, typed failure), the list is
 * scoped to one MCP server so the drawer inside a server detail only sees
 * that server's subscriptions, and a manually triggered round against a
 * DOWN server idles on the existing connectivity probe — it returns the
 * typed round outcome with zero counts instead of throwing or reaching a
 * second transport stack (no 5xx on the ledger face).
 *
 * <p>Real local DB, no mocks (R3). Data hygiene (R37): every row created
 * here is deleted by its own id; factory-seeded tables are never truncated.
 * The MCP server fixture points at a closed port (127.0.0.1:1) so the DOWN
 * path is deterministic without any network dependency.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class McpKnowledgeSyncLedgerFaceTest {

    @Autowired
    private McpKnowledgeSyncService syncService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long serverId;
    private long otherServerId;
    private final List<Long> createdSubscriptionIds = new ArrayList<>();
    private String suffix;

    @BeforeEach
    void createFixtures() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        projectId = projectService.create(1L, new ProjectCreateInput(
                "MCP ledger project " + suffix, "ledger-" + suffix, "zh-CN", "P1", null));
        String snapshot = """
                [
                  {"name":"readOnlyDocs","description":"fetch docs","inputSchemaDigest":"d1","enabled":true,
                   "annotations":{"readOnlyHint":true,"destructiveHint":false}}
                ]""";
        serverId = insertServer("ledger-server-a-" + suffix, snapshot);
        otherServerId = insertServer("ledger-server-b-" + suffix, snapshot);
    }

    private long insertServer(String name, String snapshot) {
        jdbcTemplate.update("INSERT INTO tp_mcp_server (name, endpoint, transport, tools_json, enabled)"
                        + " VALUES (?, ?, 2, ?::jsonb, 1)",
                name, "http://127.0.0.1:1/mcp", snapshot);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_mcp_server WHERE name = ?", Long.class, name);
    }

    @AfterEach
    void cleanOwnRows() {
        McpSyncSubscriptionCleaner.purge(jdbcTemplate, createdSubscriptionIds);
        jdbcTemplate.update("DELETE FROM tp_mcp_server WHERE id IN (?, ?)", serverId, otherServerId);
        // FK child first (precedent: McpKnowledgeSyncLoopTest cleanup ordering).
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private long saveSubscription(long server, String name) {
        McpKnowledgeSyncInfo saved = syncService.save(1L, new McpKnowledgeSyncSaveRequest(
                projectId, server, name, "readOnlyDocs",
                Map.of("query", "release notes"), 360, projectId, 0L, null));
        createdSubscriptionIds.add(saved.id());
        return saved.id();
    }

    @Test
    void listRowsCarryTheFourBookkeepingColumnsAfterARealRound() {
        long id = saveSubscription(serverId, "ledger-cols-" + suffix);
        assertNull(syncService.list(projectId, serverId, 1, 20).list().stream()
                .filter(r -> r.id() == id).findFirst().orElseThrow().lastSyncAt(),
                "no round has run yet: the ledger columns must read empty, not fabricated");

        syncService.ingestPayload(id, "ledger content " + suffix, "http://127.0.0.1:1/mcp");

        McpKnowledgeSyncInfo row = syncService.list(projectId, serverId, 1, 20).list().stream()
                .filter(r -> r.id() == id).findFirst().orElseThrow();
        assertNotNull(row.lastSyncAt(), "last sync moment must be visible on the list row");
        assertEquals("updated", row.lastStatus());
        assertEquals(Integer.valueOf(1), row.lastNewCount(), "landed entries carry the new count");
        assertEquals(Integer.valueOf(0), row.lastSkippedCount());
        assertNull(row.lastError(), "a clean round leaves no failure typing");
    }

    @Test
    void listScopedToServerHidesSubscriptionsOfOtherServers() {
        long onA = saveSubscription(serverId, "scope-a-" + suffix);
        saveSubscription(otherServerId, "scope-b-" + suffix);

        PageResult<McpKnowledgeSyncInfo> scoped = syncService.list(projectId, serverId, 1, 20);
        List<McpKnowledgeSyncInfo> mine = scoped.list().stream()
                .filter(r -> r.projectId() != null && r.projectId() == projectId).toList();
        assertEquals(1, mine.size(), "server scope must return only this server's subscriptions");
        assertEquals(onA, mine.get(0).id());

        PageResult<McpKnowledgeSyncInfo> unscoped = syncService.list(projectId, null, 1, 20);
        assertTrue(unscoped.list().stream()
                        .filter(r -> Long.valueOf(projectId).equals(r.projectId())).count() >= 2,
                "without the server scope both subscriptions of the project stay visible");
    }

    @Test
    void manualTriggerOnDownServerIdlesThroughTheProbeAndCarriesZeroCounts() {
        long id = saveSubscription(serverId, "down-gate-" + suffix);
        // The drawer's run-now button: a DOWN server must NOT throw into the
        // HTTP face and must NOT open a second transport attempt — the probe
        // gate types the refusal and the round returns zero counters.
        McpKnowledgeSyncRoundResult round = syncService.runOnce(1L, id);
        assertEquals("failed", round.status());
        assertEquals(0, round.newCount());
        assertEquals(0, round.skippedCount());
        assertNotNull(round.error());
        assertFalse(round.error().isBlank(), "the typed refusal must name its category for the ledger row");

        McpKnowledgeSyncInfo row = syncService.detail(id);
        assertEquals("failed", row.lastStatus());
        assertEquals(round.error(), row.lastError(), "the ledger row echoes exactly what the round returned");
        assertEquals(Integer.valueOf(0), row.lastNewCount());
        assertEquals(Integer.valueOf(0), row.lastSkippedCount());
    }
}
