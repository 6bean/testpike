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
 * Registers the two built-in requirement-write tools
 * ({@code requirement.create} and {@code requirement.update}) that make the
 * dialog's requirement intent visible to function calling (AL batch ,
 * registry side — the same posture as the case-write pair).
 * <p>The dialog chain NEVER executes these tools: both schemas carry
 * {@code x-write=true}, so the dispatcher refuses a token-less call and
 * ChatRunOrchestrator turns the call into a confirmable draft card instead —
 * landing goes through the shared confirm door and the RequirementWriteDraft
 * strategy, which writes through the same versioned RequirementService chain
 * the requirement page uses (no parallel save path, no zero-click auto-apply).
 * <p>{@code exec_spec.draftObjectType = REQUIREMENT_WRITE} is the routing
 * token the emitter's registry read matches on (registry-driven cards, not
 * name literals). The HTTP url below is a form-validity placeholder
 * (exec_kind=HTTP requires one); it is unreachable in practice because both
 * the dialog chain and the execution chain gate on x-write before any
 * transport happens. Do not read it as a bypass route.
 * <p>Source discipline: dialog-created rows land as manual-source (3)
 * requirements inside the draft's own session project — the dialog never
 * impersonates the document-extraction channel and an external tracker
 * source is not a requirement source on this platform.
 * <p>Approval chain is not bypassed: create → submit → approve all run
 * through ToolService (seed operator/approver ids 1/2, same table and state
 * as human-registered tools).
 * <p>Idempotency anchor = exec_spec intent: a missing entry is re-seeded on
 * the next start while foreign rows stay untouched. A human-made tool
 * squatting a contract name (without the intent anchor) is skipped with a
 * warning instead of crashing the start on the unique-name bucket.
 */
@Component
public class RequirementWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RequirementWriteToolSeedInitializer.class);

    static final String TOOL_REQUIREMENT_CREATE = "requirement.create";
    static final String TOOL_REQUIREMENT_UPDATE = "requirement.update";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_REQUIREMENT_CREATE, TOOL_REQUIREMENT_UPDATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed (see class note). */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/requirements";

    /** Shared field domain of both tools (mirrors RequirementWriteDraftStrategy's landing fields). */
    private static final String REQUIREMENT_FIELDS =
            "\"acceptanceCriteria\":{\"type\":\"string\",\"description\":\"验收标准\"},"
            + "\"chapter\":{\"type\":\"string\",\"description\":\"所属章节\"}";

    private static final String SCHEMA_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"title\":{\"type\":\"string\",\"description\":\"需求点标题\"},"
            + REQUIREMENT_FIELDS + "},"
            + "\"required\":[\"title\"]}";

    private static final String SCHEMA_UPDATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"requirementId\":{\"type\":\"integer\",\"description\":\"目标需求点 id\"},"
            + "\"baseVersion\":{\"type\":\"integer\",\"description\":\"编辑基于的需求版本（乐观锁锚）\"},"
            + "\"title\":{\"type\":\"string\",\"description\":\"需求点标题\"},"
            + REQUIREMENT_FIELDS + "},"
            + "\"required\":[\"requirementId\",\"baseVersion\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public RequirementWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            backfillDraftKinds();
            int gateRows = ensureDraftSwitch();
            if (gateRows > 0) {
                log.info("内置需求草案开关出厂行缺失，已按出厂默认补齐 {} 行", gateRows);
            }
            if (created > 0) {
                log.info("已注册内置需求写工具 {} 件（requirement.create/requirement.update，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tools can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置需求写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Rows seeded before this token existed (or hand-made rows carrying the
     * intent anchor) gain the draftObjectType marker so the emitter's
     * registry read finds them. Idempotent (only touches rows missing the
     * token) and intent-anchored — human-made tools are never rewritten.
     */
    private void backfillDraftKinds() {
        int updated = jdbcTemplate.update(
                "UPDATE tp_tool SET exec_spec = exec_spec || jsonb_build_object("
                        + "'draftObjectType', 'REQUIREMENT_WRITE'), updated_at = now() "
                        + "WHERE exec_spec ->> 'intent' IN ('requirement.create','requirement.update') "
                        + "AND exec_spec ->> 'draftObjectType' IS NULL");
        if (updated > 0) {
            log.info("内置需求写工具补齐草案路由标记 {} 行", updated);
        }
    }

    /** Factory gate row for requirement draft cards (the emitter reads it per run). */
    static final String DRAFT_GATE_KEY = "ai.chat.requirement-draft.enabled";

    /** Factory wording, verbatim from the version-base migration (single source of text). */
    private static final String DRAFT_GATE_DESCRIPTION =
            "需求点草案开关（默认开启；对话中 AI 提出新建或修订需求点时附草案卡，确认后走需求页同一保存链——"
            + "标题长度、状态取值、项目归属照常在确认时复核；编辑草案带版本锚，需求点在此期间被他人改过会被拒绝并提示重新分析；"
            + "关闭时回答照常给出，不再附草案卡）";

    /**
     * Replenish the factory draft gate row when the shared dev database lost
     * it (the L2 TRUNCATE-wash family wipes tp_sys_config periodically).
     * The startup hook is the single replenish channel (R37④): a missing
     * row is inserted with the factory default, an existing row — including
     * a human-set opt-out value — is never rewritten. Returns inserted rows.
     */
    public int ensureDraftSwitch() {
        return jdbcTemplate.update(
                "INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)"
                        + " VALUES (?, 'true', '系统', 'bool', 0, ?) ON CONFLICT (config_key) DO NOTHING",
                DRAFT_GATE_KEY, DRAFT_GATE_DESCRIPTION);
    }

    /**
     * Register whichever of the two contract tools is missing; returns how
     * many rows this call created (0 = list complete, safe to re-run every
     * start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('requirement.create','requirement.update')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                // Contract name taken by a foreign row (no intent anchor):
                // skip + warn instead of a half-registered crash on the name
                // bucket.
                log.warn("内置需求写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
        boolean isCreate = TOOL_REQUIREMENT_CREATE.equals(intent);
        String spec = "{\"url\":\"" + PLACEHOLDER_URL + "\",\"intent\":\"" + intent
                + "\",\"draftObjectType\":\"REQUIREMENT_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "需求",
                isCreate
                        ? "按对话意图新建需求点（草案卡确认后才会写入，不会直接生效）"
                        : "按对话意图编辑已有需求点（草案卡确认后才会写入，不会直接生效）",
                null,
                "[\"需求\", \"内置\"]",
                isCreate ? SCHEMA_CREATE : SCHEMA_UPDATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
