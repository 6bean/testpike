package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-DB spectrum of the built-in rehearsal-run launch tool (AO batch
 * : the dialog can offer "start this rehearsal batch" only because a
 * published system-level ToolDefinition row exists behind the approval chain.
 * <p>Pinned faces: ① exactly one row keyed by the intent anchor
 * {@code exec_spec->>'intent' = batch.rehearsalRun} with exec_kind=HTTP —
 * re-running the seed creates nothing (idempotent on every start);
 * ② the row reached PUBLISHED through create → submit → approve with the seed
 * operator and approver kept apart — the review gate is not bypassed;
 * ③ the row is system-level (project_id NULL): the project segment travels per
 * call on the door, never baked into the tool;
 * ④ the input schema root carries {@code x-write} so ToolWriteFlag resolves
 * true — the human confirm-ticket is provably attached to this write action
 * (the silent-misfire guard the analysis family tests pinned for the read
 * side); ⑤ the spec url points at the internal rehearsal-runs door.
 * <p>Fixture hygiene: the seeded row is factory baseline and is kept; no test
 * here truncates or deletes a factory table (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class RehearsalRunToolSeedTest {

    @Autowired
    private RehearsalRunToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ToolInfo intentRow() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' = 'batch.rehearsalRun'", Long.class);
        assertEquals(1, ids.size(), "exactly one rehearsal-run tool row must exist");
        return toolService.get(ids.get(0));
    }

    @Test
    void seedRegistersTheRehearsalRunToolAsAPublishedSystemLevelRow() {
        seedInitializer.seedMissing();

        ToolInfo row = intentRow();
        assertEquals(ApprovalStatus.PUBLISHED.code(), row.status());
        assertNull(row.projectId());
        assertEquals(ToolExecKind.HTTP.code(), row.execKind().intValue());
        assertNotNull(row.execSpecJson());
        assertTrue(row.execSpecJson().contains("/internal/ai/chat/rehearsal-runs"), row.execSpecJson());
    }

    @Test
    void reRunningTheSeedCreatesNothing() {
        seedInitializer.seedMissing();
        assertEquals(0, seedInitializer.seedMissing());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE exec_spec ->> 'intent' = 'batch.rehearsalRun'",
                Integer.class).intValue());
    }

    @Test
    void theWriteFlagOnTheSchemaAttachesTheHumanConfirmTicket() {
        seedInitializer.seedMissing();

        ToolInfo row = intentRow();
        assertTrue(ToolWriteFlag.resolve(row.inputSchemaJson()),
                "the launch tool must demand a confirm ticket: " + row.inputSchemaJson());
        assertTrue(row.inputSchemaJson().contains("batchId"), row.inputSchemaJson());
    }
}
