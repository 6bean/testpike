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
 * Registers the three built-in defect-library write tools
 * ({@code defect.create / defect.update / defect.transition}) of the
 * sixteenth domain cleared by the tool-surface clearing batch ( stage
 * 10, /defects).
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf, reservation, popup-rule, mock-space,
 * mock-stub and ai-generation tools, the dialog chain NEVER executes this
 * tool: the schema carries {@code x-write=true} so the dispatcher refuses a
 * token-less call, and the emitter journals a confirmable draft card instead
 * — routing comes from the {@code draftObjectType=DEFECT_WRITE} token in
 * the exec spec (registry-driven, no orchestrator name literals). The HTTP
 * url is a form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code DefectLibraryService.create/update/transition} chain the defect
 * page's dialogs use, so the title ceiling, the severity one-of, the
 * same-project checks on the linked case/plan, the fixer-membership refusal,
 * the closed-state edit ban, the six-state transition table and the
 * optimistic version conflict stay single-sourced and the dialog is never a
 * bypass. Attribution guard (H1): {@code DefectCreateInput} carries a
 * project field the model could steer — the landing strategy ignores the
 * payload value and files the row in the chat session's project, and edit/
 * transition re-check the target row's project before the chain runs, so a
 * forged call cannot file or move a defect in another project's lane.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class DefectWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefectWriteToolSeedInitializer.class);

    static final String TOOL_DEFECT_CREATE = "defect.create";
    static final String TOOL_DEFECT_UPDATE = "defect.update";
    static final String TOOL_DEFECT_TRANSITION = "defect.transition";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS =
            List.of(TOOL_DEFECT_CREATE, TOOL_DEFECT_UPDATE, TOOL_DEFECT_TRANSITION);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/defects";

    private static final String SCHEMA_DEFECT_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"title\":{\"type\":\"string\",\"description\":\"缺陷标题（必填，不超过 200 个字符，超长会被建单链拒绝并提示原因）\"},"
            + "\"description\":{\"type\":\"string\",\"description\":\"详细描述（可空）\"},"
            + "\"severity\":{\"type\":\"integer\",\"description\":\"严重级别：1=P0 2=P1 3=P2 4=P3，缺省按 P1；级别不在范围内会被建单链拒绝\"},"
            + "\"caseId\":{\"type\":\"integer\",\"description\":\"关联用例编号（可空；用例必须属于同一项目，否则建单被拒绝）\"},"
            + "\"planId\":{\"type\":\"integer\",\"description\":\"关联执行计划编号（可空；计划必须属于同一项目，否则建单被拒绝）\"},"
            + "\"assigneeId\":{\"type\":\"integer\",\"description\":\"修复人用户编号（可空；不是本项目成员会被拒绝；不填时项目开启了默认分派会自动分派给开发负责人）\"},"
            + "\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"stepNo\":{\"type\":\"integer\"},\"description\":{\"type\":\"string\"}}},\"description\":\"复现步骤（可空，按步骤号顺序）\"}"
            + "},\"required\":[\"title\"]}";

    private static final String SCHEMA_DEFECT_UPDATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"defectId\":{\"type\":\"integer\",\"description\":\"要编辑的缺陷编号（必填，须为当前项目内的缺陷）\"},"
            + "\"version\":{\"type\":\"integer\",\"description\":\"编辑所依据的缺陷版本号（必填；确认时若缺陷已被他人修改会被拒绝并提示刷新）\"},"
            + "\"title\":{\"type\":\"string\",\"description\":\"缺陷标题（必填，不超过 200 个字符）\"},"
            + "\"description\":{\"type\":\"string\",\"description\":\"详细描述\"},"
            + "\"severity\":{\"type\":\"integer\",\"description\":\"严重级别：1=P0 2=P1 3=P2 4=P3；级别不在范围内会被拒绝\"},"
            + "\"assigneeId\":{\"type\":\"integer\",\"description\":\"修复人用户编号（改派时须为当前项目成员，否则被拒绝）\"},"
            + "\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"stepNo\":{\"type\":\"integer\"},\"description\":{\"type\":\"string\"}}},\"description\":\"复现步骤\"}"
            + "},\"required\":[\"defectId\",\"version\",\"title\"]}";

    private static final String SCHEMA_DEFECT_TRANSITION =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"defectId\":{\"type\":\"integer\",\"description\":\"要流转的缺陷编号（必填，须为当前项目内的缺陷）\"},"
            + "\"version\":{\"type\":\"integer\",\"description\":\"流转所依据的缺陷版本号（必填；确认时若缺陷已被他人修改会被拒绝并提示刷新）\"},"
            + "\"toStatus\":{\"type\":\"integer\",\"description\":\"目标状态：2=处理中 3=已修复 4=验证中 5=已关闭 6=重新打开；须符合当前状态允许的流转，否则被拒绝并提示原因\"},"
            + "\"closedReason\":{\"type\":\"string\",\"description\":\"关闭或重新打开时必须填写的原因（不超过 500 个字符）\"}"
            + "},\"required\":[\"defectId\",\"version\",\"toStatus\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public DefectWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置缺陷库写工具 {} 件（defect.create/update/transition，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置缺陷库写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('defect.create','defect.update','defect.transition')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置缺陷库写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"DEFECT_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "缺陷管理",
                switch (intent) {
                    case TOOL_DEFECT_CREATE ->
                            "在当前项目登记一条集成缺陷；确认后走缺陷库页同一创建链——标题、严重级别、关联用例与计划同项目、"
                            + "修复人须为项目成员照常在创建时复核，不通过会被拒绝并提示原因；新单落为新建状态，"
                            + "后续状态流转仍在缺陷库页由人工推进；归属项目取当前会话项目，载荷无法指向其他项目";
                    case TOOL_DEFECT_UPDATE ->
                            "按对话意图编辑当前项目内的已有缺陷（改标题、描述、严重级别或改派修复人）；"
                            + "确认后走缺陷库页同一编辑链——已关闭的缺陷不可编辑需先重新打开，"
                            + "版本基于读取时的编号复核，期间被他人修改会被拒绝并提示刷新；草案卡确认后才会写入，不会直接生效";
                    default ->
                            "按对话意图流转当前项目内缺陷的状态（处理中/已修复/验证中/已关闭/重新打开）；"
                            + "确认后走缺陷库页同一状态机链——非法流转与关闭缺原因会被拒绝并提示原因，"
                            + "版本基于读取时的编号复核，期间被他人修改会被拒绝并提示刷新；草案卡确认后才会写入，不会直接生效";
                },
                null,
                "[\"缺陷管理\", \"内置\"]",
                switch (intent) {
                    case TOOL_DEFECT_CREATE -> SCHEMA_DEFECT_CREATE;
                    case TOOL_DEFECT_UPDATE -> SCHEMA_DEFECT_UPDATE;
                    default -> SCHEMA_DEFECT_TRANSITION;
                },
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
