package com.sixbean.testpike.core.monitor.domain.service.impl;

import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.monitor.domain.component.MonitorAlertCopy;
import com.sixbean.testpike.core.monitor.domain.component.MonitorConfigReader;
import com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


@Service
public class MonitorEpisodeServiceImpl implements MonitorEpisodeService {

    private static final Logger log = LoggerFactory.getLogger(MonitorEpisodeServiceImpl.class);

    static final String STATE_UNKNOWN = "UNKNOWN";
    static final String STATE_UP = "UP";
    static final String STATE_DOWN = "DOWN";
    static final String STATE_RECOVERING = "RECOVERING";
    static final String STATE_FLAPPING = "FLAPPING";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate jdbc;
    private final MonitorConfigReader configReader;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final AdminAlertRecipients adminAlertRecipients;

    
    private volatile long lastHeartbeatEpochSecond;
    
    private final AtomicBoolean lostAlerted = new AtomicBoolean(false);

    public MonitorEpisodeServiceImpl(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate jdbc,
                                     MonitorConfigReader configReader,
                                     NotificationService notificationService, ProjectOwners projectOwners,
                                     AdminAlertRecipients adminAlertRecipients) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    @Override
    public String evaluate(long targetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.project_id, COALESCE(p.name, '') AS project_name, t.name, "
                        + "t.target_type, t.last_state, t.consecutive_failures, t.consecutive_successes, "
                        + "t.silence_until IS NOT NULL AND t.silence_until > now() AS silenced, "
                        + "(SELECT count(*) FROM tp_monitor_check_log l WHERE l.target_id = t.id "
                        + "AND l.checked_at >= now() - make_interval(mins => :slowWindow) "
                        + "AND l.outcome <> 'deferred') AS window_samples, "
                        + "(SELECT count(*) FROM tp_monitor_check_log l WHERE l.target_id = t.id "
                        + "AND l.checked_at >= now() - make_interval(mins => :slowWindow) "
                        + "AND l.outcome = 'ok') AS window_oks "
                        + "FROM tp_monitor_target t LEFT JOIN tp_project p ON p.id = t.project_id "
                        + "WHERE t.id = :id",
                new MapSqlParameterSource("id", targetId)
                        .addValue("slowWindow", configReader.intValue(MonitorConfigReader.KEY_SLOW_WINDOW)));
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> t = rows.get(0);
        String state = (String) t.get("last_state");
        boolean silenced = Boolean.TRUE.equals(t.get("silenced"));
        int failures = ((Number) t.get("consecutive_failures")).intValue();
        int successes = ((Number) t.get("consecutive_successes")).intValue();

