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
 * Registers the three built-in case-write tools ({@code case.create},
 * {@code case.update} and {@code case.rollback}) that make the dialog's
 * write intent visible to function calling (AO batch stage 2, registry
 * side; the rollback entry joins in AM batch .
 * <p>The dialog chain NEVER executes these tools: both schemas carry
 * {@code x-write=true}, so the dispatcher refuses a token-less call and
 * ChatRunOrchestrator turns the call into a confirmable draft card instead —
 * landing goes through the shared confirm door on the core side. The HTTP
 * url below is a form-validity placeholder (exec_kind=HTTP requires one); it
 * is unreachable in practice because both the dialog chain and the execution
 * chain gate on x-write before any transport happens. Do not read it as a
 * bypass route.
 * <p>The tool NAME is a routing contract in two places at once — the FC
 * assembly indexes plan items by name, and the emitter matches calls by the
 * literal "case.create"/"case.update" — so these rows must keep the exact
 * literals; renaming breaks the draft-card chain.
 * <p>Approval chain is not bypassed: create → submit → approve all run
 * through ToolService (seed operator/approver ids 1/2, same table and state
 * as human-registered tools).
 * <p>Idempotency anchor = exec_spec intent, not the name and not "table is
 * empty": a missing entry is re-seeded on the next start while foreign rows
 * stay untouched. A human-made tool squatting a contract name (without the
 * intent anchor) is skipped with a warning instead of crashing the start on
 * the unique-name bucket.
 */
@Component
public class CaseWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CaseWriteToolSeedInitializer.class);

    static final String TOOL_CASE_CREATE = "case.create";
    static final String TOOL_CASE_UPDATE = "case.update";
    /**
 * Version-history write : "roll this case back to
 * vN" / "undo the last accept" become a confirmable card that replays the
 * case through the shared CaseService.rollback chain (the core-side
 * CaseRollbackDraftStrategy owns the landing rules). Its registry token
 * is CASE_ROLLBACK, not CASE_WRITE — the emitter routes by the token and
 * the two kinds land on different strategies.
 */
    static final String TOOL_CASE_ROLLBACK = "case.rollback";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_CASE_CREATE, TOOL_CASE_UPDATE, TOOL_CASE_ROLLBACK);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed (see class note). */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/cases";

    /** Shared field domain of both tools (mirrors CaseWriteDraftStrategy's landing fields). */
    private static final String CASE_FIELDS =
            "\"precondition\":{\"type\":\"string\",\"description\":\"前置条件\"},"
            + "\"expectedResult\":{\"type\":\"string\",\"description\":\"预期结果\"},"
            + "\"priority\":{\"type\":\"integer\",\"description\":\"优先级\"},"
            + "\"confidence\":{\"type\":\"integer\",\"description\":\"置信度\"},"
            + "\"endType\":{\"type\":\"string\",\"description\":\"端类型（web/mobile/pos/multi）\"},"
            + "\"phase\":{\"type\":\"integer\",\"description\":\"阶段\"},"
            + "\"steps\":{\"type\":\"array\",\"description\":\"步骤列表\","
            + "\"items\":{\"type\":\"object\",\"properties\":{"
            + "\"stepNo\":{\"type\":\"integer\",\"description\":\"步骤序号\"},"
            + "\"description\":{\"type\":\"string\",\"description\":\"步骤描述\"},"
            + "\"isAssert\":{\"type\":\"integer\",\"description\":\"是否断言（1=断言 0=操作）\"}}}}";

    private static final String SCHEMA_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"title\":{\"type\":\"string\",\"description\":\"用例标题\"}," + CASE_FIELDS + "},"
            + "\"required\":[\"title\"]}";

    private static final String SCHEMA_UPDATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"caseId\":{\"type\":\"integer\",\"description\":\"目标用例 id\"},"
            + "\"baseVersion\":{\"type\":\"integer\",\"description\":\"编辑基于的用例版本（乐观锁锚）\"},"
            + "\"title\":{\"type\":\"string\",\"description\":\"用例标题\"}," + CASE_FIELDS + "},"
            + "\"required\":[\"caseId\",\"baseVersion\"]}";

    /**
     * Rollback contract: the human reads a target version on the card, the
     * shared door re-checks the optimistic anchor at confirm time, so the
     * model must echo all three numbers (missing any = the emitter honestly
     * drops the card rather than baiting a refusal).
     */
    private static final String SCHEMA_ROLLBACK =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"caseId\":{\"type\":\"integer\",\"description\":\"要回滚的用例 id\"},"
            + "\"targetVersion\":{\"type\":\"integer\",\"description\":\"回滚目标版本号（版本历史里的 vN，≥1）\"},"
            + "\"baseVersion\":{\"type\":\"integer\",\"description\":\"当前用例版本（乐观锁锚，先用读工具取回）\"},"
            + "\"reason\":{\"type\":\"string\",\"description\":\"向人说明为何建议回滚到该版本\"}},"
            + "\"required\":[\"caseId\",\"targetVersion\",\"baseVersion\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public CaseWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            backfillDraftKinds();
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置用例写工具 {} 件（case.create/case.update/case.rollback，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tools can be registered
            // by hand on the tool page); the reason goes to the log in plain words.
            log.warn("内置用例写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
 * One-off alignment of rows seeded before the registry-driven card
 * routing : the two intent-anchored case tools gain the
 * draftObjectType token in their exec spec so the emitter's registry read
 * finds them. Idempotent (only touches rows missing the token) and
 * intent-anchored — human-made tools are never rewritten.
 */
    private void backfillDraftKinds() {
        int updated = jdbcTemplate.update(
                "UPDATE tp_tool SET exec_spec = exec_spec || jsonb_build_object("
                        + "'draftObjectType', 'CASE_WRITE'), updated_at = now() "
                        + "WHERE exec_spec ->> 'intent' IN ('case.create','case.update') "
                        + "AND exec_spec ->> 'draftObjectType' IS NULL");
        if (updated > 0) {
            log.info("内置用例写工具补齐草案路由标记 {} 行", updated);
        }
    }

    /**
     * Register whichever of the two contract tools is missing; returns how many
     * rows this call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN (" + intentPlaceholders() + ")",
                String.class, INTENTS.toArray());
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                // Contract name taken by a foreign row (no intent anchor):
                // skip + warn instead of a half-registered crash on the name bucket.
                log.warn("内置用例写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
                continue;
            }
            registerPublished(intent);
            created++;
        }
        return created;
    }

    /** "?,?,?" placeholder run for the intent set — the list is the single source. */
    private static String intentPlaceholders() {
        return String.join(",", java.util.Collections.nCopies(INTENTS.size(), "?"));
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
        // draftObjectType is the routing token the dialog emitter reads
        // .
        // Rollback lands on its own core strategy, so it carries its own
        // token; create/edit stay one family on CASE_WRITE.
        String draftType = TOOL_CASE_ROLLBACK.equals(intent) ? "CASE_ROLLBACK" : "CASE_WRITE";
        String spec = "{\"url\":\"" + PLACEHOLDER_URL + "\",\"intent\":\"" + intent
                + "\",\"draftObjectType\":\"" + draftType + "\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "用例",
                describe(intent),
                null,
                "[\"用例\", \"内置\"]",
                schemaFor(intent),
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }

    /** Card-facing tool description the model reads when choosing the call. */
    private static String describe(String intent) {
        return switch (intent) {
            case TOOL_CASE_CREATE -> "按对话意图新建测试用例（草案卡确认后才会写入，不会直接生效）";
            case TOOL_CASE_UPDATE -> "按对话意图编辑已有测试用例（草案卡确认后才会写入，不会直接生效）";
            case TOOL_CASE_ROLLBACK ->
                    "把用例回滚到版本历史中的某个版本（含撤销最近一次接受）；"
                            + "先用读工具确认用例当前版本作为乐观锁锚，草案卡确认后才会生效";
            default -> throw new IllegalArgumentException("unknown case-write intent: " + intent);
        };
    }

    private static String schemaFor(String intent) {
        return switch (intent) {
            case TOOL_CASE_CREATE -> SCHEMA_CREATE;
            case TOOL_CASE_UPDATE -> SCHEMA_UPDATE;
            case TOOL_CASE_ROLLBACK -> SCHEMA_ROLLBACK;
            default -> throw new IllegalArgumentException("unknown case-write intent: " + intent);
        };
    }
}
