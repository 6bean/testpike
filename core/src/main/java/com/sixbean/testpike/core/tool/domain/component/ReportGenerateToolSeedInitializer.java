package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers the built-in report-generate tool ({@code report.generate}) of the
 * seventh domain cleared by the tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan and batch tools, the
 * dialog chain NEVER executes this tool: the schema carries
 * {@code x-write=true} so the dispatcher refuses a token-less call, and the
 * emitter journals a confirmable draft card instead — routing comes from the
 * {@code draftObjectType=REPORT_WRITE} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link CaseWriteToolSeedInitializer}
 * for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME TestReportService.generate
 * chain the report page's generate dialog uses — the range-type refusal, the
 * plan-ownership check and the aggregation insert are enforced by that chain,
 * and the owning project comes from the chat session, never from the payload.
 * Exporting (xlsx/pdf) and deleting reports stay human-only on /test-report.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class ReportGenerateToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerateToolSeedInitializer.class);

    static final String TOOL_REPORT_GENERATE = "report.generate";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_REPORT_GENERATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/test-reports/generate";

    private static final String SCHEMA_REPORT_GENERATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"rangeType\":{\"type\":\"integer\",\"enum\":[1,2],\"description\":\"报告范围：1=按单个计划 2=按时间范围（必填）\"},"
            + "\"planId\":{\"type\":\"integer\",\"description\":\"来源计划 ID（rangeType=1 必填，须属于当前项目）\"},"
            + "\"startTime\":{\"type\":\"string\",\"description\":\"时间范围起 YYYY-MM-DD（rangeType=2 必填）\"},"
            + "\"endTime\":{\"type\":\"string\",\"description\":\"时间范围止 YYYY-MM-DD（rangeType=2 必填，含当日）\"},"
            + "\"title\":{\"type\":\"string\",\"description\":\"报告标题（可空=按范围自动生成）\"}},"
            + "\"required\":[\"rangeType\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public ReportGenerateToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置报告生成写工具 {} 件（report.generate，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置报告生成写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('report.generate')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置报告生成写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
                continue;
            }
            registerPublished(intent);
            created++;
        }
        return created;
    }

    /** True when a row already carries the contract name in the system-level bucket. */
    private boolean nameSquatted(String toolName) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = ? AND project_id IS NULL"
                        + " AND (exec_spec ->> 'intent' IS NULL"
                        + " OR exec_spec ->> 'intent' <> ?)",
                Integer.class, toolName, toolName);
        return n != null && n > 0;
    }

    private void registerPublished(String intent) {
        String spec = "{\"url\":\"" + PLACEHOLDER_URL + "\",\"intent\":\"" + intent
                + "\",\"draftObjectType\":\"REPORT_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "测试报告",
                "生成测试总结报告（按计划或时间范围聚合执行结果、缺陷分布与通过率）；确认后走报告页同一生成链，计划归属校验与审计由该链完成；导出与删除报告仍在测试报告页人工操作；未选计划、时间范围缺起止日期或计划不属于当前项目时会被拒绝并提示原因",
                null,
                "[\"测试报告\", \"内置\"]",
                SCHEMA_REPORT_GENERATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
