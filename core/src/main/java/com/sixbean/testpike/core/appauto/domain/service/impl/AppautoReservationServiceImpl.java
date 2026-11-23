package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoReservationService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;


@Service
public class AppautoReservationServiceImpl implements AppautoReservationService {

    private static final Logger log = LoggerFactory.getLogger(AppautoReservationServiceImpl.class);

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    
    private static final String SELECT_COLUMNS = """
            r.id, r.project_id, r.device_id, r.batch_id, r.holder_user_id,
            COALESCE(NULLIF(u.display_name, ''), u.username) AS holder_name,
            r.starts_at, r.ends_at, r.state, r.reason,
            r.approved_by, r.approved_at, r.released_at, r.release_reason, r.created_at
            """;

    private static final RowMapper<AppautoReservationView> VIEW_MAPPER = (rs, rowNum) ->
            new AppautoReservationView(
                    rs.getLong("id"),
                    (Long) rs.getObject("project_id"),
                    rs.getLong("device_id"),
                    (Long) rs.getObject("batch_id"),
                    rs.getLong("holder_user_id"),
                    rs.getString("holder_name"),
                    rs.getObject("starts_at", LocalDateTime.class),
                    rs.getObject("ends_at", LocalDateTime.class),
                    rs.getString("state"),
                    rs.getString("reason"),
                    (Long) rs.getObject("approved_by"),
                    rs.getObject("approved_at", LocalDateTime.class),
                    rs.getObject("released_at", LocalDateTime.class),
                    rs.getString("release_reason"),
                    rs.getObject("created_at", LocalDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoConfigReader configReader;
    private final NotificationService notificationService;

    public AppautoReservationServiceImpl(NamedParameterJdbcTemplate jdbc,
                                         AppautoConfigReader configReader,
                                         NotificationService notificationService) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.notificationService = notificationService;
    }

    @Override
    public AppautoReservationView create(AppautoReservationInput input) {
        validate(input);
        requireDeviceExists(input.deviceId());
        rejectIfConflicting(input);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("project_id", input.projectId())
                .addValue("device_id", input.deviceId())
                .addValue("batch_id", input.batchId())
                .addValue("holder_user_id", input.holderUserId())
                .addValue("starts_at", input.startsAt())
                .addValue("ends_at", input.endsAt())
                .addValue("reason", trimToNull(input.reason()));
        params.addValue("state",
                configReader.reservationApprovalRequired() ? "PENDING_APPROVAL" : "ACTIVE");
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_appauto_reservation (project_id, device_id, batch_id,"
                        + " holder_user_id, starts_at, ends_at, state, reason)"
                        + " VALUES (:project_id, :device_id, :batch_id, :holder_user_id,"
                        + " :starts_at, :ends_at, :state, :reason) RETURNING id",
                params, Long.class);
        return get(id);
    }

    @Override
    public AppautoReservationView get(long id) {
        List<AppautoReservationView> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_appauto_reservation r"
                        + " JOIN tp_user u ON u.id = r.holder_user_id WHERE r.id = :id",
                new MapSqlParameterSource("id", id), VIEW_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("设备预约不存在或已被删除（预约号 " + id + "）——请在「设备预约日历」列表核对预约号，或点「新建预约」重新提交",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public AppautoReservationView release(long id, String reason) {
        int flipped = jdbc.update(
                "UPDATE tp_appauto_reservation SET state = 'RELEASED',"
                        + " released_at = now(), release_reason = :reason,"
                        + " updated_at = now()"
                        + " WHERE id = :id AND state IN ('PENDING_APPROVAL', 'ACTIVE')",
                new MapSqlParameterSource("id", id)
                        .addValue("reason", blankFallback(reason, "主动归还")));
        return finishTransition(id, flipped, "归还");
    }

    @Override
    public AppautoReservationView cancel(long id, String reason) {
        int flipped = jdbc.update(
                "UPDATE tp_appauto_reservation SET state = 'CANCELLED',"
                        + " released_at = now(), release_reason = :reason,"
                        + " updated_at = now()"
                        + " WHERE id = :id AND state IN ('PENDING_APPROVAL', 'ACTIVE')",
                new MapSqlParameterSource("id", id)
                        .addValue("reason", blankFallback(reason, "取消预约")));
        return finishTransition(id, flipped, "取消");
    }

 
    private AppautoReservationView finishTransition(long id, int flipped, String action) {
        if (flipped == 0) {
            AppautoReservationView current = get(id);
            throw new ServiceException("该预约当前已是「" + stateLabel(current.state())
                    + "」状态，无法重复" + action + "（预约号 " + id + "）——请在「设备预约日历」查看该行当前状态后再操作",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        return get(id);
    }

    @Override
    public AppautoReservationView approve(long id, long approverUserId) {
        int flipped = jdbc.update(
                "UPDATE tp_appauto_reservation SET state = 'ACTIVE', "
                        + " approved_by = :approver, approved_at = now(), updated_at = now()"
                        + " WHERE id = :id AND state = 'PENDING_APPROVAL'",
                new MapSqlParameterSource("id", id).addValue("approver", approverUserId));
        if (flipped == 0) {
            AppautoReservationView current = get(id);
            throw new ServiceException("该预约当前是「" + stateLabel(current.state())
                    + "」状态，只有待审批的预约才能批准（预约号 " + id + "）——请在「设备预约日历」对处于待审批的该行执行批准",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        AppautoReservationView approved = get(id);
        notifyHolder(approved.holderUserId(), approved.projectId(), "RESERVATION_APPROVED",
                "设备预约已批准",
                "您预约的设备（设备号 " + approved.deviceId() + "，预约号 " + approved.id()
                        + "，时段 " + HHMM.format(approved.startsAt()) + "–"
                        + HHMM.format(approved.endsAt()) + "）已被批准，可按时使用",
                "RESERVATION_APPROVED:" + id);
        return approved;
    }

    @Override
    public AppautoReservationView reject(long id, long approverUserId, String reason) {
        int flipped = jdbc.update(
                "UPDATE tp_appauto_reservation SET state = 'REJECTED',"
                        + " approved_by = :approver, approved_at = now(),"
                        + " released_at = now(), release_reason = :reason, updated_at = now()"
                        + " WHERE id = :id AND state = 'PENDING_APPROVAL'",
                new MapSqlParameterSource("id", id)
                        .addValue("approver", approverUserId)
                        .addValue("reason", blankFallback(reason, "审批驳回")));
        if (flipped == 0) {
            AppautoReservationView current = get(id);
            throw new ServiceException("该预约当前是「" + stateLabel(current.state())
                    + "」状态，只有待审批的预约才能驳回（预约号 " + id + "）——请在「设备预约日历」对处于待审批的该行执行驳回",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        AppautoReservationView rejected = get(id);
        notifyHolder(rejected.holderUserId(), rejected.projectId(), "RESERVATION_REJECTED",
                "设备预约被驳回",
                "您预约的设备（设备号 " + rejected.deviceId() + "，预约号 " + rejected.id()
                        + "，时段 " + HHMM.format(rejected.startsAt()) + "–"
                        + HHMM.format(rejected.endsAt()) + "）已被驳回，理由："
                        + blankFallback(rejected.releaseReason(), "未填理由")
                        + "——请调整时段或换设备后重新预约",
                "RESERVATION_REJECTED:" + id);
        return rejected;
    }

    
    @Override
    public int sweepExpired() {
        int graceMinutes = configReader.reservationOverrunGraceMinutes();
        MapSqlParameterSource sweepParams = new MapSqlParameterSource("grace", graceMinutes);
        List<AppautoReservationView> expiringActive = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_appauto_reservation r"
                        + " JOIN tp_user u ON u.id = r.holder_user_id"
                        + " WHERE r.state = 'ACTIVE'"
                        + " AND r.ends_at < now() - make_interval(mins => :grace)",
                sweepParams, VIEW_MAPPER);
        int sweptActive = jdbc.update(
                "UPDATE tp_appauto_reservation SET state = 'EXPIRED',"
                        + " released_at = now(),"
                        + " release_reason = '预约时间窗已到期，系统自动释放设备占用',"
                        + " updated_at = now()"
                        + " WHERE state = 'ACTIVE'"
                        + " AND ends_at < now() - make_interval(mins => :grace)",
                sweepParams);
        List<AppautoReservationView> expiringPending = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_appauto_reservation r"
                        + " JOIN tp_user u ON u.id = r.holder_user_id"
                        + " WHERE r.state = 'PENDING_APPROVAL' AND r.ends_at < now()",
                new MapSqlParameterSource(), VIEW_MAPPER);
        int sweptPending = jdbc.update(
                "UPDATE tp_appauto_reservation SET state = 'EXPIRED',"
                        + " released_at = now(),"
                        + " release_reason = '预约待审批超过时间窗未处理，系统自动释放',"
                        + " updated_at = now()"
                        + " WHERE state = 'PENDING_APPROVAL' AND ends_at < now()",
                new MapSqlParameterSource());
        for (AppautoReservationView v : expiringActive) {
            notifyHolder(v.holderUserId(), v.projectId(), "RESERVATION_EXPIRED",
                    "设备预约已到期自动释放",
                    "您的设备预约（设备号 " + v.deviceId() + "，预约号 " + v.id()
                            + "，时段 " + HHMM.format(v.startsAt()) + "–"
                            + HHMM.format(v.endsAt()) + "）时间窗已到期，系统自动释放了设备占用",
                    "RESERVATION_EXPIRED:" + v.id());
        }
        for (AppautoReservationView v : expiringPending) {
            notifyHolder(v.holderUserId(), v.projectId(), "RESERVATION_EXPIRED",
                    "设备预约超窗未审已失效",
                    "您的设备预约（设备号 " + v.deviceId() + "，预约号 " + v.id()
                            + "，时段 " + HHMM.format(v.startsAt()) + "–"
                            + HHMM.format(v.endsAt()) + "）超过时间窗仍未审批，系统已自动释放",
                    "RESERVATION_EXPIRED:" + v.id());
        }
        return sweptActive + sweptPending;
    }

    
    @Override
    public List<AppautoReservationView> list(Long deviceId, Long projectId, Long batchId,
                                             boolean includeTerminal) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLUMNS)
                .append(" FROM tp_appauto_reservation r")
                .append(" JOIN tp_user u ON u.id = r.holder_user_id WHERE 1 = 1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (deviceId != null) {
            sql.append("AND r.device_id = :deviceId ");
            params.addValue("deviceId", deviceId);
        }
        if (projectId != null) {
            sql.append("AND r.project_id = :projectId ");
            params.addValue("projectId", projectId);
        }
        if (batchId != null) {
            sql.append("AND r.batch_id = :batchId ");
            params.addValue("batchId", batchId);
        }
        if (!includeTerminal) {
            sql.append("AND r.state IN ('PENDING_APPROVAL', 'ACTIVE') ");
        }
        sql.append("ORDER BY r.starts_at, r.id LIMIT 200");
        return jdbc.query(sql.toString(), params, VIEW_MAPPER);
    }

    
    @Override
    public AppautoReservationView bindBatch(long id, Long batchId) {
        AppautoReservationView current = get(id);
        if (!"PENDING_APPROVAL".equals(current.state()) && !"ACTIVE".equals(current.state())) {
            throw new ServiceException("该预约当前已是「" + stateLabel(current.state())
                    + "」状态，只有待审批或生效中的预约才能绑定批次（预约号 " + id + "）——请在「设备预约日历」该行编辑绑定，批次请到「执行批次」页选取",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        if (batchId != null) {
            Long batchProjectId;
            try {
                batchProjectId = jdbc.queryForObject(
                        "SELECT project_id FROM tp_exec_batch WHERE id = :id",
                        new MapSqlParameterSource("id", batchId), Long.class);
            } catch (org.springframework.dao.EmptyResultDataAccessException e) {
                throw new ServiceException("要绑定的批次不存在或已被删除（批次号 " + batchId
                        + "），请到「执行批次」页确认该批次仍在清单", BizErrorCode.NOT_FOUND);
            }
            if (current.projectId() != null && batchProjectId != null
                    && !current.projectId().equals(batchProjectId)) {
                throw new ServiceException("批次与预约不属于同一项目（预约归属项目号 "
                        + current.projectId() + "，批次归属项目号 " + batchProjectId
                        + "），无法绑定——请在顶栏切到预约所属项目后，到「执行批次」页选同项目批次", BizErrorCode.RESOURCE_CONFLICT);
            }
        }
        int flipped = jdbc.update(
                "UPDATE tp_appauto_reservation SET batch_id = :batchId, updated_at = now() "
                        + "WHERE id = :id AND state IN ('PENDING_APPROVAL', 'ACTIVE')",
                new MapSqlParameterSource("id", id).addValue("batchId", batchId));
        if (flipped == 0) {
            AppautoReservationView raced = get(id);
            throw new ServiceException("该预约当前已是「" + stateLabel(raced.state())
                    + "」状态，只有待审批或生效中的预约才能绑定批次（预约号 " + id + "）——请在「设备预约日历」该行编辑绑定，批次请到「执行批次」页选取",
                    BizErrorCode.STATE_MACHINE_REJECT);
        }
        return get(id);
    }

    private void validate(AppautoReservationInput input) {
        if (input.startsAt() == null || input.endsAt() == null) {
            throw new ServiceException("预约的开始时间与结束时间都必填（时间窗是互斥判定的唯一依据）——请在「设备预约日历」点「新建预约」补全时间窗",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!input.endsAt().isAfter(input.startsAt())) {
            throw new ServiceException("预约结束时间必须晚于开始时间（收到的窗是 "
                    + input.startsAt() + " 至 " + input.endsAt() + "，倒置或零长度窗无法成立）——请在「设备预约日历」点「新建预约」重新圈选时间窗",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.holderUserId() <= 0) {
            throw new ServiceException("预约人必填（冲突提示需要点名预约人）——请在「新建预约」表单的「预约人」字段选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private void requireDeviceExists(long deviceId) {
        Long found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_appauto_device WHERE id = :id",
                new MapSqlParameterSource("id", deviceId), Long.class);
        if (found == null || found == 0) {
            throw new ServiceException("要预约的设备不存在或已被删除（设备号 " + deviceId
                    + "），请到「设备墙」确认设备已登记后再选", BizErrorCode.NOT_FOUND);
        }
    }

    
    private void rejectIfConflicting(AppautoReservationInput input) {
        List<AppautoReservationConflictRow> conflicts = jdbc.query(
                "SELECT r.id, r.holder_user_id, r.starts_at, r.ends_at, r.reason,"
                        + " COALESCE(NULLIF(u.display_name, ''), u.username) AS holder_name,"
                        + " (SELECT MAX(l.expires_at) FROM tp_appauto_lease_run l"
                        + "  WHERE l.device_id = r.device_id AND l.state = 'ACTIVE')"
                        + "   AS active_lease_expires"
                        + " FROM tp_appauto_reservation r"
                        + " JOIN tp_user u ON u.id = r.holder_user_id"
                        + " WHERE r.device_id = :device_id"
                        + " AND r.state IN ('PENDING_APPROVAL', 'ACTIVE')"
                        + " AND :starts < r.ends_at AND r.starts_at < :ends"
                        + " ORDER BY r.starts_at LIMIT 1",
                new MapSqlParameterSource("device_id", input.deviceId())
                        .addValue("starts", input.startsAt())
                        .addValue("ends", input.endsAt()),
                (rs, rowNum) -> new AppautoReservationConflictRow(
                        rs.getLong("id"),
                        rs.getString("holder_name"),
                        rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("ends_at", LocalDateTime.class),
                        rs.getString("reason"),
                        rs.getObject("active_lease_expires", LocalDateTime.class)));
        if (conflicts.isEmpty()) {
            return;
        }
        AppautoReservationConflictRow c = conflicts.get(0);
        LocalDateTime estimate = c.endsAt();
        if (c.activeLeaseExpires() != null && c.activeLeaseExpires().isAfter(estimate)) {
            estimate = c.activeLeaseExpires();
        }
        throw new ServiceException("该设备在 " + HHMM.format(c.startsAt()) + "–"
                + HHMM.format(c.endsAt()) + " 时段已被 " + c.holderName()
                + " 预约（预约号 " + c.id() + "，原因：" + blankFallback(c.reason(), "未填事由")
                + "），预计释放时间 " + HHMM.format(estimate)
                + "——请在「设备预约日历」换时段重试，或到「设备墙」换设备", BizErrorCode.RESOURCE_CONFLICT);
    }

 
    private void notifyHolder(long userId, Long projectId, String eventType,
                              String title, String content, String dedupKey) {
        try {
            notificationService.create(new NotificationCreateInput(
                    userId, projectId, eventType, title, content, dedupKey));
        } catch (Exception e) {
            log.warn("设备预约站内信发送失败（接收人 id={} 事件={}）: {}",
                    userId, eventType, e.getMessage());
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String blankFallback(String s, String fallback) {
        String t = trimToNull(s);
        return t == null ? fallback : t;
    }

    private static String stateLabel(String state) {
        return switch (state) {
            case "PENDING_APPROVAL" -> "待审批";
            case "ACTIVE" -> "生效中";
            case "RELEASED" -> "已归还";
            case "EXPIRED" -> "已超时释放";
            case "CANCELLED" -> "已取消";
            case "REJECTED" -> "已驳回";
            default -> state;
        };
    }
}
