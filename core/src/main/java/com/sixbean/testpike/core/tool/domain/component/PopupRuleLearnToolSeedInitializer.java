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
 * Registers the built-in popup-rule learning tool
 * ({@code appauto.popup-rule.learn}) of the twelfth domain cleared by the
 * tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf and reservation tools, the dialog chain
 * NEVER executes this tool: the schema carries {@code x-write=true} so the
 * dispatcher refuses a token-less call, and the emitter journals a
 * confirmable draft card instead — routing comes from the {@code
 * draftObjectType=POPUP_RULE_LEARN} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link
 * CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code AppautoPopupRuleService.learn} chain the approval view's learn
 * button uses, so the learning switch, the sensitive-frame exclusion, the
 * structure gate and the pending-approval status stay single-sourced and the
 * dialog is never a bypass of one gate. The task's owning project is read
 * from tp_exec_task and compared against the chat session BEFORE any frame
 * is collected — a model-forged task id can never reach another project's
 * watch frames. The produced rule is a pending draft: approving it into the
 * rule library stays a deliberate human click on /appauto/popup-rules.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class PopupRuleLearnToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PopupRuleLearnToolSeedInitializer.class);

    static final String TOOL_POPUP_RULE_LEARN = "appauto.popup-rule.learn";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_POPUP_RULE_LEARN);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/appauto/popup-rules/learn";

    private static final String SCHEMA_POPUP_RULE_LEARN =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{\""
            + "taskId\":{\"type\":\"integer\",\"description\":\"要学习弹窗规则的执行任务编号（必填，须属于当前项目且已完成，监看帧按其寻址；敏感帧自动排除）\"}"
            + "},\"required\":[\"taskId\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public PopupRuleLearnToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置弹窗规则学习写工具 {} 件（appauto.popup-rule.learn，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置弹窗规则学习写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('appauto.popup-rule.learn')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置弹窗规则学习写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"POPUP_RULE_LEARN\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "APP 自动化",
                "对指定已完成执行任务的非敏感监看帧学习新弹窗模式；确认后走弹窗规则审批页同一学习链，学习开关、结构闸与待批准状态照常在学习时复核；产出一条待批准规则草稿，须人工在「APP 自动化 → 弹窗规则」批准后才进入规则库，不会自动入库；任务归属取当前会话项目，跨项目任务会被拒绝并提示原因",
                null,
                "[\"APP自动化\", \"内置\"]",
                SCHEMA_POPUP_RULE_LEARN,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
