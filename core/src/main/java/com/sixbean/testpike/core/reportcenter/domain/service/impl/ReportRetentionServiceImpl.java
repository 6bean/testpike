package com.sixbean.testpike.core.reportcenter.domain.service.impl;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class ReportRetentionServiceImpl implements ReportRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ReportRetentionServiceImpl.class);

 
    static final String KEY_RETENTION_DAYS = "report.retention-days";

 
    private static final int DEFAULT_DAYS = 365;

    private final NamedParameterJdbcTemplate named;
    private final SystemConfigReader systemConfigReader;
    private final AuditLogService auditLogService;

    public ReportRetentionServiceImpl(NamedParameterJdbcTemplate named,
                                      SystemConfigReader systemConfigReader,
                                      AuditLogService auditLogService) {
        this.named = named;
        this.systemConfigReader = systemConfigReader;
        this.auditLogService = auditLogService;
    }

    @Override
    public int deleteExpired() {
        int days = systemConfigReader.getInt(KEY_RETENTION_DAYS, DEFAULT_DAYS);
        if (days <= 0) {
            return 0;
        }
        List<Long> removed = named.query("DELETE FROM tp_report WHERE generated_at < "
                        + "now() - make_interval(days => :days) RETURNING id",
                new MapSqlParameterSource("days", days),
                (rs, n) -> rs.getLong(1));
        if (removed.isEmpty()) {
            return 0;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deletedCount", removed.size());
        after.put("retentionDays", days);
        after.put("purgedIds", removed);
        auditLogService.record(new AuditLogInput(ReportCenterServiceImpl.SYSTEM_OPERATOR_ID, null,
                "report", "0", "retention-purge", null, after));
        log.info("报表快照保留清理：{} 行已删除（retention-days={}）", removed.size(), days);
        return removed.size();
    }
}
