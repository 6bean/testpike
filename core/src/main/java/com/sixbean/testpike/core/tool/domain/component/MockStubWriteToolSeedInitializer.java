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
 * Registers the built-in mock-stub rule tools of the fourteenth domain
 * cleared by the tool-surface clearing batch :
 * {@code mock.stub.create} and {@code mock.stub.update}.
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory, perf, reservation, popup-rule and mock-space
 * tools, the dialog chain NEVER executes these tools: the schema carries
 * {@code x-write=true} so the dispatcher refuses a token-less call, and the
 * emitter journals a confirmable draft card instead — routing comes from the
 * {@code draftObjectType=MOCK_STUB_WRITE} token in the exec spec
 * (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see {@link
 * CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code MockStubService.create/update} chain the endpoint page's editor
 * uses, so the rule validator, the space-existence check, the scenario/state
 * declaration gate and the optimistic version conflict stay single-sourced
 * and the dialog is never a bypass. The owning space's project is re-checked
 * against the chat session at landing time — a forged space or stub id can
 * never file or edit a rule in another project's lane. A new rule only
 * serves traffic while its space is published and the rule enabled; space
 * publishing stays a deliberate human click on /mock/spaces.
 * <p>Approval chain is not bypassed for the tools themselves either: create
 * → submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class MockStubWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MockStubWriteToolSeedInitializer.class);

    static final String TOOL_MOCK_STUB_CREATE = "mock.stub.create";
    static final String TOOL_MOCK_STUB_UPDATE = "mock.stub.update";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_MOCK_STUB_CREATE, TOOL_MOCK_STUB_UPDATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/mock/stubs/save";

    /**
     * One shared rule-shape schema for both verbs; the create side also names
     * the filing space while the update side names the row and the version it
     * read. Descriptions are screen-facing text (Chinese allowed under the
     * open-source comment rule, which only pins code comments).
     */
    private static final String SCHEMA_MOCK_STUB_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{\""
            + "spaceId\":{\"type\":\"integer\",\"description\":\"所属 Mock 空间编号（必填，规则将登记在该空间的路由之下；跨项目的空间会被拒绝并提示原因）\"},"
            + "\"name\":{\"type\":\"string\",\"description\":\"规则名称（必填，不超过 120 字）\"},"
            + "\"method\":{\"type\":\"string\",\"description\":\"HTTP 方法：GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS 或 ANY（必填，不区分大小写）\"},"
            + "\"pathPattern\":{\"type\":\"string\",\"description\":\"路径模式（必填，以 / 开头，相对空间路由根；regex 类型须能编译，template 类型花括号须成对）\"},"
            + "\"pathMatchType\":{\"type\":\"string\",\"description\":\"路径匹配类型：exact、prefix、regex 或 template（可选，默认 exact）\"},"
            + "\"priority\":{\"type\":\"integer\",\"description\":\"空间内求值顺序，越小越先匹配（可选，默认 100）\"},"
            + "\"matchersJson\":{\"type\":\"string\",\"description\":\"匹配器行集合的 JSON 数组文本（可选，留空=无条件匹配，形状不合规会被拒绝并提示原因）\"},"
            + "\"responseJson\":{\"type\":\"string\",\"description\":\"响应构造的 JSON 对象文本（可选，留空=空响应；可含 status、headers、body、latencyMs、fault 故障注入块，不合规形状会被拒绝并提示原因）\"},"
            + "\"scenarioName\":{\"type\":\"string\",\"description\":\"所属状态机场景名（可选，留空=无状态规则；场景须已在本空间定义）\"},"
            + "\"requiredState\":{\"type\":\"string\",\"description\":\"本规则命中的前置场景状态（可选，须是场景声明过的状态；留空=任意状态命中）\"},"
            + "\"newState\":{\"type\":\"string\",\"description\":\"响应后推进到的场景状态（可选，须是场景声明过的状态）\"},"
            + "\"source\":{\"type\":\"string\",\"description\":\"规则来源标记（可选，默认 manual，只接受 manual、openapi、record、nl-ai、case-flip）\"},"
            + "\"enabled\":{\"type\":\"boolean\",\"description\":\"启用开关（可选，默认启用；规则须所属空间处于发布态才会对外提供访问）\"}"
            + "},\"required\":[\"spaceId\",\"name\",\"method\",\"pathPattern\"]}";

    private static final String SCHEMA_MOCK_STUB_UPDATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{\""
            + "stubId\":{\"type\":\"integer\",\"description\":\"要打桩规则的编号（必填）\"},"
            + "\"baseVersion\":{\"type\":\"integer\",\"description\":\"编辑所基于的规则版本号（必填，读取规则时看到的 version 值；若期间已被他人修改会被拒绝并提示刷新）\"},"
            + "\"name\":{\"type\":\"string\",\"description\":\"规则名称（必填，不超过 120 字）\"},"
            + "\"method\":{\"type\":\"string\",\"description\":\"HTTP 方法：GET、POST、PUT、DELETE、PATCH、HEAD、OPTIONS 或 ANY（必填，不区分大小写）\"},"
            + "\"pathPattern\":{\"type\":\"string\",\"description\":\"路径模式（必填，以 / 开头，相对空间路由根）\"},"
            + "\"pathMatchType\":{\"type\":\"string\",\"description\":\"路径匹配类型：exact、prefix、regex 或 template（可选，默认 exact）\"},"
            + "\"priority\":{\"type\":\"integer\",\"description\":\"空间内求值顺序，越小越先匹配（可选，默认 100）\"},"
            + "\"matchersJson\":{\"type\":\"string\",\"description\":\"匹配器行集合的 JSON 数组文本（可选，留空=无条件匹配）\"},"
            + "\"responseJson\":{\"type\":\"string\",\"description\":\"响应构造的 JSON 对象文本（可选，留空=空响应）\"},"
            + "\"scenarioName\":{\"type\":\"string\",\"description\":\"所属状态机场景名（可选，留空=无状态规则）\"},"
            + "\"requiredState\":{\"type\":\"string\",\"description\":\"本规则命中的前置场景状态（可选）\"},"
            + "\"newState\":{\"type\":\"string\",\"description\":\"响应后推进到的场景状态（可选）\"},"
            + "\"enabled\":{\"type\":\"boolean\",\"description\":\"启用开关（可选；规则须所属空间处于发布态才会对外提供访问）\"}"
            + "},\"required\":[\"stubId\",\"baseVersion\",\"name\",\"method\",\"pathPattern\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public MockStubWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置 Mock 规则写工具 {} 件（mock.stub.create / mock.stub.update，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tools can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置 Mock 规则写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('mock.stub.create', 'mock.stub.update')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置 Mock 规则写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
        boolean isCreate = TOOL_MOCK_STUB_CREATE.equals(intent);
        String spec = "{\"url\":\"" + PLACEHOLDER_URL + "\",\"intent\":\"" + intent
                + "\",\"draftObjectType\":\"MOCK_STUB_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "接口仿真",
                isCreate
                        ? "在当前项目的 Mock 空间下新建打桩规则（接口仿真的命中响应来源）；确认后走接口仿真 → Mock 接口页同一保存链——方法、路径模式、匹配器与响应结构、场景状态声明照常在校验时复核，不通过会被拒绝并提示原因；新规则须所属空间处于发布态且规则启用才对外提供访问，空间发布始终是人工决定；所属空间跨项目会被拒绝"
                        : "编辑当前项目内的 Mock 打桩规则（响应体、匹配器、优先级与启用开关）；确认后走接口仿真 → Mock 接口页同一保存链，形状与场景状态照常复核；必须携带读取规则时看到的版本号，若期间已被他人修改会被拒绝并提示刷新，不会静默覆盖别人的编辑；规则所属空间不可移动，跨项目规则会被拒绝",
                null,
                "[\"接口仿真\", \"内置\"]",
                isCreate ? SCHEMA_MOCK_STUB_CREATE : SCHEMA_MOCK_STUB_UPDATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
