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
 * Registers the built-in review-decision tool ({@code case.review}) of the
 * fourth domain cleared by the tool-surface clearing batch .
 * <p>Like the tag, prompt-template and skill tools, the dialog chain NEVER
 * executes this tool: the schema carries {@code x-write=true} so the
 * dispatcher refuses a token-less call, and the emitter journals a
 * confirmable draft card instead — routing comes from the
 * {@code draftObjectType=CASE_REVIEW} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link CaseWriteToolSeedInitializer}
 * for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME CaseService.review chain the
 * review workbench uses — state-machine legality, feedback-type requirements
 * and the optimistic version anchor are enforced by that chain, so the dialog
 * door can never smuggle an illegal transition or stale write.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class CaseReviewToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CaseReviewToolSeedInitializer.class);

    static final String TOOL_CASE_REVIEW = "case.review";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_CASE_REVIEW);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/cases";

    private static final String SCHEMA_REVIEW =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"caseId\":{\"type\":\"integer\",\"description\":\"被评审用例 ID\"},"
            + "\"action\":{\"type\":\"string\",\"enum\":[\"pass\",\"need_change\",\"reject\"],"
            + "\"description\":\"评审结论：通过 / 需修改 / 拒绝（用例须处于待评审态）\"},"
            + "\"remark\":{\"type\":\"string\",\"description\":\"评审意见（需修改/拒绝时必填，不超过 500 字）\"},"
            + "\"expectedVersion\":{\"type\":\"integer\",\"description\":\"草案所基于的用例版本号（乐观锁，提案时请先读取用例详情）\"},"
            + "\"feedbackTypes\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":"
            + "[\"step_inaccurate\",\"expected_wrong\",\"selector_issue\",\"logic_missing\",\"wording\",\"locate_error\"]},"
            + "\"description\":\"问题类型（需修改/拒绝时至少选一项）\"}},"
            + "\"required\":[\"caseId\",\"action\",\"expectedVersion\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public CaseReviewToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置评审决议写工具 {} 件（case.review，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置评审决议写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('case.review')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置评审决议写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"CASE_REVIEW\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "用例评审",
                "对处于待评审状态的用例提出评审决议（通过/需修改/拒绝）与意见；确认后走评审工作台同一状态机链，版本冲突或状态不合法时会被拒绝并提示原因",
                null,
                "[\"用例评审\", \"内置\"]",
                SCHEMA_REVIEW,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
