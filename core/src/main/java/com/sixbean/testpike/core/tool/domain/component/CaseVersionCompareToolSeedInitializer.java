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
 * Registers the built-in case version-compare read tool
 * ({@code case.version-compare}, AM batch : "compare vA and vB
 * of this case" becomes a ONE-LINE diff summary card instead of a JSON dump.
 * <p>Same actually-dialed posture as case.flow-guide: a read tool (no
 * {@code x-write}), so the dispatcher POSTs the model input straight to the
 * exec_spec url — the live {@code /internal/cases/version-compare} door
 * answered by CaseVersionCompareService. The url literal is pinned by
 * CaseVersionCompareToolSeedTest; placeholder-ing it would silently restore
 * the old never-dialed shape and defeat the tool.
 * <p>The diff itself stays one source (the shared version door), and the
 * tool result carries field labels + stepNo-aligned step counts only — the
 * model can quote the card but never sees raw before/after values.
 * <p>Approval chain is not bypassed: create -> submit -> approve all run
 * through ToolService (seed operator/approver ids 1/2). Idempotency anchor =
 * exec_spec intent; a name squatted by a foreign row is skipped with a
 * warning, never crashed.
 */
@Component
public class CaseVersionCompareToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CaseVersionCompareToolSeedInitializer.class);

    static final String TOOL_CASE_VERSION_COMPARE = "case.version-compare";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_CASE_VERSION_COMPARE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Live dial url (POST): the dialog dispatcher sends the model input here. */
    private static final String DIAL_URL = "http://localhost:8083/internal/cases/version-compare";

    private static final String SCHEMA_CASE_VERSION_COMPARE =
            "{\"type\":\"object\",\"properties\":{"
            + "\"caseId\":{\"type\":\"integer\",\"description\":\"要对比版本的用例 ID\"},"
            + "\"fromVersion\":{\"type\":\"integer\",\"description\":\"基准版本号（选填；缺省=用例当前最新版本）\"},"
            + "\"toVersion\":{\"type\":\"integer\",\"description\":\"目标版本号（版本历史里的 vN，≥1）\"}}"
            + ",\"required\":[\"caseId\",\"toVersion\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public CaseVersionCompareToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置用例版本对比读工具 {} 件（case.version-compare，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置用例版本对比读工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('case.version-compare')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置用例版本对比读工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
        String spec = "{\"url\":\"" + DIAL_URL + "\",\"intent\":\"" + intent + "\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "用例",
                "对比同一用例的两个历史版本，返回一行差异摘要（列出发生变化的字段；操作步骤按步骤序号对齐给出改/增/删数量，"
                        + "不返回原文内容）；纯只读查询，不修改任何数据；用例或版本号不存在时会被拒绝并提示原因",
                null,
                "[\"用例\", \"内置\", \"版本\"]",
                SCHEMA_CASE_VERSION_COMPARE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
