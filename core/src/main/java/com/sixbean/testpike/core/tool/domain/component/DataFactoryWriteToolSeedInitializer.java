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
 * Registers the two built-in data-factory write tools
 * ({@code datafactory.create / datafactory.update}) of the seventeenth
 * domain cleared by the tool-surface clearing batch ( stage 11,
 * /data-factories).
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf, reservation, popup-rule, mock-space,
 * mock-stub, ai-generation and defect tools, the dialog chain NEVER executes
 * this tool: the schema carries {@code x-write=true} so the dispatcher
 * refuses a token-less call, and the emitter journals a confirmable draft
 * card instead — routing comes from the {@code draftObjectType=
 * DATA_FACTORY_WRITE} token in the exec spec (registry-driven, no
 * orchestrator name literals). The HTTP url is a form-validity placeholder,
 * unreachable in practice (see {@link CaseWriteToolSeedInitializer} for the
 * full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code DataFactoryService.create/update} chain the data-factory page's
 * editor uses, so the type one-of, the name/content mandates, the
 * env-belongs-to-project check and the pool-key grammar plus per
 * project+env uniqueness gate stay single-sourced and the dialog is never a
 * bypass. Attribution guard (H1): {@code DataFactoryCreateInput} carries a
 * project field the model could steer — the landing strategy ignores the
 * payload value and files the row in the chat session's project, and an
 * edit re-checks the target row's project before the chain runs, so a
 * forged call cannot touch another project's scripts. Saving a script never
 * runs it: executing (which dials the real engine and writes the data pool)
 * stays a deliberate human click on /data-factories.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class DataFactoryWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataFactoryWriteToolSeedInitializer.class);

    static final String TOOL_DATA_FACTORY_CREATE = "datafactory.create";
    static final String TOOL_DATA_FACTORY_UPDATE = "datafactory.update";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_DATA_FACTORY_CREATE, TOOL_DATA_FACTORY_UPDATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/data-factories";

    private static final String SCHEMA_FACTORY_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"造数脚本名称（必填，不超过 100 个字符，超长会被保存链拒绝并提示原因）\"},"
            + "\"type\":{\"type\":\"integer\",\"description\":\"造数类型：1=API 2=SQL 3=脚本（必填，其他值会被保存链拒绝）\"},"
            + "\"envId\":{\"type\":\"integer\",\"description\":\"所属环境编号（必填，环境必须属于当前项目，否则保存被拒绝；数据池按环境隔离）\"},"
            + "\"content\":{\"type\":\"string\",\"description\":\"脚本内容（必填）：API 型为含 method/url/body 的 JSON，SQL 型为含 sql 字段的 JSON，脚本型为 Python 代码；数据键名 poolKey 格式不合法或与同环境其他脚本占用冲突会被拒绝并提示原因\"},"
            + "\"paramsJson\":{\"type\":\"string\",\"description\":\"预留参数 JSON（可空）\"}"
            + "},\"required\":[\"name\",\"type\",\"envId\",\"content\"]}";

    private static final String SCHEMA_FACTORY_UPDATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"factoryId\":{\"type\":\"integer\",\"description\":\"要编辑的造数脚本编号（必填，须为当前项目内的脚本，其他项目的会被拒绝）\"},"
            + "\"name\":{\"type\":\"string\",\"description\":\"新的脚本名称（改名的话必填，不超过 100 个字符）\"},"
            + "\"content\":{\"type\":\"string\",\"description\":\"新的脚本内容（改内容时数据键名格式与同环境占用冲突照常复核）\"},"
            + "\"status\":{\"type\":\"integer\",\"description\":\"启停状态：1=启用 2=停用（可空，不填保持当前状态）\"},"
            + "\"paramsJson\":{\"type\":\"string\",\"description\":\"新的预留参数 JSON（可空，不填保持当前值）\"}"
            + "},\"required\":[\"factoryId\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public DataFactoryWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置数据工厂写工具 {} 件（datafactory.create/update，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置数据工厂写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('datafactory.create','datafactory.update')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置数据工厂写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"DATA_FACTORY_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "数据工厂",
                switch (intent) {
                    case TOOL_DATA_FACTORY_CREATE ->
                            "在当前项目保存一个新的造数脚本（API/SQL/Python 三型）；确认后走数据工厂页同一保存链——"
                            + "类型、环境须属于当前项目、数据键名格式与同环境占用冲突照常在保存时复核，"
                            + "不通过会被拒绝并提示原因；归属项目取当前会话项目，载荷无法指向其他项目；"
                            + "确认只保存脚本定义，运行造数仍在数据工厂页由人工点击执行";
                    default ->
                            "按对话意图编辑当前项目内的已有造数脚本（改名、改内容、改参数或启用/停用）；"
                            + "确认后走数据工厂页同一保存链——改内容时数据键名格式与同环境占用冲突照常复核，"
                            + "脚本归属项目复核后方可写入，其他项目的脚本会被拒绝；"
                            + "草案卡确认后才会写入，不会直接生效，运行造数仍在数据工厂页由人工点击执行";
                },
                null,
                "[\"数据工厂\", \"内置\"]",
                TOOL_DATA_FACTORY_CREATE.equals(intent) ? SCHEMA_FACTORY_CREATE : SCHEMA_FACTORY_UPDATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
