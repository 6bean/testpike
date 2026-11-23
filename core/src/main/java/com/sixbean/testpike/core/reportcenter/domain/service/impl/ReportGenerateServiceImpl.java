package com.sixbean.testpike.core.reportcenter.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportCenterService;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportGenerateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
public class ReportGenerateServiceImpl implements ReportGenerateService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerateServiceImpl.class);

    
    static final String KEY_WEEKLY_ENABLED = "report.weekly-enabled";
    static final String KEY_MONTHLY_ENABLED = "report.monthly-enabled";

    private static final String EVENT_DIGEST = "REPORT_DIGEST";

    private final ReportCenterService reportCenterService;
    private final ProjectService projectService;
    private final ProjectOwners projectOwners;
    private final AdminAlertRecipients adminAlertRecipients;
    private final NotificationService notificationService;
    private final SystemConfigReader systemConfigReader;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReportGenerateServiceImpl(ReportCenterService reportCenterService,
                                     ProjectService projectService,
                                     ProjectOwners projectOwners,
                                     AdminAlertRecipients adminAlertRecipients,
                                     NotificationService notificationService,
                                     SystemConfigReader systemConfigReader,
                                     JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper) {
        this.reportCenterService = reportCenterService;
        this.projectService = projectService;
        this.projectOwners = projectOwners;
        this.adminAlertRecipients = adminAlertRecipients;
        this.notificationService = notificationService;
        this.systemConfigReader = systemConfigReader;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public int runWeekly() {
        if (!systemConfigReader.getBoolean(KEY_WEEKLY_ENABLED, true)) {
            log.info("report.weekly-enabled=false，本周期报跳过（K8 热更）");
            return 0;
        }
        Timestamp[] week = dbWindow("date_trunc('week', now() - interval '7 days')",
                "date_trunc('week', now())");
        return generateSweep(1, week[0].toLocalDateTime(), week[1].toLocalDateTime());
    }

    @Override
    public int runMonthly() {
        if (!systemConfigReader.getBoolean(KEY_MONTHLY_ENABLED, true)) {
            log.info("report.monthly-enabled=false，本月报跳过（K8 热更）");
            return 0;
        }
        Timestamp[] month = dbWindow("date_trunc('month', now() - interval '1 month')",
                "date_trunc('month', now())");
        return generateSweep(2, month[0].toLocalDateTime(), month[1].toLocalDateTime());
    }

    
    private int generateSweep(int reportType, LocalDateTime start, LocalDateTime end) {
        List<Long> projectIds = projectService.activeProjectIds();
        List<ReportView> views = new ArrayList<>(projectIds.size() + 1);
        for (Long pid : projectIds) {
            views.add(reportCenterService.generate(ReportCenterServiceImpl.SYSTEM_OPERATOR_ID,
                    new ReportGenerateInput(reportType, 1, pid, start.toString(), end.toString())));
        }
        views.add(reportCenterService.generate(ReportCenterServiceImpl.SYSTEM_OPERATOR_ID,
                new ReportGenerateInput(reportType, 2, 0L, start.toString(), end.toString())));
        for (ReportView view : views) {
            sendDigestQuietly(view, reportType);
        }
        return views.size();
    }

    private Timestamp[] dbWindow(String startExpr, String endExpr) {
        Timestamp start = jdbcTemplate.queryForObject("SELECT " + startExpr, Timestamp.class);
        Timestamp end = jdbcTemplate.queryForObject("SELECT " + endExpr, Timestamp.class);
        return new Timestamp[] {start, end};
    }


    private void sendDigestQuietly(ReportView view, int reportType) {
        try {
            String typeName = reportType == 1 ? "周报" : "月报";
            String summary = digestSummary(view.payloadJson());
            String period = view.periodStart().toLocalDate() + " ~ " + view.periodEnd().toLocalDate();
            if (view.scopeType() != null && view.scopeType() == 1) {
                String name = projectName(view.scopeId());
                String title = typeName + "摘要：" + name + "（" + period + "）";
                for (Long userId : projectOwners.resolveWithCreator(view.scopeId())) {
                    notify(userId, view.scopeId(), view.id(), title, summary);
                }
            } else {
                String title = typeName + "摘要：全局（" + period + "）";
                for (Long userId : adminAlertRecipients.adminUserIds()) {
                    notify(userId, null, view.id(), title, summary);
                }
            }
        } catch (RuntimeException e) {
            log.warn("REPORT_DIGEST 发送失败（reportId={}，快照已生成不受影响）: {}",
                    view.id(), e.getMessage());
        }
    }

    private void notify(Long userId, Long projectId, long reportId, String title, String content) {
        notificationService.create(new NotificationCreateInput(userId, projectId, EVENT_DIGEST,
                title, content, EVENT_DIGEST + ":" + reportId + ":" + userId));
    }

    
    private String digestSummary(String payloadJson) throws RuntimeException {
        try {
            JsonNode calibers = objectMapper.readTree(payloadJson).path("calibers");
            StringBuilder sb = new StringBuilder("关键指标：");
            List<String> parts = new ArrayList<>();
            calibers.fields().forEachRemaining(e -> {
                JsonNode block = e.getValue();
                if (block.has("value")) {
                    String v = block.get("value").isNull() ? "—" : block.get("value").asText();
                    parts.add(block.path("display_name").asText(e.getKey()) + "=" + v);
                }
            });
            return sb.append(String.join("；", parts)).append("。详情见报表中心。").toString();
        } catch (Exception e) {
            throw new IllegalStateException("报表 payload 解析失败（摘要跳过，快照不受影响）", e);
        }
    }

    private String projectName(long projectId) {
        return jdbcTemplate.query("SELECT name FROM tp_project WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : ("项目" + projectId), projectId);
    }
}
