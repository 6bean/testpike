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
 * Registers the built-in perf-run launch tool ({@code perf.run.launch}) of
 * the tenth domain cleared by the tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test and api-inventory tools, the dialog chain NEVER executes this tool:
 * the schema carries {@code x-write=true} so the dispatcher refuses a
 * token-less call, and the emitter journals a confirmable draft card instead —
 * routing comes from the {@code draftObjectType=PERF_LAUNCH} token in the exec
 * spec (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code PerfRunService.launch(projectId, scenarioId, envId, operatorId)}
 * transaction the workbench's launch dialog uses, so the production-change
 * safety gate, the duration ceiling, the canary pre-flight and the
 * environment execution lock stay single-sourced and the dialog is never a
 * bypass of one gate. The owning project is read from the chat session, never
 * from the payload — a model-forged scenario id can only ever be checked
 * against (and pressed inside) the session's own project. The card states
 * the cost (one occupied environment) before the human confirms.
 * Designing or editing the scenario itself stays a human flow on /perf:
 * the wizard is a multi-step design session, not a dialog payload.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class PerfLaunchToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PerfLaunchToolSeedInitializer.class);

    static final String TOOL_PERF_LAUNCH = "perf.run.launch";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_PERF_LAUNCH);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/perf/scenarios/0/runs";

    private static final String SCHEMA_PERF_LAUNCH =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{\""
            + "scenarioId\":{\"type\":\"integer\",\"description\":\"压测场景编号（必填，须属于当前会话项目，由发起链复核）\"},"
            + "\"envId\":{\"type\":\"integer\",\"description\":\"本次发起的目标环境编号（可选；缺省用场景绑定的环境；指定时须属于当前项目，环境不存在或不匹配会被拒绝并提示原因）\"}}"
            + ",\"required\":[\"scenarioId\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public PerfLaunchToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置压测发起写工具 {} 件（perf.run.launch，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置压测发起写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the launch tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('perf.run.launch')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置压测发起写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"PERF_LAUNCH\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "性能压测",
                "按场景发起一次压测运行；确认后走压测工作台同一发起链，生产变更安全闸、时长上限、金丝雀预检与环境执行锁照常在发起时判定，不通过会被拒绝并提示原因；草案卡会注明本次运行占用一个测试环境；场景归属取当前会话项目；场景编号缺失或环境不匹配时会被拒绝并提示原因；场景的新建与编辑仍在压测工作台人工操作",
                null,
                "[\"性能压测\", \"内置\"]",
                SCHEMA_PERF_LAUNCH,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
