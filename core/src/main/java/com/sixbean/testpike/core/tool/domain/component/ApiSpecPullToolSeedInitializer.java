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
 * Registers the built-in api-inventory contract-pull tool
 * ({@code apiSpec.pull}) of the ninth domain cleared by the tool-surface
 * clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report and
 * api-test tools, the dialog chain NEVER executes this tool: the schema
 * carries {@code x-write=true} so the dispatcher refuses a token-less call,
 * and the emitter journals a confirmable draft card instead — routing comes
 * from the {@code draftObjectType=APISPEC_WRITE} token in the exec spec
 * (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * ApiSpecService.fetchFromUrl URL-entry chain the inventory page's pull
 * dialog uses, so the SSRF whitelist, the timeout/size gates, the OpenAPI
 * format parse, the SHA256 idempotency (same content = conflict), the
 * version chain and the change-analysis hook stay single-sourced. The owning
 * project is read from the chat session, never from the payload — a
 * model-forged url can only ever append onto the session's own project.
 * Contract file upload stays a human action on /api-inventory: a dialog
 * payload carries no file bytes.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class ApiSpecPullToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiSpecPullToolSeedInitializer.class);

    static final String TOOL_APISPEC_PULL = "apiSpec.pull";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_APISPEC_PULL);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/api-specs/fetch-url";

    private static final String SCHEMA_APISPEC_PULL =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{\""
            + "name\":{\"type\":\"string\",\"description\":\"契约名称（版本链键，必填，同一项目内同名按版本递增）\"},"
            + "\"url\":{\"type\":\"string\",\"description\":\"契约文档地址（必填，仅 http/https，须命中出站白名单；白名单外、超时、超过 5MB 或非 OpenAPI 3 结构时会被拒绝并提示原因）\"}}"
            + ",\"required\":[\"name\",\"url\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public ApiSpecPullToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置接口契约拉取写工具 {} 件（apiSpec.pull，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置接口契约拉取写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('apiSpec.pull')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置接口契约拉取写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"APISPEC_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "接口清单",
                "从 URL 拉取接口契约文档入库并挂版本链；确认后走接口清单页同一拉取链，出站白名单、大小与格式校验、同内容去重与变更分析由该链完成；契约归属取当前会话项目，契约名称或文档地址缺失、地址不合规或内容不合规时会被拒绝并提示原因；契约文件的上传导入仍在接口清单页人工操作",
                null,
                "[\"接口清单\", \"内置\"]",
                SCHEMA_APISPEC_PULL,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
