package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.GenkitToolSeedInitializer;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class GenkitToolSeedTest {

 
    private static final List<String> TOP10 = List.of(
            "idCard", "bankCard", "uscc", "phone", "email",
            "md5", "sha256", "base64Encode", "dateTime", "jsonFormat");

    @Autowired
    private GenkitToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 种子注册Top10_走审批链published_GENKIT形态() {
        // R37 fixture scoping: the Top10 rows are part of the shipped tp_tool
        // baseline, so the run removes them through the FK-aware sweep and
        // lets seedMissing() re-create them via the real approval chain —
        // the rows come back (baseline count restored), nothing is truncated.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate, genkitIds());
        int created = seedInitializer.seedMissing();
        assertEquals(10, created);

        for (String fn : TOP10) {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_tool WHERE exec_spec ->> 'function' = ?", Long.class, fn);
            assertNotNull(id, "Top10 函数 " + fn + " 必须已注册为工具");
            ToolInfo tool = toolService.get(id);
            assertEquals(ToolExecKind.GENKIT.code(), tool.execKind(), fn + " 形态=GENKIT");
            assertEquals(3, tool.status(), fn + " 必须走完审批链=发布态");
            assertTrue(tool.name().startsWith("造数"), "内置造数工具统一「造数」前缀: " + tool.name());
            assertTrue(tool.inputSchemaJson() == null || !tool.inputSchemaJson().contains("x-write"),
                    fn + " 造数函数为只读工具");
        }
        // Audit = exactly ten "approve" rows written for the tools re-created
        // by this run (proof the approval chain is never bypassed). Scoped by
        // object_id so audit rows owned by other batches in the shared dev DB
        // cannot interfere. object_id is stored as text (String.valueOf(id)),
        // so the numeric tool ids must be cast before the IN comparison.
        Long approveAudit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'approve'"
                        + " AND object_type = 'tool' AND object_id IN"
                        + " (SELECT id::text FROM tp_tool WHERE exec_kind = 4)", Long.class);
        assertEquals(10, approveAudit);
    }

    @Test
    void 二次执行幂等_零新增_半缺清单补建() {
        seedInitializer.seedMissing();
        assertEquals(0, seedInitializer.seedMissing(), "清单齐备=二次执行零新增");

        // Delete two rows (simulating a manual takedown gap); re-seeding must
        // fill exactly the gap. Deletion goes through the FK-aware sweep
        // (version/binding child rows first) and the rows come back published
        // via the product channel.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_tool WHERE exec_spec ->> 'function' IN ('md5','phone')",
                Long.class));
        assertEquals(2, seedInitializer.seedMissing(), "缺哪个补哪个（函数名锚幂等非表空锚）");
        assertEquals(10, seedInitializer.seedMissing() == 0 ? 10 : -1);
    }

    /** Tool approval events for the run's genkit rows (accumulation guard). */
    @AfterEach
    void sweepApprovalNotifications() {
        SharedDbFixtureSweep.toolApprovalNotifications(jdbcTemplate, genkitIds());
    }

    /** Row ids of exec_kind=GENKIT(4) tools — the Top10 seed anchors, read directly instead of via function-name lookup. */
    private java.util.List<Long> genkitIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_tool WHERE exec_kind = 4", Long.class);
    }
}
