package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed spectrum of the built-in read-only analysis tools item 6,
 * real dev DB, no mocks).
 * <p>Pinned faces: ① the two registry analysis functions are registered as
 * ToolDefinition rows through the approval chain (create → submit → approve,
 * seed operator and approver kept apart) so the review gate is not bypassed and
 * the rows stay manageable in the tool page like any hand-registered tool;
 * ② idempotency anchored on {@code exec_spec->>'function'} with exec_kind=5 —
 * re-running the seed creates nothing and foreign rows are never touched;
 * ③ the rows carry no {@code x-write}: the analysis channel runs them without
 * a confirm ticket (read-only contract, AI-features-on-by-default rule);
 * ④ they are system-level (project_id NULL) — the project segment travels per
 * call on the internal door, never on the row;
 * ⑤ an ANALYSIS row without a function name is refused at registration time,
 * so a half-configured analysis tool cannot reach the published set.
 * <p>Fixture hygiene: the seeded rows are factory baseline and are kept; no
 * test here truncates or deletes a factory table (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class AnalysisToolSeedTest {

    @Autowired
    private AnalysisToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Set<String> registryAnalysisNames() {
        return Arrays.stream(GenFunction.values())
                .filter(GenFunction::isAnalysis)
                .map(GenFunction::functionName)
                .collect(Collectors.toSet());
    }

    private List<ToolInfo> seededRows() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_tool WHERE exec_kind = 5 AND project_id IS NULL"
                        + " AND exec_spec ->> 'function' IN ("
                        + "'" + String.join("','", registryAnalysisNames()) + "')",
                Long.class);
        return ids.stream().map(id -> toolService.get(id)).collect(Collectors.toList());
    }

    @Test
    void seedRegistersEveryRegistryAnalysisFunctionAsAPublishedSystemRow() {
        seedInitializer.seedMissing();

        List<ToolInfo> rows = seededRows();
        Set<String> functions = rows.stream()
                .map(r -> r.execSpecJson())
                .collect(Collectors.toSet());

        assertEquals(registryAnalysisNames().size(), rows.size(),
                "one row per registry analysis function");
        for (ToolInfo row : rows) {
            assertNotNull(row.execSpecJson(), row.name());
            assertTrue(row.execSpecJson().contains("\"function\""), row.execSpecJson());
            assertTrue(com.sixbean.testpike.common.approval.ApprovalStateMachine
                    .isPublished(row.status()),
                    row.name() + " must sit in the published state (approval chain not bypassed)");
            assertEquals(ToolExecKind.ANALYSIS.code(), row.execKind().intValue(), row.name());
            // Function names, not descriptions, are the anchor set face.
            assertTrue(registryAnalysisNames().stream()
                    .anyMatch(f -> row.execSpecJson().contains(f)), row.execSpecJson());
        }
        assertFalse(functions.isEmpty());
    }

    @Test
    void seedIsIdempotentOnTheFunctionAnchor() {
        seedInitializer.seedMissing();
        int before = seededRows().size();

        int createdAgain = seedInitializer.seedMissing();

        assertEquals(0, createdAgain, "second run must create nothing");
        assertEquals(before, seededRows().size());
    }

    @Test
    void analysisRowsAreReadOnlySoTheDialogNeedsNoConfirmTicket() {
        seedInitializer.seedMissing();

        for (ToolInfo row : seededRows()) {
            assertFalse(ToolWriteFlag.resolve(row.inputSchemaJson()),
                    row.name() + " is a read tool: x-write must stay absent");
        }
    }

    /**
     * The dialog can only pass a batch id to the risk-report tool when the
     * function-calling schema declares it: the live run proved the model had
     * nowhere to put batchId (the row carried the density vocabulary), so the
     * refusal was structural, not a prompt problem. Pinned face: the seeded
     * row's schema declares batchId as a required property.
     */
    @Test
    void rehearsalRiskReportSchemaDeclaresTheBatchIdTheDialogMustPass() throws Exception {
        seedInitializer.seedMissing();

        ToolInfo row = seededRows().stream()
                .filter(r -> r.execSpecJson().contains("rehearsalRiskReport"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "rehearsalRiskReport has no seeded system row to check"));

        var schema = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(row.inputSchemaJson());
        assertTrue(schema.path("properties").has("batchId"),
                row.name() + " schema must carry batchId: " + row.inputSchemaJson());
        var required = new java.util.ArrayList<String>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        assertTrue(required.contains("batchId"),
                row.name() + " must require batchId (the composer refuses without it): "
                        + row.inputSchemaJson());
    }

    @Test
    void analysisRowWithoutFunctionNameIsRefusedAtRegistration() {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> toolService.create(1L, new ToolCreateInput(
                        null, "分析·缺函数名夹具", 2, "分析",
                        "夹具：只读分析形态但 exec_spec 无 function",
                        null, "[\"分析\"]",
                        "{\"type\":\"object\",\"properties\":{},\"required\":[]}", null,
                        ToolExecKind.ANALYSIS.code(), "{}", 30000, null)));

        assertTrue(refused.getMessage().contains("function"), refused.getMessage());
        Integer leftovers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = ?", Integer.class, "分析·缺函数名夹具");
        assertEquals(0, leftovers == null ? -1 : leftovers, "a refused create writes no row");
    }
}
