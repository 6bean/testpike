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
 * Registers the built-in case flow-guide tool ({@code case.flow-guide},
 * AO-9 stage 2): "this case is stuck at some status — what can I do next".
 * <p>This is the repository's first tool that is actually DIALED: it is a
 * read tool (no {@code x-write}), so the dispatcher routes the call straight
 * into the HTTP exec form, which POSTs the model input to the exec_spec url
 * — the live {@code /internal/cases/flow-guide} POST door answered by
 * CaseFlowGuideService. Unlike the write-tool seeds, the url here is real
 * and is pinned by CaseFlowGuideToolSeedTest: turning it into a placeholder
 * would silently reproduce the never-dialed shape and defeat the tool.
 * <p>Answer vocabulary comes from the case state machine at query time (one
 * source, no second transition table), so status wording stays in sync with
 * the review flow automatically.
 * <p>Approval chain is not bypassed either: create -> submit -> approve all
 * run through ToolService (seed operator/approver ids 1/2). Idempotency
 * anchor = exec_spec intent; a name squatted by a foreign row is skipped
 * with a warning, never crashed.
 */
@Component
public class CaseFlowGuideToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CaseFlowGuideToolSeedInitializer.class);

    static final String TOOL_CASE_FLOW_GUIDE = "case.flow-guide";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_CASE_FLOW_GUIDE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Live dial url (POST): the dialog dispatcher sends the model input here. */
    private static final String DIAL_URL = "http://localhost:8083/internal/cases/flow-guide";

    private static final String SCHEMA_CASE_FLOW_GUIDE =
            "{\"type\":\"object\",\"properties\":{"
            + "\"caseId\":{\"type\":\"integer\",\"description\":\"用例 ID（选填；缺省=返回完整状态流程图谱，用于「用例一般有哪些状态、各状态能做什么」类问题）\"}}"
            + ",\"required\":[]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public CaseFlowGuideToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置用例流程引导读工具 {} 件（case.flow-guide，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置用例流程引导读工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /**
     * Register the contract tool when missing; returns how many rows this
     * call created (0 = list complete, safe to re-run every start).
     */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('case.flow-guide')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置用例流程引导读工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                "查询用例状态流转指引：给出某个用例当前状态可以直接执行的下一步动作（含直达页面），或不带用例编号时返回完整状态流程图谱；纯只读查询，不修改任何数据；用例编号不存在时会被拒绝并提示原因",
                null,
                "[\"用例\", \"内置\"]",
                SCHEMA_CASE_FLOW_GUIDE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
