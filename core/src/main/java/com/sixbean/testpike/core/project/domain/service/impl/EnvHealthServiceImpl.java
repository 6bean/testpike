package com.sixbean.testpike.core.project.domain.service.impl;

import com.sixbean.testpike.core.project.domain.service.EnvHealthService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView;
import com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.project.api.dto.EnvHealthScanResult;
import com.sixbean.testpike.core.project.api.dto.EnvProbeOutcome;
import com.sixbean.testpike.core.project.domain.component.EnvHealthRow;
import com.sixbean.testpike.core.project.domain.component.EnvScanOutcome;
import com.sixbean.testpike.core.project.domain.component.EnvScanState;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import com.sixbean.testpike.core.project.domain.component.CertProbeOutcome;
import com.sixbean.testpike.core.project.domain.component.EnvCertProber;
import com.sixbean.testpike.core.project.domain.component.TlsAwareHttpSupport;
import com.sixbean.testpike.core.project.domain.component.TlsHttpResult;
import com.sixbean.testpike.common.util.TlsPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class EnvHealthServiceImpl implements EnvHealthService {

    static final String KEY_ENABLED = "env.health-check.enabled";
    static final String KEY_INTERVAL = "env.health-interval-minutes";
    
    static final String KEY_CERT_WARN_DAYS = "tls.cert.expire-warn-days";
    static final int HEALTH_UNKNOWN = 0;
    static final int HEALTHY = 1;
    static final int UNHEALTHY = 2;
    
    static final int ALERT_STREAK = 2;
    
    private static final int MSG_MAX = 200;
    
    private static final int PROBE_TIMEOUT_MS = 5000;
    private static final DateTimeFormatter CARD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter CARD_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Logger log = LoggerFactory.getLogger(EnvHealthServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final EnvTlsSupport envTlsSupport;
    private final EnvCertProber certProber;
    private final TlsAwareHttpSupport tlsHttp;
    private final MonitorEpisodeService monitorEpisodeService;

    public EnvHealthServiceImpl(JdbcTemplate jdbcTemplate, ConfigService configService,
                                NotificationService notificationService, ProjectOwners projectOwners,
                                AuditLogService auditLogService, ObjectMapper objectMapper,
                                EnvTlsSupport envTlsSupport, EnvCertProber certProber,
                                TlsAwareHttpSupport tlsHttp, MonitorEpisodeService monitorEpisodeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.envTlsSupport = envTlsSupport;
        this.certProber = certProber;
        this.tlsHttp = tlsHttp;
        this.monitorEpisodeService = monitorEpisodeService;
    }

    @Override
    public EnvHealthScanResult scanAll() {
        if (!"1".equals(configService.getValue(KEY_ENABLED, "1").trim())) {
            return new EnvHealthScanResult(0, 0, 0, 0, 0);
        }
        List<Long> envIds = jdbcTemplate.queryForList("""
                SELECT e.id FROM tp_project_env e
                  JOIN tp_project p ON p.id = e.project_id
                 WHERE e.base_url IS NOT NULL AND e.base_url <> '' AND p.status <> 2
                 ORDER BY e.id
                """, Long.class);
        int healthy = 0;
        int unhealthy = 0;
        int alerted = 0;
        int recovered = 0;
        for (Long envId : envIds) {
            try {
                EnvScanOutcome o = doScan(envId);
                switch (o.state()) {
                    case HEALTHY -> healthy++;
                    case UNHEALTHY -> unhealthy++;
                    case ALERTED -> {
                        unhealthy++;
                        alerted++;
                    }
                    case RECOVERED -> {
                        healthy++;
                        recovered++;
                    }
                }
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                log.warn("环境健康检查单环境异常（envId={}）: {}", envId, e.getMessage());
                unhealthy++;
            }
        }
        return new EnvHealthScanResult(envIds.size(), healthy, unhealthy, alerted, recovered);
    }

    @Override
    public EnvProbeOutcome scanOne(long envId) {
        return doScan(envId).probe();
    }

    
    private EnvScanOutcome doScan(long envId) {
        EnvHealthRow env = loadEnv(envId);
        EnvProbeOutcome probe = probe(env);
        observeCertificate(env);
        int prevStatus = env.healthStatus();
        if (probe.healthy()) {
            jdbcTemplate.update("""
                    UPDATE tp_project_env
                       SET health_status = 1, health_msg = NULL, health_fail_streak = 0,
                           health_checked_at = now(), updated_at = now()
                     WHERE id = ?""", envId);
            if (prevStatus == UNHEALTHY) {
                notifyRecovered(env);
                return new EnvScanOutcome(probe, EnvScanState.RECOVERED);
            }
            return new EnvScanOutcome(probe, EnvScanState.HEALTHY);
        }
        int streak = env.failStreak() + 1;
        jdbcTemplate.update("""
                UPDATE tp_project_env
                   SET health_status = 2, health_msg = ?, health_fail_streak = ?,
                       health_checked_at = now(), updated_at = now()
                 WHERE id = ?""", truncate(probe.msg()), streak, envId);
        if (streak == ALERT_STREAK) {
            notifyUnhealthy(env, probe.msg());
            return new EnvScanOutcome(probe, EnvScanState.ALERTED);
        }
        return new EnvScanOutcome(probe, EnvScanState.UNHEALTHY);
    }

    private EnvHealthRow loadEnv(long envId) {
        List<EnvHealthRow> rows = jdbcTemplate.query("""
                SELECT e.id, e.project_id, e.name, e.base_url, e.config_json::text AS config_json,
                       e.health_status, e.health_fail_streak
                  FROM tp_project_env e WHERE e.id = ?""",
                (rs, i) -> new EnvHealthRow(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getString("name"), rs.getString("base_url"), rs.getString("config_json"),
                        rs.getInt("health_status"), rs.getInt("health_fail_streak")), envId);
        if (rows.isEmpty()) {
            throw new ServiceException("环境不存在或已被删除", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }


    private EnvProbeOutcome probe(EnvHealthRow env) {
        String url = env.baseUrl().trim();
        TlsPolicy policy = envTlsSupport.resolvePolicyFailSafe(env.configJson());
        try {
            TlsHttpResult r = tlsHttp.get(url, policy, PROBE_TIMEOUT_MS);
            if (r.status() < 200 || r.status() >= 300) {
                return EnvProbeOutcome.fail("连通检查失败：HTTP " + r.status());
            }
        } catch (Exception e) {
            return EnvProbeOutcome.fail("连通检查失败：" + describe(e));
        }
        JsonNode login = loginSmokeConfig(env.configJson());
        if (login == null) {
            return EnvProbeOutcome.ok();
        }
        String path = login.path("path").asText("");
        if (path.isBlank()) {
            return EnvProbeOutcome.fail("登录冒烟配置缺少 path（config_json.health-login）");
        }
        try {
            TlsHttpResult r = tlsHttp.postJson(joinUrl(url, path),
                    Map.of("username", login.path("username").asText(""),
                            "password", login.path("password").asText("")),
                    policy, PROBE_TIMEOUT_MS);
            if (r.status() < 200 || r.status() >= 300) {
                return EnvProbeOutcome.fail("登录冒烟失败：HTTP " + r.status());
            }
            return EnvProbeOutcome.ok();
        } catch (Exception e) {
            return EnvProbeOutcome.fail("登录冒烟失败：" + describe(e));
        }
    }

    
    private void observeCertificate(EnvHealthRow env) {
        try {
            TlsPolicy policy = envTlsSupport.resolvePolicyFailSafe(env.configJson());
            CertProbeOutcome o = certProber.probe(env.baseUrl().trim(), policy);
            if (!o.collected()) {
                jdbcTemplate.update("UPDATE tp_project_env SET cert_issuer = NULL, cert_not_after = NULL,"
                        + " cert_chain_complete = NULL, cert_checked_at = NULL WHERE id = ?", env.envId());
                return;
            }
            jdbcTemplate.update("""
                    UPDATE tp_project_env
                       SET cert_issuer = ?, cert_not_after = ?, cert_chain_complete = ?,
                           cert_checked_at = now()
                     WHERE id = ?""",
                    truncate(o.issuer()), java.sql.Timestamp.valueOf(o.notAfter()),
                    o.chainTrusted() ? 1 : 0, env.envId());
        } catch (Exception e) {
            log.warn("证书观测链路失败（envId={}，不影响健康判定）: {}", env.envId(), e.getMessage());
        }
    }

    
    private JsonNode loginSmokeConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(configJson).path("health-login");
            return node.isMissingNode() || node.isNull() ? null : node;
        } catch (Exception e) {
            log.warn("环境 config_json 解析失败（跳过登录冒烟）: {}", e.getMessage());
            return null;
        }
    }

    private static String joinUrl(String base, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    
    private static String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof SocketTimeoutException || root instanceof java.net.ConnectException
                || root instanceof java.net.NoRouteToHostException || root instanceof java.io.IOException
                        && root.getMessage() != null && root.getMessage().contains("refused")) {
            return "连接超时或不可达（5 秒上限）";
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }


    private void notifyUnhealthy(EnvHealthRow env, String msg) {
        for (Long ownerId : projectOwners.resolve(env.projectId())) {
            String title = "环境健康检查异常：" + env.envName();
            String content = "项目环境「" + env.envName() + "」（" + env.baseUrl()
                    + "）连续 " + ALERT_STREAK + " 次健康检查异常：" + msg
                    + "。执行批次可能失败，请检查环境可用性。";
            try {
                notificationService.create(new NotificationCreateInput(ownerId, env.projectId(),
                        "ENV_UNHEALTHY", title, content, "ENV_UNHEALTHY:" + env.envId() + ":" + ownerId));
            } catch (Exception e) {
                log.warn("ENV_UNHEALTHY 通知失败（envId={} user={}）: {}", env.envId(), ownerId, e.getMessage());
            }
        }
        recordHealthAudit(env, UNHEALTHY, msg, "连续 " + ALERT_STREAK + " 次异常，已告警项目负责人");
    }

    
    private void notifyRecovered(EnvHealthRow env) {
        String activePattern = "ENV_UNHEALTHY:" + env.envId() + ":%";
        List<Long> recipients = jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM tp_notification WHERE dedup_key LIKE ? "
                        + "AND dedup_key NOT LIKE '%:recovered' ORDER BY user_id",
                Long.class, activePattern);
        if (recipients.isEmpty()) {
            return;
        }
        jdbcTemplate.update("UPDATE tp_notification SET dedup_key = dedup_key || ':recovered' "
                + "WHERE dedup_key LIKE ? AND dedup_key NOT LIKE '%:recovered'", activePattern);
        String title = "环境已恢复健康：" + env.envName();
        String content = "项目环境「" + env.envName() + "」（" + env.baseUrl()
                + "）健康检查已恢复正常，可继续执行批次。";
        for (Long userId : recipients) {
            try {
                notificationService.create(new NotificationCreateInput(userId, env.projectId(),
                        "ENV_RECOVERED", title, content,
                        "ENV_RECOVERED:" + env.envId() + ":" + userId
                                + ":" + System.currentTimeMillis() / 60000));
            } catch (Exception e) {
                log.warn("ENV_RECOVERED 通知失败（envId={} user={}）: {}", env.envId(), userId, e.getMessage());
            }
        }
        recordHealthAudit(env, HEALTHY, null, "环境恢复正常"
                + (recipients.isEmpty() ? "" : "，已通知 " + recipients.size() + " 位曾收告警者"));
    }

    
    private void recordHealthAudit(EnvHealthRow env, int status, String msg, String result) {
        try {
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("healthStatus", env.healthStatus());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("envId", env.envId());
            after.put("envName", env.envName());
            after.put("healthStatus", status);
            if (msg != null) {
                after.put("msg", msg);
            }
            after.put("result", result);
            auditLogService.record(new AuditLogInput(0L, env.projectId(), "project_env",
                    String.valueOf(env.envId()), "health", before, after));
        } catch (Exception e) {
            log.warn("环境健康审计写入失败（envId={}）: {}", env.envId(), e.getMessage());
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MSG_MAX ? value : value.substring(0, MSG_MAX);
    }

    
    public int intervalMinutes() {
        try {
            return Integer.parseInt(configService.getValue(KEY_INTERVAL, "15").trim());
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    
    @Override
    public Map<String, Object> getHealth(long envId) {
        EnvHealthRow env = loadEnv(envId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT to_char(health_checked_at, 'YYYY-MM-DD HH24:MI:SS') AS checked_at,"
                        + " COALESCE(health_msg, '') AS msg,"
                        + " cert_issuer, to_char(cert_not_after, 'YYYY-MM-DD') AS cert_not_after,"
                        + " cert_chain_complete,"
                        + " to_char(cert_checked_at, 'YYYY-MM-DD HH24:MI:SS') AS cert_checked_at"
                        + " FROM tp_project_env WHERE id = ?", envId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", switch (env.healthStatus()) {
            case HEALTHY -> "healthy";
            case UNHEALTHY -> "unhealthy";
            default -> "unknown";
        });
        data.put("checkedAt", row.get("checked_at") == null ? "" : row.get("checked_at"));
        data.put("msg", row.get("msg"));
        data.put("tlsMode", envTlsSupport.readMode(env.configJson()));
        data.put("certIssuer", row.get("cert_issuer"));
        data.put("certNotAfter", row.get("cert_not_after"));
        Object chain = row.get("cert_chain_complete");
        data.put("certChainComplete", chain == null ? null : ((Number) chain).intValue() == 1);
        data.put("certCheckedAt", row.get("cert_checked_at"));
        return data;
    }

    
    @Override
    public String envHealthWarning(long envId) {
        List<String> warnings = jdbcTemplate.query("""
                SELECT name, health_msg FROM tp_project_env
                 WHERE id = ? AND health_status = 2
                   AND health_checked_at >= now() - (? || ' minutes')::interval
                """, (rs, i) -> "环境「" + rs.getString("name") + "」近 " + intervalMinutes()
                + " 分钟健康检查异常：" + (rs.getString("health_msg") == null ? "未记录原因" : rs.getString("health_msg")),
                envId, String.valueOf(intervalMinutes()));
        if (warnings.isEmpty()) {
            return null;
        }
        return warnings.get(0) + monitorEvidenceSuffix(envId);
    }

    
    private String monitorEvidenceSuffix(long envId) {
        MonitorEpisodeView open = monitorEpisodeService.findOpenEpisodeByEnv(envId);
        if (open == null || open.startedAt() == null) {
            return "";
        }
        long redMinutes = java.time.Duration.between(open.startedAt(), LocalDateTime.now()).toMinutes();
        return "。监控显示该链路已红 " + redMinutes + " 分钟："
                + (open.causeSummary() == null || open.causeSummary().isBlank()
                        ? "未记录现象" : open.causeSummary());
    }


    @Override
    public int scanCertExpiry() {
        int warnDays = certWarnDays();
        if (warnDays <= 0) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT e.id AS env_id, e.project_id, e.name,
                       to_char(e.cert_not_after, 'YYYY-MM-DD') AS not_after,
                       round(extract(epoch from (e.cert_not_after - now())) / 86400)::int AS days_left
                  FROM tp_project_env e
                  JOIN tp_project p ON p.id = e.project_id
                 WHERE e.cert_not_after IS NOT NULL
                   AND e.cert_not_after <= now() + make_interval(days => ?)
                   AND p.status <> 2
                 ORDER BY e.id""", warnDays);
        int notified = 0;
        String dayWindow = LocalDateTime.now().format(CARD_DATE).replace("-", "");
        for (Map<String, Object> row : rows) {
            long envId = ((Number) row.get("env_id")).longValue();
            long projectId = ((Number) row.get("project_id")).longValue();
            String envName = (String) row.get("name");
            String notAfter = (String) row.get("not_after");
            int daysLeft = ((Number) row.get("days_left")).intValue();
            String human = daysLeft < 0
                    ? "证书已过期（到期日 " + notAfter + "）"
                    : "证书将于 " + notAfter + " 到期，剩余 " + daysLeft + " 天";
            for (Long ownerId : projectOwners.resolve(projectId)) {
                String dedupKey = "CERT_EXPIRING:" + envId + ":" + ownerId + ":" + dayWindow;
                if (notificationService.existsByDedupKey(dedupKey)) {
                    continue;
                }
                try {
                    long id = notificationService.create(new NotificationCreateInput(ownerId, projectId,
                            "CERT_EXPIRING",
                            "环境证书到期提醒：" + envName,
                            "项目环境「" + envName + "」" + human
                                    + "。到期后执行将按档案档位失败（strict/custom_ca 校验拒绝），"
                                    + "请联系管理员更新证书或维护内部 CA。",
                            dedupKey));
                    if (id > 0) {
                        notified++;
                    }
                } catch (Exception e) {
                    log.warn("CERT_EXPIRING 通知失败（envId={} user={}）: {}", envId, ownerId, e.getMessage());
                }
            }
        }
        return notified;
    }

    
    private int certWarnDays() {
        try {
            return Integer.parseInt(configService.getValue(KEY_CERT_WARN_DAYS, "30").trim());
        } catch (NumberFormatException e) {
            return 30;
        }
    }
}
