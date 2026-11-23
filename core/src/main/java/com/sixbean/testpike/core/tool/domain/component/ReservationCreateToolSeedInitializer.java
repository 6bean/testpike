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
 * Registers the built-in device-reservation create tool
 * ({@code appauto.reservation.create}) of the eleventh domain cleared by the
 * tool-surface clearing batch .
 * <p>Like the tag, prompt-template, skill, review, plan, batch, report,
 * api-test, api-inventory and perf tools, the dialog chain NEVER executes
 * this tool: the schema carries {@code x-write=true} so the dispatcher
 * refuses a token-less call, and the emitter journals a confirmable draft
 * card instead — routing comes from the {@code draftObjectType=
 * RESERVATION_WRITE} token in the exec spec (registry-driven, no orchestrator
 * name literals). The HTTP url is a form-validity placeholder, unreachable in
 * practice (see {@link CaseWriteToolSeedInitializer} for the full bypass-proof
 * reasoning).
 * <p>Shape note: confirming a card runs the SAME
 * {@code AppautoReservationService.create} chain the reservation calendar's
 * dialog uses, so the window validation, the device-existence check, the
 * conflict refusal (it names the current holder and the expected release
 * time) and the approval-switch routing stay single-sourced and the dialog is
 * never a bypass of one gate. The owning project is read from the chat
 * session, never from the payload — a model-forged device id can only ever be
 * booked inside (and checked against) the session's own project lane. The
 * card states the occupied device and window before the human confirms.
 * Releasing or cancelling an existing reservation stays a deliberate human
 * click on /appauto/reservations.
 * <p>Approval chain is not bypassed for the tool itself either: create →
 * submit → approve all run through ToolService (seed operator/approver ids
 * 1/2). Idempotency anchor = exec_spec intent; a name squatted by a foreign
 * row is skipped with a warning, never crashed.
 */
@Component
public class ReservationCreateToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReservationCreateToolSeedInitializer.class);

    static final String TOOL_RESERVATION_CREATE = "appauto.reservation.create";
    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_RESERVATION_CREATE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Placeholder exec url: required by the HTTP exec-spec guard, never dialed. */
    private static final String PLACEHOLDER_URL =
            "http://localhost:8083/internal/appauto/reservations";

    private static final String SCHEMA_RESERVATION_CREATE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"deviceId\":{\"type\":\"integer\",\"description\":\"要预约的设备编号（必填，须已登记，由创建链复核）\"},"
            + "\"startsAt\":{\"type\":\"string\",\"description\":\"预约窗开始时间（必填，形如 2026-10-30 09:00:00）\"},"
            + "\"endsAt\":{\"type\":\"string\",\"description\":\"预约窗结束时间（必填，须晚于开始时间；与同设备已有预约或租约重叠时会被拒绝并点名占用者）\"},"
            + "\"reason\":{\"type\":\"string\",\"description\":\"预约事由（人话，展示与审计面）\"}"
            + "},\"required\":[\"deviceId\",\"startsAt\",\"endsAt\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;

    public ReservationCreateToolSeedInitializer(ToolService toolService, JdbcTemplate jdbcTemplate) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("已注册内置设备预约写工具 {} 件（appauto.reservation.create，走审批链发布态）", created);
            }
        } catch (RuntimeException e) {
            // A seed failure must not block startup (the tool can be
            // registered by hand on the tool page); the reason goes to the
            // log in plain words.
            log.warn("内置设备预约写工具种子注册失败（不影响启动，可在工具页手工注册）: {}", e.getMessage());
        }
    }

    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('appauto.reservation.create')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("内置设备预约写工具 {} 的契约名称已被人工工具占用，跳过种子注册（请人工核对）", intent);
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
                + "\",\"draftObjectType\":\"RESERVATION_WRITE\"}";
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "APP 自动化",
                "按设备与时间窗创建一条设备预约；确认后走预约日历同一创建链，时间窗校验、设备存在性、与已有预约或租约的冲突判定照常在创建时复核，冲突会被拒绝并点名占用者与预计释放时间；草案卡会注明本次预约占用的设备与时间窗；项目归属取当前会话项目；是否需审批按预约审批开关照常判定；已有预约的归还与取消仍在预约日历人工操作",
                null,
                "[\"APP自动化\", \"内置\"]",
                SCHEMA_RESERVATION_CREATE,
                null, ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
