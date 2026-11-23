package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP knowledge sync subscription registration segment .
 *
 * <p>Guards asserted here (all against the real local DB, no mocks): save
 * persists and echoes back; duplicate name inside one project is a readable
 * 409; unknown server is refused by name; a tool that is not in the enabled
 * snapshot is refused; a tool that IS enabled but lacks the read-only
 * annotation is refused fail-closed with the reason spelled out; interval
 * outside the guard range is refused with the range named; an invisible
 * target project is refused; pause/resume flips the enabled flag; update
 * re-validates; delete removes the row.
 *
 * <p>Data hygiene (R37): rows created by this test are removed by their own
 * ids in {@link #afterEach}; factory-seeded tables are never truncated.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = {"testpike.scheduling.enabled=false"})
class McpKnowledgeSyncServiceTest {

    @Autowired
    private McpKnowledgeSyncService syncService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long serverId;
    private final List<Long> createdSubscriptionIds = new ArrayList<>();
    private String suffix;

    @BeforeEach
    void createFixtures() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        projectId = projectService.create(1L, new ProjectCreateInput(
                "MCP sync project " + suffix, "sync-" + suffix, "zh-CN", "P1", null));
        // Snapshot shape carried by tp_mcp_server.tools_json (see V39): array of
        // {name, description, inputSchemaDigest, enabled[, annotations]}.
        // readOnlyDocs = enabled + annotated read-only; writeDocs = enabled but
        // mutating; hiddenTool = present but not enabled.
        String snapshot = """
                [
                  {"name":"readOnlyDocs","description":"fetch docs","inputSchemaDigest":"d1","enabled":true,
                   "annotations":{"readOnlyHint":true,"destructiveHint":false}},
                  {"name":"writeDocs","description":"mutate docs","inputSchemaDigest":"d2","enabled":true,
                   "annotations":{"readOnlyHint":false}},
                  {"name":"hiddenTool","description":"disabled one","inputSchemaDigest":"d3","enabled":false,
                   "annotations":{"readOnlyHint":true}}
                ]""";
        jdbcTemplate.update("INSERT INTO tp_mcp_server (name, endpoint, transport, tools_json, enabled)"
                        + " VALUES (?, ?, 2, ?::jsonb, 1)",
                "sync-server-" + suffix, "http://127.0.0.1:1/mcp", snapshot);
        serverId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_mcp_server WHERE name = ?", Long.class, "sync-server-" + suffix);
    }

    @AfterEach
    void cleanOwnRows() {
        for (Long id : createdSubscriptionIds) {
            jdbcTemplate.update("DELETE FROM tp_mcp_knowledge_sync WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM tp_mcp_server WHERE id = ?", serverId);
        // The owner membership created by projectService.create is an FK child
        // (tp_user_project_project_id_fkey) — drop it before the project row
        // (precedent: DefectLibraryServiceTest cleanup ordering).
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private McpKnowledgeSyncSaveRequest validRequest(String name, String toolName) {
        return new McpKnowledgeSyncSaveRequest(projectId, serverId, name, toolName,
                Map.of("query", "release notes"), 360, projectId, 0L, null);
    }

    private long saveAndTrack(McpKnowledgeSyncSaveRequest request) {
        McpKnowledgeSyncInfo info = syncService.save(1L, request);
        createdSubscriptionIds.add(info.id());
        return info.id();
    }

    @Test
    void savePersistsAndEchoesBack() {
        String name = "sync-" + suffix;
        McpKnowledgeSyncInfo info = syncService.save(1L, validRequest(name, "readOnlyDocs"));
        createdSubscriptionIds.add(info.id());

        assertEquals(name, info.name());
        assertEquals(serverId, info.serverId());
        assertEquals("readOnlyDocs", info.toolName());
        assertEquals(360, info.syncIntervalMinutes());
        assertEquals(projectId, info.targetProjectId());
        assertFalse(info.autoActivate(), "quality gate must default to off");
        assertTrue(info.enabled(), "a fresh subscription starts active");

        Integer dbCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mcp_knowledge_sync WHERE id = ?", Integer.class, info.id());
        assertEquals(1, dbCount, "subscription row must be persisted");
        String mapping = jdbcTemplate.queryForObject(
                "SELECT param_mapping::text FROM tp_mcp_knowledge_sync WHERE id = ?",
                String.class, info.id());
        assertTrue(mapping.contains("release notes"), "param mapping must be stored as JSONB");
    }

    @Test
    void duplicateNameInsideProjectIsReadableConflict() {
        String name = "dup-" + suffix;
        saveAndTrack(validRequest(name, "readOnlyDocs"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> saveAndTrack(validRequest(name, "readOnlyDocs")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains(name), "conflict message must name the clashing subscription");
    }

    @Test
    void unknownServerIsRefusedByName() {
        McpKnowledgeSyncSaveRequest request = new McpKnowledgeSyncSaveRequest(
                projectId, 999_999_999L, "ghost-" + suffix, "readOnlyDocs",
                Map.of(), 360, projectId, 0L, null);
        ServiceException ex = assertThrows(ServiceException.class, () -> syncService.save(1L, request));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("999999999"), "message must name the missing server id");
    }

    @Test
    void toolOutsideEnabledSnapshotIsRefused() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> saveAndTrack(validRequest("off-list-" + suffix, "notInSnapshot")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("notInSnapshot"), "message must name the offending tool");
    }

    @Test
    void enabledToolWithoutReadOnlyAnnotationIsRefusedFailClosed() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> saveAndTrack(validRequest("mutating-" + suffix, "writeDocs")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("writeDocs"), "message must name the tool");
        assertTrue(ex.getMessage().toLowerCase().contains("read-only"),
                "message must state the read-only guard as the reason");
    }

    @Test
    void disabledToolIsRefusedEvenWhenAnnotatedReadOnly() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> saveAndTrack(validRequest("disabled-" + suffix, "hiddenTool")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("hiddenTool"));
    }

    @Test
    void intervalOutsideGuardRangeIsRefusedWithRangeNamed() {
        McpKnowledgeSyncSaveRequest tooSmall = validRequest("fast-" + suffix, "readOnlyDocs");
        ServiceException low = assertThrows(ServiceException.class, () -> syncService.save(1L,
                new McpKnowledgeSyncSaveRequest(tooSmall.projectId(), tooSmall.serverId(), tooSmall.name(),
                        tooSmall.toolName(), tooSmall.paramMapping(), 0, tooSmall.targetProjectId(),
                        tooSmall.targetPackId(), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, low.getCode());

        McpKnowledgeSyncSaveRequest tooLarge = validRequest("slow-" + suffix, "readOnlyDocs");
        ServiceException high = assertThrows(ServiceException.class, () -> syncService.save(1L,
                new McpKnowledgeSyncSaveRequest(tooLarge.projectId(), tooLarge.serverId(), tooLarge.name(),
                        tooLarge.toolName(), tooLarge.paramMapping(), 100_000, tooLarge.targetProjectId(),
                        tooLarge.targetPackId(), null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, high.getCode());
        assertTrue(high.getMessage().matches(".*\\d.*"), "message must spell out the allowed interval range");
    }

    @Test
    void unknownTargetProjectIsRefused() {
        McpKnowledgeSyncSaveRequest base = validRequest("bad-target-" + suffix, "readOnlyDocs");
        McpKnowledgeSyncSaveRequest request = new McpKnowledgeSyncSaveRequest(base.projectId(),
                base.serverId(), base.name(), base.toolName(), base.paramMapping(),
                base.syncIntervalMinutes(), 999_999_998L, 0L, null);
        ServiceException ex = assertThrows(ServiceException.class, () -> syncService.save(1L, request));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("999999998"), "message must name the invisible target id");
    }

    @Test
    void globalTargetBucketZeroIsAccepted() {
        McpKnowledgeSyncSaveRequest base = validRequest("global-target-" + suffix, "readOnlyDocs");
        McpKnowledgeSyncSaveRequest request = new McpKnowledgeSyncSaveRequest(base.projectId(),
                base.serverId(), base.name(), base.toolName(), base.paramMapping(),
                base.syncIntervalMinutes(), 0L, 0L, null);
        McpKnowledgeSyncInfo info = syncService.save(1L, request);
        createdSubscriptionIds.add(info.id());
        assertEquals(0L, info.targetProjectId(), "0 is the global ledger bucket and must be storable");
    }

    @Test
    void pauseAndResumeFlipEnabledFlag() {
        long id = saveAndTrack(validRequest("toggle-" + suffix, "readOnlyDocs"));
        syncService.pause(1L, id);
        assertFalse(syncService.detail(id).enabled(), "pause must stop the scheduler from picking the row");

        syncService.resume(1L, id);
        assertTrue(syncService.detail(id).enabled(), "resume must bring the row back into the due scan");
    }

    @Test
    void updateRevalidatesToolAgainstReadonlyWhitelist() {
        long id = saveAndTrack(validRequest("edit-" + suffix, "readOnlyDocs"));
        McpKnowledgeSyncSaveRequest base = validRequest("edit-" + suffix, "readOnlyDocs");
        McpKnowledgeSyncSaveRequest toMutating = new McpKnowledgeSyncSaveRequest(base.projectId(),
                base.serverId(), base.name(), "writeDocs", base.paramMapping(),
                base.syncIntervalMinutes(), base.targetProjectId(), base.targetPackId(), null);

        ServiceException ex = assertThrows(ServiceException.class, () -> syncService.update(1L, id, toMutating));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("writeDocs"));

        McpKnowledgeSyncSaveRequest intervalFixed = new McpKnowledgeSyncSaveRequest(base.projectId(),
                base.serverId(), base.name(), "readOnlyDocs", Map.of("query", "changelog"),
                1440, base.targetProjectId(), base.targetPackId(), null);
        McpKnowledgeSyncInfo updated = syncService.update(1L, id, intervalFixed);
        createdSubscriptionIds.add(id);
        assertEquals(1440, updated.syncIntervalMinutes());
        assertEquals(1, syncService.list(projectId, null, 1, 10).total(), "list must page the project slice");
    }

    @Test
    void deleteRemovesTheRow() {
        long id = saveAndTrack(validRequest("remove-" + suffix, "readOnlyDocs"));
        syncService.delete(1L, id);
        assertNotNull(syncService);
        Integer left = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mcp_knowledge_sync WHERE id = ?", Integer.class, id);
        assertEquals(0, left, "delete must physically remove the subscription row");
    }
}
