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
 * Registers the built-in batch-create tool ({@code batch.create}) of the sixth
 * domain cleared by the tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review and plan tools, the dialog
 * chain NEVER executes this tool: the schema carries {@code x-write=true} so
 * the dispatcher refuses a token-less call, and the emitter journals a
 * confirmable draft card instead — routing comes from the
 * {@code draftObjectType=BATCH_WRITE} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link CaseWriteToolSeedInitializer}
 * for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME BatchService.create chain the
 * batches-page form uses — case ownership, the at-least-one-case refusal and
 * the end-environment matching are enforced by that chain, and the row lands
 * as a draft batch: starting it (precheck, snapshot, task queue) stays a
 * human click on /batches, never a dialog card. The reuse-a-recent-batch and
 * template-schedule entries on the same page stay human-only (their payload
 * needs a live pick from the operator's own history, which a dialog proposal
 * cannot anchor honestly).
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class BatchCreateToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchCreateToolSeedInitializer.class);

    static final String TOOL_BATCH_CREATE = "batch.create";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_BATCH_CREATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/batches";

    private static final String SCHEMA_BATCH_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"批次名称（必填，不超过 100 字）\"},"
            + "\"caseIds\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},"
            + "\"description\":\"勾选的用例 ID 集合（必填非空，须全部属于当前项目且未删除）\"},"
            + "\"envId\":{\"type\":\"integer\",\"description\":\"执行环境 ID（可空=启动前在批次页再选；非空须属于当前项目）\"},"
            + "\"browser\":{\"type\":\"string\",\"description\":\"浏览器内核（可空=缺省 chromium；超出环境矩阵会被拒绝）\"},"
            + "\"notifyFeishu\":{\"type\":\"integer\",\"enum\":[0,1],\"description\":\"批级飞书群通知：0=仅站内（默认） 1=开\"},"
            + "\"poolTagId\":{\"type\":\"integer\",\"description\":\"资源池标签 ID（可空=不选池，全池可领）\"},"
            + "\"cleanAppData\":{\"type\":\"integer\",\"enum\":[0,1],\"description\":\"跑批后自动清机：0=关（默认） 1=开\"}},"
            + "\"required\":[\"name\",\"caseIds\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public BatchCreateToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置批次创建写工具 {} 件（batch.create，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置批次创建写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('batch.create')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置批次创建写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"BATCH_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "执行批次",
                "创建执行批次（按勾选用例落为草稿批）；确认后走批次页同一创建链，用例归属、端-环境匹配与审计由该链校验，启动批次仍在批次页人工操作；未勾选用例、用例不属于当前项目或端与环境不匹配时会被拒绝并提示原因",
                null,
                "[\"执行批次\", \"内置\"]",
                SCHEMA_BATCH_CREATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
