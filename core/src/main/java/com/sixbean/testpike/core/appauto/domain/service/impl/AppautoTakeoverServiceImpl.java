package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.core.appauto.api.dto.TakeoverCreateInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoTakeoverInputQueue;
import com.sixbean.testpike.core.appauto.domain.service.AppautoTakeoverService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class AppautoTakeoverServiceImpl implements AppautoTakeoverService {

    private static final Logger log = LoggerFactory.getLogger(AppautoTakeoverServiceImpl.class);

 
    private static final int REASON_MAX_LEN = 200;

    private static final String SELECT_COLUMNS = """
            id, device_id, project_id, task_id, requester_id, occupier_id,
            state, reason, decided_by, decided_at, expires_at, recording_ref, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoConfigReader configReader;
    private final NotificationService notificationService;
    private final AppautoTakeoverInputQueue inputQueue;

    public AppautoTakeoverServiceImpl(NamedParameterJdbcTemplate jdbc,
                                      AppautoConfigReader configReader,
                                      NotificationService notificationService,
                                      AppautoTakeoverInputQueue inputQueue) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.notificationService = notificationService;
        this.inputQueue = inputQueue;
    }

    @Override
    @Audit(objectType = "appauto_takeover", action = "request", objectId = "#result.id")
    @Transactional
    public AppautoTakeoverView request(TakeoverCreateInput input) {
        Long projectId = jdbc.query("SELECT project_id FROM tp_appauto_device WHERE id = :devId",
                new MapSqlParameterSource("devId", input.deviceId()),
                rs -> rs.next() ? rs.getLong(1) : null);
        if (projectId == null) {
            throw new ServiceException("设备不存在或已被移除——请先在「APP 自动化 → 设备台账」确认设备",
                    BizErrorCode.NOT_FOUND);
        }
        Long occupierId = resolveOccupier(input.deviceId());
        if (occupierId == null) {
            throw new ServiceException("该设备当前没有进行中的占用会话（空闲）——无需接管，"
                    + "请在「设备墙」对该设备直接发起「执行监看」或派发执行批次", BizErrorCode.RESOURCE_CONFLICT);
        }
        Long inflight = jdbc.query("""
                SELECT id FROM tp_appauto_takeover_request
                WHERE device_id = :dev AND requester_id = :req AND state = 'pending'
                AND expires_at > now()
                """, new MapSqlParameterSource("dev", input.deviceId())
                .addValue("req", input.requesterId()),
                rs -> rs.next() ? rs.getLong(1) : null);
        if (inflight != null) {
            throw new ServiceException("你已有一条对该设备的接管申请正在等待对方确认（同设备在途仅一条）"
                    + "——请先联系占用者在「执行监看」处理该申请，或待申请到期后重新点击「申请接管」",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        LocalDateTime expiresAt = LocalDateTime.now()
                .plusSeconds(configReader.takeoverRequestTtlSeconds());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("dev", input.deviceId())
                .addValue("pid", projectId)
                .addValue("task", input.taskId())
                .addValue("req", input.requesterId())
                .addValue("occ", occupierId)
                .addValue("reason", trimReason(input.reason()))
                .addValue("exp", expiresAt);
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_appauto_takeover_request
                    (device_id, project_id, task_id, requester_id, occupier_id, state,
                     reason, expires_at)
                VALUES (:dev, :pid, :task, :req, :occ, 'pending', :reason, :exp)
                RETURNING id
                """, params, Long.class);
        long requestId = id == null ? 0L : id;
        notify(occupierId, projectId, "TAKEOVER_REQUEST",
                "有人申请接管你占用的设备",
                "用户申请在你的设备会话期间直接操作设备（接管不改占用归属，你的跑批任务不受影响，"
                        + "但接管期间的画面操作可能干扰当前会话）。请确认或拒绝；同意即生效，"
                        + "超时未处理申请自动作废。",
                "TAKEOVER_REQ:" + requestId);
        return get(requestId);
    }

    @Override
    @Audit(objectType = "appauto_takeover", action = "agree", objectId = "#requestId")
    public AppautoTakeoverView agree(long requestId, long deciderId) {
        AppautoTakeoverView current = get(requestId);
        requirePending(current, "同意");
        requireOccupier(current, deciderId, "同意");
        flipDecision(requestId, STATE_ACTIVE, deciderId);
        receipt(current, "已同意你的接管申请", "对方已同意，现在可以在设备页进行接管操作。"
                + "操作全程留痕，结束接管后画面自动归还原会话。");
        return get(requestId);
    }

    @Override
    @Audit(objectType = "appauto_takeover", action = "reject", objectId = "#requestId")
    public AppautoTakeoverView reject(long requestId, long deciderId) {
        AppautoTakeoverView current = get(requestId);
        requirePending(current, "驳回");
        requireOccupier(current, deciderId, "驳回");
        flipDecision(requestId, STATE_REJECTED, deciderId);
        receipt(current, "已拒绝你的接管申请", "对方拒绝了本次接管申请。如确有协作需要，"
                + "建议先与对方沟通后再发起。");
        return get(requestId);
    }

    @Override
    @Audit(objectType = "appauto_takeover", action = "force", objectId = "#requestId")
    public AppautoTakeoverView force(long requestId, long adminOperatorId) {
        AppautoTakeoverView current = get(requestId);
        requirePending(current, "强制接管");
        flipDecision(requestId, STATE_FORCED, adminOperatorId);
        notify(current.occupierId(), current.projectId(), "TAKEOVER_FORCED",
                "管理员已强制接管你的设备会话",
                "管理员对你的设备会话执行了强制接管（接管不改占用归属，你的跑批任务不受影响，"
                        + "但接管期间的画面操作可能干扰当前会话）。原因与操作全程留痕可查。",
                "TAKEOVER_FORCED:" + requestId);
        return get(requestId);
    }

    @Override
    @Audit(objectType = "appauto_takeover", action = "end", objectId = "#requestId")
    public AppautoTakeoverView end(long requestId, long operatorId) {
        AppautoTakeoverView current = get(requestId);
        if (!STATE_ACTIVE.equals(current.state()) && !STATE_FORCED.equals(current.state())) {
            throw new ServiceException("该接管不在进行中（当前状态：" + current.state()
                    + "）——请仅对进行中的接管在「执行监看」点击「结束接管」", BizErrorCode.RESOURCE_CONFLICT);
        }
        int updated = jdbc.update("""
                UPDATE tp_appauto_takeover_request
                SET state = :to, decided_by = :dec, decided_at = now()
                WHERE id = :id AND state = :from
                """, new MapSqlParameterSource("id", requestId)
                .addValue("to", STATE_ENDED)
                .addValue("dec", operatorId)
                .addValue("from", current.state()));
        if (updated > 0) {
            inputQueue.clear(requestId);
        }
        return get(requestId);
    }

    @Override
    public AppautoTakeoverView get(long requestId) {
        return readRow(requestId);
    }


    @Override
    public String inputQueueKey(long requestId) {
        return AppautoTakeoverInputQueue.inputKey(requestId);
    }

    @Override
    public void enqueueInput(long requestId, long operatorId, List<Map<String, Object>> commands) {
        AppautoTakeoverView current = get(requestId);
        requireChannelOpen(current, "下发输入指令");
        if (current.requesterId() != operatorId) {
            throw new ServiceException("仅接管申请人可以向本次接管下发操作指令"
                    + "——请用发起「申请接管」的账号操作，或重新点击「申请接管」",
                    BizErrorCode.FORBIDDEN);
        }
        if (commands == null || commands.isEmpty()) {
            return;
        }
        int cap = inputQueue.queueMax();
        long inflight = inputQueue.size(requestId);
        if (inflight + commands.size() > cap) {
            throw new ServiceException("该接管的待注入指令已排队 " + inflight + " 条（上限 "
                    + cap + " 条）——本批 " + commands.size() + " 条未受理，请等画面操作消化后再发"
                    + "（连点过快会被丢弃，整批拒禁部分入账）——请在「执行监看」画面操作消化后再发送下一批",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        inputQueue.enqueue(requestId, commands);
    }

    @Override
    public List<Map<String, Object>> pollInputCommands(long requestId, long executorId, int limit) {
        AppautoTakeoverView current = get(requestId);
        requireChannelOpen(current, "领取输入指令");
        Long holder = activeHolderExecutorId(current.deviceId());
        if (holder == null || !holder.equals(executorId)) {
            throw new ServiceException("领取输入指令的执行器与设备当前持有的执行器不一致"
                    + "（输入只能注入到真正握着设备的机器，防串机误注）", BizErrorCode.FORBIDDEN); // error-guidance-keep: executor-facing claim protocol error, consumer is the executor link (no on-screen entry; holder authority = lease rows)
        }
        return inputQueue.drain(requestId, clampLimit(limit));
    }

    @Override
    public List<Map<String, Object>> pollInputCommandsForExecutor(long executorId, int limit) {
        List<Long> requestIds = jdbc.query("""
                SELECT t.id FROM tp_appauto_takeover_request t
                JOIN tp_appauto_lease_run r
                  ON r.device_id = t.device_id AND r.state = 'ACTIVE'
                 AND r.holder_executor_id = :holder AND r.expires_at > now()
                WHERE t.state IN ('active', 'forced')
                ORDER BY t.id
                """, new MapSqlParameterSource("holder", String.valueOf(executorId)),
                (rs, rowNum) -> rs.getLong(1));
        int budget = clampLimit(limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long rid : requestIds) {
            if (out.size() >= budget) {
                break;
            }
            out.addAll(inputQueue.drain(rid, budget - out.size()));
        }
        return out;
    }


    
    private void requireChannelOpen(AppautoTakeoverView current, String verb) {
        if (!STATE_ACTIVE.equals(current.state()) && !STATE_FORCED.equals(current.state())) {
            throw new ServiceException("接管尚未生效（当前状态：" + current.state()
                    + "）——请等占用者「同意接管」或管理员「强制接管」生效后再" + verb,
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    
    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 50);
    }

    
    private Long activeHolderExecutorId(long deviceId) {
        String holder = jdbc.query("""
                SELECT holder_executor_id FROM tp_appauto_lease_run
                WHERE device_id = :dev AND state = 'ACTIVE' AND expires_at > now()
                ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource("dev", deviceId),
                rs -> rs.next() ? rs.getString(1) : null);
        if (holder == null || holder.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(holder.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }


    
    private Long resolveOccupier(long deviceId) {
        return jdbc.query("""
                SELECT b.created_by FROM tp_appauto_lease_run r
                JOIN tp_exec_batch b ON b.id = r.batch_id
                WHERE r.device_id = :dev AND r.state = 'ACTIVE'
                ORDER BY r.id DESC LIMIT 1
                """, new MapSqlParameterSource("dev", deviceId),
                rs -> rs.next() ? rs.getLong(1) : null);
    }

    
    private AppautoTakeoverView readRow(long requestId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", requestId);
        AppautoTakeoverView view = jdbc.query(
                "SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_takeover_request WHERE id = :id
                """, params, rs -> rs.next() ? toView(rs) : null);
        if (view == null) {
            throw new ServiceException("接管申请不存在或已被移除（请刷新「执行监看」查看最新状态）",
                    BizErrorCode.NOT_FOUND);
        }
        if (STATE_PENDING.equals(view.state())
                && view.expiresAt() != null && view.expiresAt().isBefore(LocalDateTime.now())) {
            jdbc.update("""
                    UPDATE tp_appauto_takeover_request SET state = :to
                    WHERE id = :id AND state = :from AND expires_at <= now()
                    """, new MapSqlParameterSource("id", requestId)
                    .addValue("to", STATE_EXPIRED).addValue("from", STATE_PENDING));
            return readRow(requestId);
        }
        return view;
    }

    private void requirePending(AppautoTakeoverView current, String verb) {
        if (STATE_EXPIRED.equals(current.state())) {
            throw new ServiceException("该接管申请已过期作废——请在「执行监看」重新点击「申请接管」",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        if (!STATE_PENDING.equals(current.state())) {
            throw new ServiceException("该接管申请已被处理（当前状态：" + current.state()
                    + "），本次" + verb + "未生效（请到「执行监看」刷新查看最新状态）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    
    private void requireOccupier(AppautoTakeoverView current, long deciderId, String verb) {
        if (current.occupierId() != deciderId) {
            throw new ServiceException("仅设备当前占用会话的负责人可以" + verb
                    + "接管申请；如需强制接管请联系管理员在「执行监看」使用「强制接管」",
                    BizErrorCode.FORBIDDEN);
        }
    }

    
    private void flipDecision(long requestId, String toState, long deciderId) {
        jdbc.update("""
                UPDATE tp_appauto_takeover_request
                SET state = :to, decided_by = :dec, decided_at = now()
                WHERE id = :id AND state = 'pending'
                """, new MapSqlParameterSource("id", requestId)
                .addValue("to", toState).addValue("dec", deciderId));
    }

    private void receipt(AppautoTakeoverView current, String head, String detail) {
        notify(current.requesterId(), current.projectId(), "TAKEOVER_REQUEST",
                "接管申请" + head, "设备（编号 " + current.deviceId() + "）的占用者" + detail,
                "TAKEOVER_DECIDED:" + current.id());
    }

    private void notify(long userId, long projectId, String eventType, String title,
                        String content, String dedupKey) {
        try {
            notificationService.create(new NotificationCreateInput(
                    userId, projectId, eventType, title, content, dedupKey));
        } catch (Exception e) {
            log.warn("接管站内信发送失败（接收人 id={} 事件={}）: {}", userId, eventType, e.getMessage());
        }
    }

    private static String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String cleaned = reason.trim();
        return cleaned.length() <= REASON_MAX_LEN ? cleaned : cleaned.substring(0, REASON_MAX_LEN);
    }

    private static AppautoTakeoverView toView(ResultSet rs) throws SQLException {
        return new AppautoTakeoverView(
                rs.getLong("id"),
                rs.getLong("device_id"),
                rs.getLong("project_id"),
                rs.getObject("task_id", Long.class),
                rs.getLong("requester_id"),
                rs.getLong("occupier_id"),
                rs.getString("state"),
                rs.getString("reason"),
                rs.getObject("decided_by", Long.class),
                rs.getTimestamp("decided_at") == null ? null
                        : rs.getTimestamp("decided_at").toLocalDateTime(),
                rs.getTimestamp("expires_at").toLocalDateTime(),
                rs.getString("recording_ref"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
