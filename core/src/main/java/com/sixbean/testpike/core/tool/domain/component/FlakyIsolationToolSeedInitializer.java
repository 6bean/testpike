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
 * Registers the built-in flaky-quarantine tool
 * ({@code case.flaky.isolate}) of the closed-loop operations batch
 * .
 * <p>Like the tag, case, review, plan, batch, report, api-test,
 * api-inventory, perf, reservation, popup-rule, mock-space, mock-stub,
 * ai-generation, defect, data-factory and exploration tools, the dialog
 * chain NEVER executes this tool: the schema carries {@code x-write=true}
 * so the dispatcher refuses a token-less call, and the emitter journals a
 * confirmable draft card instead — routing comes from the {@code
 * draftObjectType=FLAKY_ISOLATION} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link
 * CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code CaseService.setFlaky} chain the workbench's one-click quarantine
 * uses, so the audit row, the idempotent reason refresh and the version
 * bump stay single-sourced and the dialog is never a parallel write path.
 * The door is one-directional — a card can only MARK a case flaky;
 * releasing an isolated case stays the cases page's human face. The
 * landing strategy additionally refuses a foreign-project case (H1) and a
 * stale version echo before anything moves.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a
 * foreign row is skipped with a warning, never crashed.
 */
@Component
public class FlakyIsolationToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlakyIsolationToolSeedInitializer.class);

    static final String TOOL_CASE_FLAKY_ISOLATE = "case.flaky.isolate";
    /** Idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_CASE_FLAKY_ISOLATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/cases/flaky-isolation";

    private static final String SCHEMA_FLAKY_ISOLATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{\""
            + "caseId\":{\"type\":\"integer\",\"description\":\"被隔离的用例编号（必填，取失败分析观察到的用例）\"},"
            + "\"baseVersion\":{\"type\":\"integer\",\"description\":\"分析时看到的用例版本号（必填，确认时复核：期间用例被他人修改会拒绝并提示刷新）\"},"
            + "\"reason\":{\"type\":\"string\",\"description\":\"隔离依据说明（必填，写明翻转形态与证据，草案卡据此供人工判断）\"}"
            + "},\"required\":[\"caseId\",\"baseVersion\",\"reason\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public FlakyIsolationToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置失败隔离工具 {} 件（case.flaky.isolate，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置失败隔离工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('case.flaky.isolate')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置失败隔离工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"FLAKY_ISOLATION\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "用例库",
                "把失败分析判定为环境抖动的用例标记为流畅隔离（确认后走用例工作台一键隔离同一链——审计行与"
                        + "隔离原因幂等刷新由该链携带，用例不属于会话项目或期间已被修改会被拒绝并提示原因；"
                        + "本通道只支持隔离不支持解除，解除隔离仍在用例库页人工操作）",
                null,
                "[\"失败隔离\", \"内置\"]",
                SCHEMA_FLAKY_ISOLATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
