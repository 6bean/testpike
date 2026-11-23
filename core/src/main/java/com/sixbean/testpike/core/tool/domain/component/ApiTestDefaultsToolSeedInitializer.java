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
 * Registers the built-in api-test defaults tool ({@code apitest.defaults.save})
 * of the eighth domain cleared by the tool-surface clearing batch (
 * stage 5a).
 * <p>Like the tag, prompt-template, skill, review, plan, batch and report
 * tools, the dialog chain NEVER executes this tool: the schema carries
 * {@code x-write=true} so the dispatcher refuses a token-less call, and the
 * emitter journals a confirmable draft card instead — routing comes from the
 * {@code draftObjectType=APITEST_WRITE} token in the exec spec
 * (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME ApiCollectionService.rename
 * full save face the collection dialog uses (null name/tags = keep stored
 * value), with the header-shape and auth vocabulary gates enforced by that
 * chain; the owning project is re-checked against the chat session, never
 * taken from the payload. Request-level saving stays a human editor action
 * on /api-test.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class ApiTestDefaultsToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiTestDefaultsToolSeedInitializer.class);

    static final String TOOL_APITEST_DEFAULTS_SAVE = "apitest.defaults.save";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_APITEST_DEFAULTS_SAVE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/apitest/collections/{id}";

    private static final String SCHEMA_APITEST_DEFAULTS_SAVE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"collectionId\":{\"type\":\"integer\",\"description\":\"目标接口集 ID（必填，须属于当前会话项目）\"},"
            + "\"headersJson\":{\"type\":\"string\",\"description\":\"默认请求头 JSON 数组字符串，形如 [{\\\"name\\\":\\\"X-Tenant\\\",\\\"value\\\":\\\"a\\\"}]（可空=不改动存量默认头）\"},"
            + "\"authJson\":{\"type\":\"string\",\"description\":\"默认认证配置 JSON 对象字符串，认证型与字段沿用接口集页同一表单契约（可空=不改动存量默认认证）\"}}"
            + ",\"required\":[\"collectionId\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public ApiTestDefaultsToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置接口集默认配置写工具 {} 件（apitest.defaults.save，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置接口集默认配置写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('apitest.defaults.save')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置接口集默认配置写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"APITEST_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "接口测试",
                "保存接口集的默认请求头与默认认证配置；确认后走接口集页同一保存链，请求头格式校验、认证配置契约校验与审计由该链完成；接口集名称与标签保持不变，单个请求的编辑仍在接口测试页人工操作；接口集编号缺失、不属于当前项目或配置内容不合规时会被拒绝并提示原因",
                null,
                "[\"接口测试\", \"内置\"]",
                SCHEMA_APITEST_DEFAULTS_SAVE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