        if ("DOWN".equals(state) || "RECOVERING".equals(state) || "FLAPPING".equals(state)) {
            return evaluateDownSide(t, silenced, failures, successes, state);
        }
        return evaluateUpSide(t, silenced, failures, successes);
    }


    private String evaluateDownSide(Map<String, Object> target, boolean silenced,
                                    int failures, int successes, String state) {
        long targetId = num(target.get("id"));
        Map<String, Object> open = findOpenEpisode(targetId);
        if (failures > 0) {
            if (open != null) {
                long episodeId = num(open.get("id"));
                int failureCount = bumpEpisodeFailure(targetId, episodeId);
                if (!silenced && renotifyDue(episodeId)) {
                    renotifyDown(target, episodeId, failureCount);
                }
            }
            return state;
        }
        int need = configReader.intValue(MonitorConfigReader.KEY_RECOVER_SUCCESSES);
        if (successes >= need) {
            closeEpisodeAndRecover(target, open, silenced);
            return transition(targetId, STATE_UP);
        }
        return transition(targetId, STATE_RECOVERING);
    }

    private int bumpEpisodeFailure(long targetId, long episodeId) {
        jdbcTemplate.update("UPDATE tp_monitor_episode SET failure_count = failure_count + 1 "
                + "WHERE id = ?", episodeId);
        Integer n = jdbcTemplate.queryForObject(
                "SELECT failure_count FROM tp_monitor_episode WHERE id = ?", Integer.class, episodeId);
        log.debug("监控目标 {} 进行中 episode {} 连败累计 {}", targetId, episodeId, n);
        return n == null ? 0 : n;
    }

    
    private boolean renotifyDue(long episodeId) {
        Integer due = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN last_notified_at IS NULL OR last_notified_at < now() - "
                        + "make_interval(mins => ?) THEN 1 ELSE 0 END FROM tp_monitor_episode WHERE id = ?",
                Integer.class, configReader.intValue(MonitorConfigReader.KEY_RENOTIFY_INTERVAL), episodeId);
        return due != null && due == 1;
    }

    
    private void renotifyDown(Map<String, Object> target, long episodeId, int failureCount) {
        long targetId = num(target.get("id"));
        String cause = lastCause(targetId);
        long stamp = System.currentTimeMillis() / 60000;
        for (Long ownerId : projectOwners.resolve(num(target.get("project_id")))) {
            createQuietly(ownerId, num(target.get("project_id")), "MONITOR_DOWN",
                    MonitorAlertCopy.downTitle((String) target.get("name")),
                    MonitorAlertCopy.downContent((String) target.get("project_name"),
                            (String) target.get("name"), cause, failureCount),
                    "MONITOR_DOWN:" + targetId + ":" + ownerId + ":rn" + stamp);
        }
        jdbcTemplate.update("UPDATE tp_monitor_episode SET notice_count = notice_count + 1, "
                + "last_notified_at = now() WHERE id = ?", episodeId);
    }

    private void closeEpisodeAndRecover(Map<String, Object> target, Map<String, Object> open,
                                        boolean silenced) {
        long targetId = num(target.get("id"));
        if (open != null) {
            jdbcTemplate.update("UPDATE tp_monitor_episode SET recovered_at = now() WHERE id = ?",
                    num(open.get("id")));
        }
        List<Long> recipients = jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM tp_notification WHERE dedup_key LIKE ? "
                        + "AND dedup_key NOT LIKE '%:recovered' ORDER BY user_id",
                Long.class, "MONITOR_DOWN:" + targetId + ":%");
        renameActiveDownKeys(targetId);
        if (silenced) {
            return;
        }
        if (recipients.isEmpty()) {
            recipients = projectOwners.resolve(num(target.get("project_id")));
        }
        for (Long ownerId : recipients) {
            createQuietly(ownerId, num(target.get("project_id")), "MONITOR_RECOVERED",
                    "监控已恢复：「" + target.get("name") + "」",
                    MonitorAlertCopy.recoveredContent((String) target.get("name")),
                    "MONITOR_RECOVERED:" + targetId + ":" + ownerId + ":"
                            + System.currentTimeMillis() / 60000);
        }
    }

    private void renameActiveDownKeys(long targetId) {
        jdbcTemplate.update("UPDATE tp_notification SET dedup_key = dedup_key || ':recovered' "
                        + "WHERE dedup_key LIKE ? AND dedup_key NOT LIKE '%:recovered'",
                "MONITOR_DOWN:" + targetId + ":%");
    }


    private String evaluateUpSide(Map<String, Object> target, boolean silenced,
                                  int failures, int successes) {
        long targetId = num(target.get("id"));
        if (failures <= 0) {
            String state = (String) target.get("last_state");
            if (STATE_UNKNOWN.equals(state) && successes > 0) {
                return transition(targetId, STATE_UP);
            }
            return state;
        }
        if (!breachDetected(target, failures)) {
            return (String) target.get("last_state");
        }
        openDownEpisode(targetId);
        if (silenced) {
            return transition(targetId, STATE_DOWN);
        }
        int flaps = countEpisodesInWindow(targetId,
                configReader.intValue(MonitorConfigReader.KEY_FLAP_WINDOW));
        if (flaps >= configReader.intValue(MonitorConfigReader.KEY_FLAP_EPISODES)) {
            notifyFlapping(target, flaps, failures);
            return transition(targetId, STATE_FLAPPING);
        }
        notifyDown(target, failures);
        return transition(targetId, STATE_DOWN);
    }

    
    private boolean breachDetected(Map<String, Object> target, int failures) {
        if (failures >= configReader.intValue(MonitorConfigReader.KEY_FAST_FAILS)) {
            return true;
        }
        if (!"ONLINE".equalsIgnoreCase((String) target.get("target_type"))) {
            return false;
        }
        long samples = num(target.get("window_samples"));
        long oks = num(target.get("window_oks"));
        int minSamples = configReader.intValue(MonitorConfigReader.KEY_SLOW_MIN_SAMPLES);
        int rateThreshold = configReader.intValue(MonitorConfigReader.KEY_SLOW_ONLINE_RATE);
        return samples >= minSamples && oks * 100 < samples * rateThreshold;
    }

    private void openDownEpisode(long targetId) {
        jdbc.update("INSERT INTO tp_monitor_episode (target_id, started_at, failure_count, "
                        + "notice_count, last_notified_at, cause_summary) "
                        + "VALUES (:tid, now(), 1, 0, NULL, :cause)",
                new MapSqlParameterSource("tid", targetId)
                        .addValue("cause", trunc(lastCause(targetId), 300)));
    }

    private void notifyDown(Map<String, Object> target, int failures) {
        long targetId = num(target.get("id"));
        String content = MonitorAlertCopy.downContent((String) target.get("project_name"),
                (String) target.get("name"), lastCause(targetId), failures);
        for (Long ownerId : projectOwners.resolve(num(target.get("project_id")))) {
            createQuietly(ownerId, num(target.get("project_id")), "MONITOR_DOWN",
                    MonitorAlertCopy.downTitle((String) target.get("name")),
                    content, "MONITOR_DOWN:" + targetId + ":" + ownerId);
        }
        markNotified(targetId);
    }

    private void notifyFlapping(Map<String, Object> target, int flaps, int failures) {
        long targetId = num(target.get("id"));
        String content = MonitorAlertCopy.flappingContent((String) target.get("project_name"),
                (String) target.get("name"), flaps,
                configReader.intValue(MonitorConfigReader.KEY_FLAP_WINDOW));
        for (Long ownerId : projectOwners.resolve(num(target.get("project_id")))) {
            createQuietly(ownerId, num(target.get("project_id")), "MONITOR_FLAPPING",
                    "监控告警：目标反复抖动「" + target.get("name") + "」", content,
                    "MONITOR_FLAPPING:" + targetId + ":" + ownerId);
        }
        createDownFirstNotice(target, targetId, failures);
        markNotified(targetId);
    }

    private void createDownFirstNotice(Map<String, Object> target, long targetId, int failures) {
        for (Long ownerId : projectOwners.resolve(num(target.get("project_id")))) {
            createQuietly(ownerId, num(target.get("project_id")), "MONITOR_DOWN",
                    MonitorAlertCopy.downTitle((String) target.get("name")),
                    MonitorAlertCopy.downContent((String) target.get("project_name"),
                            (String) target.get("name"), lastCause(targetId), failures),
                    "MONITOR_DOWN:" + targetId + ":" + ownerId);
        }
    }

    private void markNotified(long targetId) {
        jdbcTemplate.update("UPDATE tp_monitor_episode SET notice_count = notice_count + 1, "
                + "last_notified_at = now() WHERE target_id = ? AND recovered_at IS NULL", targetId);
    }


    @Override
    public void markScanHeartbeat() {
        lastHeartbeatEpochSecond = System.currentTimeMillis() / 1000;
    }

    @Override
    public int alertIfStalled(int graceSeconds) {
        long heartbeat = lastHeartbeatEpochSecond;
        if (heartbeat == 0) {
            return 0;
        }
        long stalledSeconds = System.currentTimeMillis() / 1000 - heartbeat;
        if (stalledSeconds <= graceSeconds) {
            lostAlerted.set(false);
            return 0;
        }
        if (lostAlerted.getAndSet(true)) {
            return 0;
        }
        List<Long> admins = adminAlertRecipients.adminUserIds();
        long stamp = System.currentTimeMillis() / 60000;
        for (Long adminId : admins) {
            createQuietly(adminId, null, "MONITOR_LOST",
                    "监控中心失联：探测调度已停摆",
                    MonitorAlertCopy.lostContent((int) stalledSeconds),
                    "MONITOR_LOST:0:" + adminId + ":" + stamp);
        }
        return admins.size();
    }

    @Override
    public String configValue(String key) {
        return configReader.value(key);
    }


    
    private static final String OVERLAP_SQL = """
            SELECT e.id, e.target_id, t.name AS target_name, t.target_type, t.env_id,
                   e.started_at, e.recovered_at, e.failure_count, e.cause_summary
              FROM tp_monitor_episode e
              JOIN tp_monitor_target t ON t.id = e.target_id
             WHERE t.env_id = :envId
               AND e.started_at <= :winTo
               AND COALESCE(e.recovered_at, now()) >= :winFrom
             ORDER BY e.started_at DESC
            """;

    @Override
    public List<MonitorEpisodeView> findEpisodesOverlappingWindow(long envId,
                                                                  LocalDateTime from,
                                                                  LocalDateTime to) {
        if (from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("envId", envId)
                .addValue("winFrom", Timestamp.valueOf(from))
                .addValue("winTo", Timestamp.valueOf(to));
        return toViews(jdbc.queryForList(OVERLAP_SQL, params));
    }

    @Override
    public MonitorEpisodeView findOpenEpisodeByEnv(long envId) {
        List<MonitorEpisodeView> open = findEpisodesOverlappingWindow(envId,
                LocalDateTime.now(), LocalDateTime.now());
        return open.isEmpty() ? null : open.get(0);
    }

    
    private List<MonitorEpisodeView> toViews(List<Map<String, Object>> rows) {
        List<MonitorEpisodeView> views = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            LocalDateTime recoveredAt = toLocalDateTime(r.get("recovered_at"));
            views.add(new MonitorEpisodeView(
                    ((Number) r.get("id")).longValue(),
                    ((Number) r.get("target_id")).longValue(),
                    (String) r.get("target_name"),
                    (String) r.get("target_type"),
                    ((Number) r.get("env_id")).longValue(),
                    toLocalDateTime(r.get("started_at")),
                    recoveredAt,
                    recoveredAt == null,
                    ((Number) r.get("failure_count")).intValue(),
                    (String) r.get("cause_summary")));
        }
        return views;
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }


    private String transition(long targetId, String state) {
        jdbc.update("UPDATE tp_monitor_target SET last_state = :state, updated_at = now() "
                + "WHERE id = :id", new MapSqlParameterSource("state", state).addValue("id", targetId));
        return state;
    }

    private Map<String, Object> findOpenEpisode(long targetId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM tp_monitor_episode WHERE target_id = :tid "
                        + "AND recovered_at IS NULL ORDER BY started_at DESC LIMIT 1",
                new MapSqlParameterSource("tid", targetId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int countEpisodesInWindow(long targetId, int windowMinutes) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tp_monitor_episode WHERE target_id = :tid "
                        + "AND started_at >= now() - make_interval(mins => :w)",
                new MapSqlParameterSource("tid", targetId).addValue("w", windowMinutes),
                Integer.class);
        return n == null ? 0 : n;
    }

    private String lastCause(long targetId) {
        String cause = jdbcTemplate.queryForObject(
                "SELECT COALESCE(last_cause, '探测失败（无原因留痕）') FROM tp_monitor_target WHERE id = ?",
                String.class, targetId);
        return cause;
    }

    
    private void createQuietly(long userId, Long projectId, String eventType, String title,
                               String content, String dedupKey) {
        try {
            notificationService.create(new NotificationCreateInput(userId, projectId,
                    eventType, title, content, dedupKey));
        } catch (Exception e) {
            log.warn("{} 通知失败（user={}）: {}", eventType, userId, e.getMessage());
        }
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String trunc(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
