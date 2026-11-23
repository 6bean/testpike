package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registers the built-in rehearsal-run launch tool :
 * the dialog action that starts a draft rehearsal batch whose id came back on
 * the draft-confirm receipt.
 * <p>Same posture as the analysis seed family — a published system-level
 * ToolDefinition row through the approval chain (create → submit → approve,
 * seed operator and approver kept apart as 1/2), so auditing, the marketplace
 * and the assembly vocabulary stay one place. Unlike the read-only analysis
 * rows this schema carries the root {@code x-write} flag: every launch goes
 * through the human confirm ticket (the write-chain one-click gate, zero
 * relaxation) and the server-side three-facet gate runs behind the ticket
 * inside the door.
 * <p>Idempotency anchor = {@code exec_spec->>'intent'} (display-name renaming
 * must not re-create the row); a name squatted by a foreign row is skipped
 * with a warning; a seed failure never blocks startup. The url targets the
 * internal rehearsal-runs door on this core service; the project segment and
 * the operator identity are injected per call server-side, never by the model.
 */
@Component
public class RehearsalRunToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RehearsalRunToolSeedInitializer.class);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;
    private static final String INTENT = "batch.rehearsalRun";
    private static final String TOOL_NAME = "执行回归演练";

    private static final String SPEC = """
            {"url":"http://localhost:8083/internal/ai/chat/rehearsal-runs",\
            "intent":"batch.rehearsalRun"}""";

    /** Root x-write: the launch action always demands the human confirm ticket. */
    private static final String SCHEMA = """
            {"type":"object","properties":{"batchId":{"type":"integer",\
            "description":"批次编号（草案确认回执中给出）"}},"required":["batchId"],"x-write":true}""";

    private static final String DESCRIPTION =
            "启动一个由演练草案确认创建的草稿态执行批次：需要批次编号；仅当对话执行类总闸、"
                    + "回归演练执行开关与本项目的执行白名单全部开启时才会进入执行队列，"
                    + "任一档关闭时批次保持草稿态并说明在哪打开；启动前需人工点击确认，"
                    + "执行进度在批次详情页查看";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public RehearsalRunToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置演练启动工具（执行类，带人工确认票与三级门）");
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool page can register
            // it by hand); the reason goes to the log in operational words.
            log.warn("演练启动工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    /** Register the launch tool when the intent anchor has no row; returns 0 or 1. */
    public int seedMissing() {
        Integer present = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE exec_spec ->> 'intent' = ?",
                Integer.class, INTENT);
        if (present != null && present > 0) {
            return 0;
        }
        if (nameSquatted()) {
            log.warn("演练启动工具的展示名称已被人工工具占用，跳过种子注册（请人工核对）");
            return 0;
        }
        registerPublished();
        return 1;
    }

    private boolean nameSquatted() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = ? AND project_id IS NULL"
                        + " AND (exec_spec ->> 'intent' IS NULL OR exec_spec ->> 'intent' <> ?)",
                Integer.class, TOOL_NAME, INTENT);
        return n != null && n > 0;
    }

    private void registerPublished() {
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, TOOL_NAME, 2, "执行", DESCRIPTION, null,
                "[\"执行\",\"演练\",\"内置\"]", SCHEMA, null,
                ToolExecKind.HTTP.code(), SPEC, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
