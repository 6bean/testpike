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
 * Registers the built-in AI case-generation batch submit tool
 * ({@code aiGen.batch.submit}) of the fifteenth domain cleared by the
 * tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf, reservation, popup-rule and mock tools, the
 * dialog chain NEVER executes this tool: the schema carries
 * {@code x-write=true} so the dispatcher refuses a token-less call, and the
 * emitter journals a confirmable draft card instead — routing comes from the
 * {@code draftObjectType=AIGEN_BATCH_WRITE} token in the exec spec
 * (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see {@link
 * CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code AiBatchService.createBatch} + per-item MQ publish chain the
 * generation page's batch dialog uses, so the requirement list validator
 * (non-empty lines, per-line length, the hot-updatable {@code
 * ai.batch.max-count} ceiling) stays single-sourced and the dialog is never
 * a bypass of one gate. The owning project is read from the chat session
 * (the input record has no project field the model can steer) — a forged
 * call cannot queue generation in another project's lane. Submitting only
 * QUEUES asynchronous generation: produced cases still wait for the human
 * review loop, the dialog never auto-approves a case. The single-item
 * requirement-extraction submit and the cost-estimate dialog stay page
 * actions on /ai/generate.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class AiGenBatchSubmitToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiGenBatchSubmitToolSeedInitializer.class);

    static final String TOOL_AIGEN_BATCH_SUBMIT = "aiGen.batch.submit";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_AIGEN_BATCH_SUBMIT);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/ai-batches";

    private static final String SCHEMA_AIGEN_BATCH_SUBMIT =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"requirements\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
            + "\"description\":\"待生成用例的需求条目列表（必填，每条为非空文本；空条目与超长条目会被提交链拒绝并提示原因，单次条数超上限时会被拒绝并要求分批提交）\"}"
            + "},\"required\":[\"requirements\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public AiGenBatchSubmitToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置 AI 生成批次提交写工具 {} 件（aiGen.batch.submit，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置 AI 生成批次提交写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('aiGen.batch.submit')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置 AI 生成批次提交写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"AIGEN_BATCH_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "用例生成",
                "在当前项目提交一批需求进行 AI 批量生成用例；确认后走 AI 用例生成页同一批量提交链——需求条目非空、单条长度与单次条数上限照常在提交时复核，不通过会被拒绝并提示原因；提交仅是排队异步生成，产出的用例仍须人工在用例评审页逐条通过，不会自动生效；归属项目取当前会话项目，载荷无法指向其他项目；单条需求的成本预估与需求点提取仍在生成页人工操作",
                null,
                "[\"用例生成\", \"内置\"]",
                SCHEMA_AIGEN_BATCH_SUBMIT,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
