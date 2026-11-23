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
 * Registers the built-in prompt-template write tool ({@code promptTpl.save})
 * of the second domain cleared by the tool-surface clearing batch
 * .
 * <p>Like the tag trio, the dialog chain NEVER executes this tool: the schema
 * carries {@code x-write=true} so the dispatcher refuses a token-less call,
 * and the emitter journals a confirmable draft card instead — routing comes
 * from the {@code draftObjectType=PROMPT_TPL_WRITE} token in the exec spec
 * (registry-driven, no orchestrator name literals). The HTTP url is a
 * form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: prompt templates are append-only ("edit = new version row"
 * is the storage contract of PromptTemplateService), so this domain seeds ONE
 * tool — there is no separate update verb to route to.
 * <p>Approval chain is not bypassed: create → submit → approve all run
 * through ToolService (seed operator/approver ids 1/2). Idempotency anchor =
 * exec_spec intent; a name squatted by a foreign row is skipped with a
 * warning, never crashed.
 */
@Component
public class PromptTplWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptTplWriteToolSeedInitializer.class);

    static final String TOOL_PROMPTTPL_SAVE = "promptTpl.save";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_PROMPTTPL_SAVE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/prompt-templates";

    private static final String SCHEMA_SAVE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"模板名（与业务域共同确定版本族）\"},"
            + "\"domain\":{\"type\":\"string\",\"description\":\"业务域（同模板名不同域各自独立版本族）\"},"
            + "\"content\":{\"type\":\"string\",\"description\":\"模板正文（保存即族内新版本行，默认灰度状态，旧版本原文保留）\"}},"
            + "\"required\":[\"name\",\"domain\",\"content\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public PromptTplWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置提示词模板写工具 {} 件（promptTpl.save，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置提示词模板写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('promptTpl.save')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置提示词模板写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"PROMPT_TPL_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "提示词模板",
                "按对话意图保存提示词模板新版本（保存即族内新版本、默认灰度；草案卡确认后才会写入，不会直接生效）",
                null,
                "[\"提示词模板\", \"内置\"]",
                SCHEMA_SAVE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
