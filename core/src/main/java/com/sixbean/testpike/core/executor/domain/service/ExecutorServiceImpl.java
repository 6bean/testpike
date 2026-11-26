package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorSelfCheckView;
import com.sixbean.testpike.core.executor.domain.component.ExecutorProtocolCompatGate;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;

import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;


@Service
public class ExecutorServiceImpl implements ExecutorService {

    private static final Logger log = LoggerFactory.getLogger(ExecutorServiceImpl.class);

    
    private static final int DEFAULT_OFFLINE_SECONDS = 30;
    private static final int STATUS_ONLINE = 1;
    private static final int STATUS_UPGRADING = 2;
    private static final int STATUS_OFFLINE = 3;

    private static final String COLUMNS = """
            id, name, group_ids, env_ids, end_types, browsers, capacity, running_cnt, status,
            version, to_char(last_heartbeat, 'YYYY-MM-DD HH24:MI:SS'), capabilities::text,
            drain_requested
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final ConfigCacheHolder configCacheHolder;
    private final AdminAlertRecipients adminAlertRecipients;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ExecutorProtocolCompatGate compatGate;
    private final ClusterComponentService clusterComponentService;

    public ExecutorServiceImpl(JdbcTemplate jdbcTemplate,
                               NotificationService notificationService,
                               ConfigCacheHolder configCacheHolder,
                               AdminAlertRecipients adminAlertRecipients,
                               com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                               ExecutorProtocolCompatGate compatGate,
                               ClusterComponentService clusterComponentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.configCacheHolder = configCacheHolder;
        this.adminAlertRecipients = adminAlertRecipients;
        this.objectMapper = objectMapper;
        this.compatGate = compatGate;
        this.clusterComponentService = clusterComponentService;
    }

    @Override
    @Transactional
    public long register(ExecutorRegisterInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("执行器名称不能为空——请在执行器启动配置中填写名称后重新注册"
                    + "（参数 name，示例：runner-sh-01）", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.name().length() > 50) {
            throw new ServiceException("执行器名称不能超过 50 字——请缩短名称后重新注册（示例：runner-sh-01）",
                BizErrorCode.VALIDATION_ERROR);
        }
        compatGate.assertRegisterAllowed(input.name(), input.version());
        int capacity = input.capacity() > 0 ? input.capacity() : 2;
        String capabilitiesJson = writeCapabilities(input.capabilities());
        Long existing = jdbcTemplate.query("SELECT id FROM tp_executor WHERE name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, input.name());
        if (existing != null) {
            jdbcTemplate.update("""
                    UPDATE tp_executor SET group_ids = ?, env_ids = ?, end_types = ?, browsers = ?,
                           capacity = ?, running_cnt = ?, status = ?, version = ?,
                           capabilities = ?::jsonb,
                           last_heartbeat = now(), updated_at = now() WHERE id = ?
                    """, input.groupIds(), input.envIds(), input.endTypes(), input.browsers(),
                    capacity, input.runningTasks(), STATUS_ONLINE, input.version(),
                    capabilitiesJson, existing);
            return existing;
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_executor (name, group_ids, env_ids, end_types, browsers,
                                         capacity, running_cnt, status, version, capabilities,
                                         last_heartbeat)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now()) RETURNING id
                """, Long.class, input.name(), input.groupIds(), input.envIds(),
                input.endTypes() == null ? "web" : input.endTypes(),
                input.browsers() == null ? "chromium" : input.browsers(),
                capacity, input.runningTasks(), STATUS_ONLINE, input.version(), capabilitiesJson);
        return id;
    }

 
    private String writeCapabilities(Map<String, Object> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (Exception e) {
            log.warn("执行器 capabilities 序列化失败，按未上报落库: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    @Transactional
    public void heartbeat(long executorId, int runningTasks) {
        Integer prevStatus = jdbcTemplate.query("SELECT status FROM tp_executor WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, executorId);
        if (prevStatus == null) {
            throw new ServiceException("执行器未注册——请先由执行器进程完成一次注册再发心跳"
                    + "（可在「执行器池」核对是否在册）", BizErrorCode.NOT_FOUND);
        }
        jdbcTemplate.update("""
                UPDATE tp_executor SET running_cnt = ?, status = ?,
                       last_heartbeat = now(), updated_at = now() WHERE id = ?
                """, Math.max(0, runningTasks), STATUS_ONLINE, executorId);
        String[] row = jdbcTemplate.query("SELECT name, version FROM tp_executor WHERE id = ?",
                rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null, executorId);
        if (row != null) {
            compatGate.alertIfIncompatible(row[0], row[1]);
        }
        if (prevStatus == STATUS_OFFLINE) {
            String name = jdbcTemplate.query("SELECT name FROM tp_executor WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, executorId);
            jdbcTemplate.update("""
                    UPDATE tp_notification SET dedup_key = dedup_key || ':recovered'
                    WHERE dedup_key = ?
                    """, "EXECUTOR_OFFLINE:" + executorId);
            notifyAdmins("EXECUTOR_ONLINE", "执行器已恢复",
                    "执行器 " + name + "（id=" + executorId + "）已恢复心跳，重新参与任务派发。",
                    "EXECUTOR_ONLINE:" + executorId);
        }
    }

    @Override
    @Transactional
    public int detectOfflineAndNotify() {
        int offlineSeconds = offlineSeconds();
        List<Long> stale = jdbcTemplate.queryForList("""
                SELECT id FROM tp_executor
                WHERE status = 1 AND last_heartbeat < now() - (? || ' seconds')::interval
                """, Long.class, offlineSeconds);
        for (long executorId : stale) {
            jdbcTemplate.update("UPDATE tp_executor SET status = ?, updated_at = now() WHERE id = ?",
                    STATUS_OFFLINE, executorId);
            String name = jdbcTemplate.query("SELECT name FROM tp_executor WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, executorId);
            notifyAdmins("EXECUTOR_OFFLINE", "执行器离线告警",
                    "执行器 " + name + "（id=" + executorId + "）已超过 " + offlineSeconds
                            + " 秒未上报心跳，已标记离线，任务不再派发；恢复心跳后将自动通知。",
                    "EXECUTOR_OFFLINE:" + executorId);
        }
        return stale.size();
    }

    

 
    private void notifyAdmins(String eventType, String title, String content, String dedupKey) {
        List<Long> admins = adminAlertRecipients.adminUserIds();
        for (Long adminId : admins) {
            try {
                notificationService.create(new NotificationCreateInput(
                        adminId, null, eventType, title, content, dedupKey));
            } catch (Exception e) {
                log.warn("执行器通知发送失败（接收人 id={}）: {}", adminId, e.getMessage());
            }
        }
    }

    
    private int offlineSeconds() {
        try {
            String value = configCacheHolder.get("testpike.executor.offline-seconds");
            return value == null ? DEFAULT_OFFLINE_SECONDS : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return DEFAULT_OFFLINE_SECONDS;
        }
    }

    @Override
    public List<ExecutorInfo> listAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS
                + " FROM tp_executor ORDER BY id DESC", this::mapRow);
    }

    @Override
    public ExecutorInfo get(long executorId) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_executor WHERE id = ?",
                this::mapRow, executorId).stream().findFirst().orElse(null);
    }

    @Override
    @Transactional
    public boolean isOnline(long executorId) {
        ExecutorInfo info = get(executorId);
        if (info == null) {
            return false;
        }
        if (info.status() != STATUS_ONLINE) {
            return false;
        }
        boolean fresh = jdbcTemplate.queryForObject("""
                SELECT last_heartbeat >= now() - (? || ' seconds')::interval
                FROM tp_executor WHERE id = ?
                """, Boolean.class, offlineSeconds(), executorId);
        if (!Boolean.TRUE.equals(fresh)) {
            jdbcTemplate.update("UPDATE tp_executor SET status = ?, updated_at = now() WHERE id = ?",
                    STATUS_OFFLINE, executorId);
            return false;
        }
        return compatGate.dispatchable(info.version());
    }

    @Override
    public boolean hasOnlineExecutor() {
        return !listOnline().isEmpty();
    }

    @Override
    public List<ExecutorInfo> listOnline() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM tp_executor"
                        + " WHERE status = ? AND last_heartbeat >= now() - (? || ' seconds')::interval"
                        + " ORDER BY id",
                this::mapRow, STATUS_ONLINE, offlineSeconds())
                .stream().filter(e -> compatGate.dispatchable(e.version())).toList();
    }

    @Override
    public List<Map<String, Object>> onlineCapabilities() {
        return listOnline().stream().map(ExecutorInfo::capabilities).toList();
    }

    @Override
    @Transactional
    public void releaseCapacity(long executorId) {
        jdbcTemplate.update("""
                UPDATE tp_executor SET running_cnt = GREATEST(0, running_cnt - 1), updated_at = now()
                WHERE id = ?
                """, executorId);
    }

    @Override
    public ExecutorSelfCheckView selfCheck(long executorId) {
        ExecutorInfo info = get(executorId);
        if (info == null) {
            throw new ServiceException("执行器不存在（id=" + executorId
                    + "），请确认已从「执行器池」选择有效机器后重试"
                    + "（示例：从机器列表行进入自检页）", BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> caps = info.capabilities() == null ? Map.of() : info.capabilities();
        boolean reported = !caps.isEmpty();
        String hostOs = reported ? text(caps.get("hostOs")) : null;

        Map<String, Object> tools = asMap(caps.get("tools"));
        List<String> missingTools = tools.entrySet().stream()
                .filter(e -> !truthy(e.getValue())).map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());

        Long deviceCount = null;
        if (hostOs != null && !hostOs.isBlank()) {
            deviceCount = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM tp_appauto_device
                    WHERE labels @> (?::jsonb)
                    """, Long.class, "[\"" + hostOs.toLowerCase(java.util.Locale.ROOT)
                    + "\", \"source:auto\"]");
        }
        List<ClusterComponentInfo> components =
                clusterComponentService.listByNode(String.valueOf(executorId));

        return new ExecutorSelfCheckView(info.id(), info.name(), info.version(),
                info.status(), statusText(info.status()), info.lastHeartbeat(), reported,
                hostOs == null || hostOs.isBlank() ? null : hostOs,
                tools, missingTools, asMap(caps.get("runtimes")),
                asStringList(caps.get("engines")), asStringList(caps.get("platforms")),
                asStringList(caps.get("deviceKinds")), deviceCount, components);
    }

 
    private static String statusText(int status) {
        return switch (status) {
            case STATUS_ONLINE -> "在线";
            case STATUS_UPGRADING -> "升级中";
            case STATUS_OFFLINE -> "离线";
            default -> "未知";
        };
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private ExecutorInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExecutorInfo(rs.getLong("id"), rs.getString("name"),
                rs.getString("group_ids"), rs.getString("env_ids"),
                rs.getString("end_types"), rs.getString("browsers"),
                rs.getInt("capacity"), rs.getInt("running_cnt"),
                rs.getInt("status"), rs.getString("version"), rs.getString(11),
                readCapabilities(rs.getString(12)), rs.getBoolean(13));
    }

    
    @SuppressWarnings("unchecked")
    private Map<String, Object> readCapabilities(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
