package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic tool execution surface refuses the ANALYSIS shape
 * item 5, real dev DB, no mocks).
 * <p>An ANALYSIS tool row exists so approval, the marketplace and the assembly
 * vocabulary stay single-sourced, but its numbers are produced by the internal
 * analysis door where the server injects the project segment. Falling into the
 * HTTP default branch would dial a URL for a function that has none and write a
 * trace row that pretends an analysis ran — so the dispatch has a named branch
 * that refuses in plain words, before any trace or audit row is written.
 * <p>Fixture hygiene: the tool row is created through {@link ToolService} and
 * removed by id afterwards (R37 — factory tables are never truncated here).
 */
@SpringBootTest(classes = CoreApplication.class)
class ToolExecutionAnalysisRefusalTest {

    private static final String READ_ONLY_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"chapter\":{\"type\":\"string\"}},"
                    + "\"required\":[]}";

    @Autowired
    private ToolService toolService;

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long toolId;

    @BeforeEach
    void createAnalysisTool() {
        ToolInfo draft = toolService.create(1L, new ToolCreateInput(
                null, "分析·覆盖摘要（执行面拒止夹具）", 2, "分析",
                "夹具：只读分析形态工具行，用于验证通用执行面拒绝执行",
                null, "[\"分析\"]", READ_ONLY_SCHEMA, null,
                ToolExecKind.ANALYSIS.code(), "{\"function\":\"coverageSummary\"}", 30000, null));
        var submitted = toolService.submit(1L, draft.id(), draft.version());
        toolService.approve(2L, submitted.id(), submitted.version());
        toolId = toolService.get(draft.id()).id();
    }

    @AfterEach
    void removeFixtureTool() {
        if (toolId != null) {
            jdbcTemplate.update("DELETE FROM tp_tool WHERE id = ?", toolId);
            toolId = null;
        }
    }

    private List<String> refusalSentences() {
        String aiChain = null;
        try {
            toolExecutionService.invoke(1L, null, toolId, "{}");
        } catch (ServiceException e) {
            aiChain = e.getMessage();
        }
        String testRun = null;
        try {
            toolExecutionService.testRun(1L, toolId, "{}");
        } catch (ServiceException e) {
            testRun = e.getMessage();
        }
        return List.of(String.valueOf(aiChain), String.valueOf(testRun));
    }

    @Test
    void bothExecutionEntrancesRefuseTheAnalysisShapeByName() {
        List<String> refusals = refusalSentences();

        for (String sentence : refusals) {
            assertTrue(sentence.contains("分析"), sentence);
            assertTrue(sentence.contains("对话"), sentence);
        }
    }

    @Test
    void refusalWritesNoInvocationTrace() {
        refusalSentences();

        Integer traces = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE tool_id = ?",
                Integer.class, toolId);
        assertEquals(0, traces == null ? -1 : traces,
                "a guarded refusal must never leave a trace row that pretends an analysis ran");
    }

    @Test
    void refusalIsNotAFiveHundredOnTheHttpDefaultBranch() {
        // Regression pin: without the named branch the ANALYSIS code falls
        // through to executeHttp and fails on the missing url instead.
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> toolExecutionService.testRun(1L, toolId, "{}"));

        assertTrue(thrown.getMessage().contains("覆盖摘要")
                        || thrown.getMessage().contains("对话"),
                thrown.getMessage());
        assertTrue(!thrown.getMessage().contains("url"), thrown.getMessage());
    }
}
