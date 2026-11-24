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
 * Registers the built-in exploration start tool
 * ({@code explore.session.create}) of the eighteenth and last debt domain
 * cleared by the tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf, reservation, popup-rule, mock-space,
 * mock-stub, ai-generation, defect and data-factory tools, the dialog chain
 * NEVER executes this tool: the schema carries {@code x-write=true} so the
 * dispatcher refuses a token-less call, and the emitter journals a
 * confirmable draft card instead — routing comes from the {@code
 * draftObjectType=EXPLORE_CREATE} token in the exec spec (registry-driven,
 * no orchestrator name literals). The HTTP url is a form-validity
 * placeholder, unreachable in practice (see {@link
 * CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code ExploreService.create} chain the explore page's dialog uses, so
 * the budget fuse, the goal non-blank and length mandates, the web-form
 * SSRF floor (file://, internal and metadata addresses refused) and the
 * mobile-form env whitelist gate stay single-sourced and the dialog is
 * never a bypass. Attribution guard (H1): the landing strategy ignores any
 * project field the model could steer and files the session in the chat
 * session's project. Cost honesty: unlike the definition-saving kinds,
 * confirming this card STARTS the exploration and spends project AI budget
 * — the description and the card face both say so, and the run stays
 * cancellable on /explore.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class ExploreCreateToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExploreCreateToolSeedInitializer.class);

    static final String TOOL_EXPLORE_SESSION_CREATE = "explore.session.create";
    /** Idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_EXPLORE_SESSION_CREATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/explore/sessions";

    private static final String SCHEMA_EXPLORE_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"goalText\":{\"type\":\"string\",\"description\":\"探索目标描述（必填，不超过 2000 字，空白或超长会被创建链拒绝并提示原因）\"},"
            + "\"startUrl\":{\"type\":\"string\",\"description\":\"起始地址（必填）：web 形态填 http(s) 地址，内网与元数据地址会被拒绝；移动形态填 app://包名\"},"
            + "\"envId\":{\"type\":\"integer\",\"description\":\"目标环境编号（仅移动形态必填，环境须在白名单内，否则创建被拒绝；web 形态可空）\"},"
            + "\"credRef\":{\"type\":\"string\",\"description\":\"测试账号凭据键名（可空，仅移动形态消费，只见键名不见明文）\"}"
            + "},\"required\":[\"goalText\",\"startUrl\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public ExploreCreateToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置探索发起工具 {} 件（explore.session.create，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置探索发起工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('explore.session.create')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置探索发起工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"EXPLORE_CREATE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "自主探索",
                "在当前项目发起一次自主探索会话（web 从起始网址逛页面，移动形态按包名+环境）；"
                        + "确认后走探索页同一创建链——达到上限自动停止、目标字数上限、起始地址合规底线"
                        + "（内网与元数据地址会被拒绝）与移动形态环境白名单照常在创建时复核，"
                        + "不通过会被拒绝并提示原因；归属项目取当前会话项目，载荷无法指向其他项目；"
                        + "确认即开始探索并占用项目 AI 预算，探索可在探索页随时取消",
                null,
                "[\"自主探索\", \"内置\"]",
                SCHEMA_EXPLORE_CREATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
