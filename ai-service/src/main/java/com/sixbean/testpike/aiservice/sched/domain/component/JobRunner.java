package com.sixbean.testpike.aiservice.sched.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
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

    private final CoreGateway coreGateway;

    public JobRunner(CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
    }

    
    public void alert(String jobName, Throwable cause) {
        String reason = cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage();
        try {
            List<Long> admins = coreGateway.listAdminRecipients();
            String hourWindow = LocalDateTime.now().format(HOUR_WINDOW);
            for (Long adminId : admins) {
                try {
                    coreGateway.createNotification(adminId, null, "JOB_FAILED",
                            "定时任务执行失败：" + jobName,
                            "定时任务 " + jobName + " 本轮执行失败（" + reason
                                    + "），系统将在下一轮自动重试；连续失败请查看服务日志定位原因。",
                            "JOB_FAILED:" + jobName + ":" + hourWindow + ":" + adminId);
                } catch (Exception e) {
                    log.warn("JOB_FAILED 站内信发送失败（job={} 接收人id={}）: {}",
                            jobName, adminId, e.getMessage());
                }
            }
            coreGateway.writeAudit(new LlmAuditRequest(null, null, "scheduler", jobName,
                    "JOB_FAILED", null,
                    Map.of("reason", reason, "errorType", cause.getClass().getName())));
        } catch (Exception e) {
            log.warn("JOB_FAILED 告警链路故障（job={}），仅日志留痕不阻断调度续跑: {}",
                    jobName, e.getMessage());
        }
    }
}
