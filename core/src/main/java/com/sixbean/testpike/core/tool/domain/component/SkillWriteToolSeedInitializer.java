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
 * Registers the built-in skill write tool ({@code skill.save}) of the third
 * domain cleared by the tool-surface clearing batch .
 * <p>Like the tag and prompt-template tools, the dialog chain NEVER executes
 * this tool: the schema carries {@code x-write=true} so the dispatcher
 * refuses a token-less call, and the emitter journals a confirmable draft
 * card instead — routing comes from the {@code draftObjectType=SKILL_WRITE}
 * token in the exec spec (registry-driven, no orchestrator name literals).
 * The HTTP url is a form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Shape note: a confirmed card creates the skill as a DRAFT row inside the
 * session's project; the human approval chain on /skills still decides
 * whether it ever gets published, so the dialog door never bypasses review.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class SkillWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillWriteToolSeedInitializer.class);

    static final String TOOL_SKILL_SAVE = "skill.save";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_SKILL_SAVE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/skills";

    private static final String SCHEMA_SAVE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"技能名（同项目内不可重名）\"},"
            + "\"type\":{\"type\":\"integer\",\"description\":\"技能类型：1=用例检查 2=需求补齐 3=脚本开发规范 4=测试执行规范 5=自由\"},"
            + "\"body\":{\"type\":\"string\",\"description\":\"技能正文（受控 DSL 内容，确认后写入即为待审批草稿，仍需人工在技能库页提交审批才会发布）\"},"
            + "\"frontmatterJson\":{\"type\":\"string\",\"description\":\"可选元数据（合法 JSON 文本）\"}},"
            + "\"required\":[\"name\",\"type\",\"body\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public SkillWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置技能写工具 {} 件（skill.save，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置技能写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('skill.save')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置技能写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"SKILL_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "技能库",
                "按对话意图创建技能草稿（确认后写入即为待审批草稿，仍需人工在技能库页提交审批才会发布）",
                null,
                "[\"技能库\", \"内置\"]",
                SCHEMA_SAVE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
