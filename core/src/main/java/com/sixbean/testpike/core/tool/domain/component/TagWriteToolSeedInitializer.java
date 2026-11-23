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
 * Registers the three built-in tag-write tools ({@code tag.create},
 * {@code tag.rename}, {@code tag.merge}) of the first domain cleared by the
 * tool-surface clearing batch .
 * <p>Like the case-write pair, the dialog chain NEVER executes these tools:
 * all three schemas carry {@code x-write=true} so the dispatcher refuses a
 * token-less call, and the emitter journals a confirmable draft card instead
 * — routing comes from the {@code draftObjectType=TAG_WRITE} token in each
 * exec spec (registry-driven since stage 2, no orchestrator name literals).
 * The HTTP url is a form-validity placeholder, unreachable in practice (see
 * {@link CaseWriteToolSeedInitializer} for the full bypass-proof reasoning).
 * <p>Approval chain is not bypassed: create → submit → approve all run
 * through ToolService (seed operator/approver ids 1/2).
 * <p>Idempotency anchor = exec_spec intent (same posture as the case pair);
 * a name squatted by a foreign row is skipped with a warning, never crashed.
 */
@Component
public class TagWriteToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TagWriteToolSeedInitializer.class);

    static final String TOOL_TAG_CREATE = "tag.create";
    static final String TOOL_TAG_RENAME = "tag.rename";
    static final String TOOL_TAG_MERGE = "tag.merge";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_TAG_CREATE, TOOL_TAG_RENAME, TOOL_TAG_MERGE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL = "http://localhost:8083/internal/tags";

    private static final String SCHEMA_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"标签名（同项目唯一）\"},"
            + "\"type\":{\"type\":\"integer\",\"description\":\"标签类型（1=模块 2=功能 3=优先级 4=自由标签，默认自由标签）\"}},"
            + "\"required\":[\"name\"]}";

    private static final String SCHEMA_RENAME =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"tagId\":{\"type\":\"integer\",\"description\":\"目标标签 id\"},"
            + "\"newName\":{\"type\":\"string\",\"description\":\"新标签名（同项目重名会被拒绝并提示原因）\"}},"
            + "\"required\":[\"tagId\",\"newName\"]}";

    private static final String SCHEMA_MERGE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"fromTagId\":{\"type\":\"integer\",\"description\":\"被合并标签 id（合并后删除）\"},"
            + "\"toTagId\":{\"type\":\"integer\",\"description\":\"承接全部用例关联的目标标签 id\"}},"
            + "\"required\":[\"fromTagId\",\"toTagId\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public TagWriteToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置标签写工具 {} 件（tag.create/tag.rename/tag.merge，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tools can be registered
            // by hand on the tool page); the reason goes to the log in plain words.
            log.warn("内置标签写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register whichever of the three contract tools is missing; returns how
     * many rows this call created (0 = list complete, safe to re-run every
     * start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('tag.create','tag.rename','tag.merge')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置标签写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"TAG_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "标签",
                switch (intent) {
                    case TOOL_TAG_CREATE -> "按对话意图新建用例标签（草案卡确认后才会写入，不会直接生效）";
                    case TOOL_TAG_RENAME -> "按对话意图重命名已有标签（草案卡确认后才会写入，不会直接生效）";
                    default -> "按对话意图合并标签，用例关联整体迁移（草案卡确认后才会写入，不会直接生效）";
                },
                null,
                "[\"标签\", \"内置\"]",
                switch (intent) {
                    case TOOL_TAG_CREATE -> SCHEMA_CREATE;
                    case TOOL_TAG_RENAME -> SCHEMA_RENAME;
                    default -> SCHEMA_MERGE;
                },
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
