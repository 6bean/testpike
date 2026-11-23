package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiBatchEstimate;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInput;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewItem;
import com.sixbean.testpike.core.ai.infrastructure.remote.client.AiServiceScenarioPreviewClient;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Stage0 scenario preview ledger : the enumeration outcome of
 * one requirement line is persisted for the human circle-selection step, a
 * switch-off result writes nothing (legacy flow, zero cost rows), batch reads
 * come back in submission order, and the batch cost estimate multiplies the
 * legacy per-line token base by the persisted scenario count and the live
 * deliberation-round count (ruling section 10 item 1).
 * <p>The ai-service leg is stubbed at the client interface (L2 infra isolation
 * only, per strict-testing rules); the live chain is verified by the L3
 * probe against the real /internal/gen/scenario-preview endpoint.
 */
@SpringBootTest(classes = CoreApplication.class)
class ScenarioPreviewServiceTest {

    /** Marker batch id so this class only ever deletes rows it created (R37). */
    private static final long MARKER_BATCH = 987654321L;

    @Autowired
    private ScenarioPreviewService scenarioPreviewService;

    @Autowired
    private AiBatchService aiBatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiServiceScenarioPreviewClient previewClient;

    @AfterEach
    void cleanOwnRows() {
        jdbcTemplate.update("DELETE FROM tp_ai_scenario_preview WHERE batch_id = ?", MARKER_BATCH);
    }

    private static ScenarioPreviewInput input(String text, int seq, int priority) {
        return new ScenarioPreviewInput(MARKER_BATCH, seq, 4242L, 5L, text, priority);
    }

    @Test
    void create_persistedPreviewReadsBackWithScenarioJsonb() {
        when(previewClient.enumerate(anyLong(), any(), anyInt(), any(), anyLong()))
                .thenReturn(new ScenarioPreviewResult(true,
                        List.of(new ScenarioPreviewItem("counter-example", "错误密码锁定", "连续输错5次锁定", null),
                                new ScenarioPreviewItem("boundary", "第五次输入", "连续输错5次锁定", "边界值")),
                        List.of(), 2, 4, true));

        long id = scenarioPreviewService.create(input("用户连续输错5次锁定账号", 0, 0));
        assertTrue(id > 0);

        var rows = scenarioPreviewService.listByBatch(MARKER_BATCH);
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals(id, row.id());
        assertEquals("用户连续输错5次锁定账号", row.requirementText());
        assertEquals(2, row.scenarios().size());
        assertEquals("counter-example", row.scenarios().get(0).kind());
        assertEquals("连续输错5次锁定", row.scenarios().get(1).anchor());
        assertEquals(2, row.roundsUsed());
        assertEquals(4, row.modelCalls());
        assertTrue(row.converged());
    }

    @Test
    void create_switchOffWritesNoRowAndKeepsLegacyShape() {
        when(previewClient.enumerate(anyLong(), any(), anyInt(), any(), anyLong()))
                .thenReturn(ScenarioPreviewResult.disabled());

        long id = scenarioPreviewService.create(input("关闭态需求行", 0, 1));

        assertEquals(0L, id);
        assertEquals(0, scenarioPreviewService.listByBatch(MARKER_BATCH).size());
    }

    @Test
    void listByBatch_returnsRowsInSubmissionSeqOrder() {
        when(previewClient.enumerate(anyLong(), any(), anyInt(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    int priority = invocation.getArgument(2);
                    return new ScenarioPreviewResult(true,
                            List.of(new ScenarioPreviewItem("main-flow", "主流程" + priority, "原文", null)),
                            List.of(), 1, 2, true);
                });
        scenarioPreviewService.create(input("第二行", 1, 1));
        scenarioPreviewService.create(input("第一行", 0, 0));

        var rows = scenarioPreviewService.listByBatch(MARKER_BATCH);
        assertEquals(List.of("第一行", "第二行"),
                rows.stream().map(r -> r.requirementText()).toList());
    }

    @Test
    void estimate_withPreviewRows_multipliesScenarioCountAndDeliberationRounds() {
        insertPreviewRow(0, "[{\"kind\":\"main-flow\",\"title\":\"a\",\"anchor\":\"x\"},"
                + "{\"kind\":\"boundary\",\"title\":\"b\",\"anchor\":\"x\"},"
                + "{\"kind\":\"counter-example\",\"title\":\"c\",\"anchor\":\"x\"}]", 2, 4);
        int rounds = currentRounds();

        AiBatchEstimate estimate = aiBatchService.estimate(4242L, List.of("A"), MARKER_BATCH);

        // Base per line = round(800 + len*2.0) = 802; multiplied by the three
        // persisted scenarios and the live deliberation round count.
        assertEquals(1, estimate.count());
        assertEquals(802L * 3 * rounds, estimate.estimatedTokens());
        assertEquals(3, estimate.scenarioCount());
        assertEquals(rounds, estimate.deliberationRounds());
    }

    @Test
    void estimate_legacyAndUnknownBatch_keepZeroDriftShape() {
        long legacy = aiBatchService.estimate(4242L, List.of("A")).estimatedTokens();
        assertEquals(802L, legacy);
        assertNull(aiBatchService.estimate(4242L, List.of("A")).scenarioCount());

        AiBatchEstimate unknownBatch = aiBatchService.estimate(4242L, List.of("A"), 123456789L);
        assertEquals(legacy, unknownBatch.estimatedTokens());
        assertNull(unknownBatch.scenarioCount());
        assertNotNull(unknownBatch);
    }

    @Test
    void estimate_rowsWithoutAScenarioList_doNotFabricateMultiplier() {
        // Residual preview rows with an empty list (non-converged rejected lines)
        // count as one unit, never as a zero-cost pass.
        insertPreviewRow(0, "[]", 2, 0);
        AiBatchEstimate estimate = aiBatchService.estimate(4242L, List.of("A"), MARKER_BATCH);
        assertEquals(802L * Math.max(1, currentRounds()), estimate.estimatedTokens());
        assertEquals(0, estimate.scenarioCount());
    }

    private void insertPreviewRow(int seq, String scenariosJson, int rounds, int modelCalls) {
        jdbcTemplate.update("""
                INSERT INTO tp_ai_scenario_preview (batch_id, seq, project_id, operator_id,
                    requirement_text, priority, scenarios, rounds_used, model_calls)
                VALUES (?, ?, 4242, 5, ?, 0, ?::jsonb, ?, ?)
                """, MARKER_BATCH, seq, "A", scenariosJson, rounds, modelCalls);
    }

    /** Live governance row value (K8 hot update, never cached across tests). */
    private int currentRounds() {
        Integer raw = jdbcTemplate.queryForObject(
                "SELECT config_value::int FROM tp_sys_config WHERE config_key = 'ai.kernel.deliberation-rounds'",
                Integer.class);
        return raw == null ? 2 : Math.min(3, Math.max(1, raw));
    }
}
