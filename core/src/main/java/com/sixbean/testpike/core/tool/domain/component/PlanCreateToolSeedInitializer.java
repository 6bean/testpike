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
 * Registers the built-in plan-create tool ({@code plan.create}) of the fifth
 * domain cleared by the tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill and review tools, the dialog chain
 * NEVER executes this tool: the schema carries {@code x-write=true} so the
 * dispatcher refuses a token-less call, and the emitter journals a
 * confirmable draft card instead — routing comes from the
 * {@code draftObjectType=PLAN_WRITE} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link CaseWriteToolSeedInitializer}
 * for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME PlanService.create chain the
 * plan wizard uses — env validation, the at-least-one-scope-dimension refusal,
 * the automatic case pull and audit are enforced by that chain, and the row
 * lands in draft state: publishing (which locks the scope) stays a human
 * click on /plans, never a dialog card.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class PlanCreateToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlanCreateToolSeedInitializer.class);

    static final String TOOL_PLAN_CREATE = "plan.create";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_PLAN_CREATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/plans";

    private static final String SCHEMA_PLAN_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"计划名称（必填，不超过 100 字）\"},"
            + "\"targetVersion\":{\"type\":\"string\",\"description\":\"目标版本（执行归因维度，可空）\"},"
            + "\"envIds\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},"
            + "\"description\":\"执行环境 ID 集合（必填非空，生成批次按环境分批；须属于当前项目）\"},"
            + "\"caseScope\":{\"type\":\"object\",\"description\":\"用例范围规则（至少限定一个维度，防止误拉全量用例）\",\"properties\":{"
            + "\"phases\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"enum\":[1,2,3,4]},"
            + "\"description\":\"阶段：1冒烟 2功能 3回归 4发布验证\"},"
            + "\"priorities\":{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"enum\":[0,1,2]},"
            + "\"description\":\"优先级：0=P0 1=P1 2=P2\"},"
            + "\"keyword\":{\"type\":\"string\",\"description\":\"模块/关键词（匹配标题/前置/步骤文本）\"},"
            + "\"tagIds\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"description\":\"标签 ID 列表\"}}},"
            + "\"entryCriteria\":{\"type\":\"object\",\"properties\":{\"requireSmokePass\":"
            + "{\"type\":\"boolean\",\"description\":\"准入要求冒烟全部通过（默认 true）\"}}},"
            + "\"exitCriteria\":{\"type\":\"object\",\"properties\":{"
            + "\"minPassRate\":{\"type\":\"number\",\"description\":\"准出通过率阈值 0~1（默认 0.95）\"},"
            + "\"noOpenDefects\":{\"type\":\"boolean\",\"description\":\"准出要求无未关闭缺陷（默认 true）\"}}},"
            + "\"planStart\":{\"type\":\"string\",\"description\":\"排期起 YYYY-MM-DD（可空）\"},"
            + "\"planEnd\":{\"type\":\"string\",\"description\":\"排期止 YYYY-MM-DD（可空）\"},"
            + "\"lockMode\":{\"type\":\"integer\",\"enum\":[1,2],\"description\":\"1=宽松（默认） 2=严格\"}},"
            + "\"required\":[\"name\",\"envIds\",\"caseScope\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public PlanCreateToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置计划创建写工具 {} 件（plan.create，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置计划创建写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('plan.create')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置计划创建写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"PLAN_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "测试计划",
                "创建测试执行计划（按范围规则自动拉入用例）；确认后走计划向导同一创建链，落为草稿态，发布与分配执行人仍在计划页人工操作；环境不属于当前项目或范围规则未限定维度时会被拒绝并提示原因",
                null,
                "[\"测试计划\", \"内置\"]",
                SCHEMA_PLAN_CREATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
