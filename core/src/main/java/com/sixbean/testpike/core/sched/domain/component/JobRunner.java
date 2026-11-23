package com.sixbean.testpike.core.sched.domain.component;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;


@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    
    static final DateTimeFormatter HOUR_WINDOW = DateTimeFormatter.ofPattern("yyyyMMddHH");

    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final AdminAlertRecipients adminAlertRecipients;

    public JobRunner(NotificationService notificationService, AuditLogService auditLogService,
                     AdminAlertRecipients adminAlertRecipients) {
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    
    public void alert(String jobName, Throwable cause) {
        String reason = cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage();
        try {
            List<Long> admins = adminAlertRecipients.adminUserIds();
            String hourWindow = LocalDateTime.now().format(HOUR_WINDOW);
            for (Long adminId : admins) {
                try {
                    notificationService.create(new NotificationCreateInput(adminId, null,
                            "JOB_FAILED", "定时任务执行失败：" + jobName,
                            "定时任务 " + jobName + " 本轮执行失败（" + reason
                                    + "），系统将在下一轮自动重试；连续失败请查看服务日志定位原因。",
                            "JOB_FAILED:" + jobName + ":" + hourWindow + ":" + adminId));
                } catch (Exception e) {
                    log.warn("JOB_FAILED 站内信发送失败（job={} 接收人id={}）: {}",
                            jobName, adminId, e.getMessage());
                }
            }
            auditLogService.record(new AuditLogInput(null, null, "scheduler", jobName,
                    "JOB_FAILED", null, Map.of("reason", reason, "errorType",
                    cause.getClass().getName())));
        } catch (Exception e) {
            log.warn("JOB_FAILED 告警链路故障（job={}），仅日志留痕不阻断调度续跑: {}",
                    jobName, e.getMessage());
        }
    }
}
