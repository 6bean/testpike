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
 * Registers the built-in mock-space create tool
 * ({@code mock.space.create}) of the thirteenth domain cleared by the
 * tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf, reservation and popup-rule tools, the
 * dialog chain NEVER executes this tool: the schema carries
 * {@code x-write=true} so the dispatcher refuses a token-less call, and the
 * emitter journals a confirmable draft card instead — routing comes from the
 * {@code draftObjectType=MOCK_SPACE_WRITE} token in the exec spec
 * (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see {@link
 * CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code MockSpaceService.create} chain the space page's dialog uses, so
 * the structural validator, the production-environment ban and the
 * site-wide routing-key uniqueness conflict stay single-sourced and the
 * dialog is never a bypass of one gate. The owning project is read from the
 * chat session (the input record has no project field at all) — a forged
 * call cannot open a space in another project's lane. The produced space is
 * a DRAFT row: publishing it (the transition that opens the traffic door
 * under /mocks/) stays a deliberate human click on /mock/spaces.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class MockSpaceCreateToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockSpaceCreateToolSeedInitializer.class);

    static final String TOOL_MOCK_SPACE_CREATE = "mock.space.create";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_MOCK_SPACE_CREATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/mock/spaces/create";

    private static final String SCHEMA_MOCK_SPACE_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"Mock 空间名称（必填，不超过 120 字）\"},"
            + "\"spaceKey\":{\"type\":\"string\",\"description\":\"对外路由标识（必填，小写字母/数字/下划线/连字符且以字母或数字开头，全站唯一，进入访问路径 /mocks/{spaceKey}/ 之下）\"},"
            + "\"alias\":{\"type\":\"string\",\"description\":\"第二路径段别名（可选，字符规则与路由标识相同）\"},"
            + "\"envId\":{\"type\":\"integer\",\"description\":\"绑定环境编号（可选，留空=不绑定；生产环境会被创建链拒绝并提示原因）\"},"
            + "\"basePathMode\":{\"type\":\"string\",\"description\":\"路径基准模式：prefix 或 strict（可选，默认 prefix）\"},"
            + "\"authMode\":{\"type\":\"string\",\"description\":\"鉴权模式：none、token 或 cred（可选，默认 none）\"},"
            + "\"rateLimitRps\":{\"type\":\"integer\",\"description\":\"空间级限流 RPS（可选，留空走系统默认，须为正数）\"},"
            + "\"quotaDaily\":{\"type\":\"integer\",\"description\":\"空间级日配额（可选，留空走系统默认，须为正数）\"},"
            + "\"priority\":{\"type\":\"integer\",\"description\":\"跨空间路由同路径优先级（可选，默认 100，越小越优先）\"},"
            + "\"chaosPercent\":{\"type\":\"integer\",\"description\":\"故障注入比例 0-100（可选，默认 0=不注入）\"},"
            + "\"missPolicy\":{\"type\":\"string\",\"description\":\"未命中策略：passthrough、reject 或 llm-fallback（可选，默认 llm-fallback）\"}"
            + "},\"required\":[\"name\",\"spaceKey\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public MockSpaceCreateToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置 Mock 空间创建写工具 {} 件（mock.space.create，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置 Mock 空间创建写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('mock.space.create')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置 Mock 空间创建写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"MOCK_SPACE_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "接口仿真",
                "在当前项目创建 Mock 空间（接口仿真转发与打桩的挂载点）；确认后走空间页同一创建链——名称与路由标识规则、生产环境禁用与路由标识全站唯一冲突照常在创建时复核，不通过会被拒绝并提示原因；产出为草稿态空间，须人工在「接口仿真 → Mock 空间」发布后才对外提供访问；归属项目取当前会话项目，载荷无法指向其他项目",
                null,
                "[\"接口仿真\", \"内置\"]",
                SCHEMA_MOCK_SPACE_CREATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
