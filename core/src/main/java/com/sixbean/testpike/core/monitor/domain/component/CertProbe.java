package com.sixbean.testpike.core.monitor.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;


@Component
public class CertProbe implements MonitorProbe {

    static final String KEY_STALE_HOURS = "monitor.cert.stale-hours";
    private static final String KEY_EXPIRE_WARN_DAYS = "tls.cert.expire-warn-days";

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final MonitorProbeSupport support;

    public CertProbe(NamedParameterJdbcTemplate jdbc, ConfigService configService,
                     MonitorProbeSupport support) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.support = support;
    }

    @Override
    public String supportedType() {
        return "CERT";
    }

    @Override
    public MonitorProbeResult probe(MonitorTarget target) {
        long start = System.nanoTime();
        if (target.getEnvId() == null) {
            return MonitorProbeResult.fail(null, "证书探测需要目标挂接项目环境");
        }
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT cert_not_after, cert_chain_complete, cert_checked_at "
                            + "FROM tp_project_env WHERE id = :eid",
                    new MapSqlParameterSource("eid", target.getEnvId()));
        } catch (Exception e) {
            return MonitorProbeResult.fail(null, "环境不存在或已被删除，无法读取证书观测");
        }
        java.sql.Timestamp checkedAt = (java.sql.Timestamp) row.get("cert_checked_at");
        java.sql.Timestamp notAfter = (java.sql.Timestamp) row.get("cert_not_after");
        Object chain = row.get("cert_chain_complete");
        int latency = support.elapsedMs(start);
        if (checkedAt == null || notAfter == null) {
            return MonitorProbeResult.fail(latency,
                    "本环境尚无证书观测数据，请先执行环境健康检查采集证书信息");
        }
        int staleHours = intConfig(KEY_STALE_HOURS, 24);
        if (staleHours > 0 && checkedAt.toLocalDateTime()
                .isBefore(LocalDateTime.now().minusHours(staleHours))) {
            return MonitorProbeResult.fail(latency, "证书观测已过期（超 " + staleHours
                    + " 小时未更新），请重跑环境健康检查");
        }
        if (chain != null && ((Number) chain).intValue() == 0) {
            return MonitorProbeResult.fail(latency, "证书链校验不通过（TLS 握手链路不完整）");
        }
        int warnDays = intConfig(KEY_EXPIRE_WARN_DAYS, 30);
        if (warnDays > 0) {
            LocalDateTime deadline = LocalDateTime.now().plusDays(warnDays);
            if (notAfter.toLocalDateTime().isBefore(deadline)) {
                long daysLeft = java.time.Duration.between(LocalDateTime.now(),
                        notAfter.toLocalDateTime()).toDays();
                return MonitorProbeResult.fail(latency, "证书将在 " + Math.max(daysLeft, 0)
                        + " 天内到期（" + notAfter.toLocalDateTime().toLocalDate() + "），请及时更换");
            }
        }
        return MonitorProbeResult.ok(latency);
    }

    private int support0(long start) {
        long ms = (System.nanoTime() - start) / 1_000_000;
        return (int) Math.max(ms, 0);
    }

    private int intConfig(String key, int fallback) {
        try {
            return Integer.parseInt(configService.getValue(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
