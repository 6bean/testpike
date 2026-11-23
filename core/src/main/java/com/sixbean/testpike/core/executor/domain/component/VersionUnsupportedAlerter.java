package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;


@Component
public class VersionUnsupportedAlerter {

    private static final Logger log = LoggerFactory.getLogger(VersionUnsupportedAlerter.class);
    private static final DateTimeFormatter DAY_WINDOW = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;

    public VersionUnsupportedAlerter(NotificationService notificationService,
                                     AdminAlertRecipients adminAlertRecipients) {
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void alert(String executorName, String content) {
        String dedup = "VERSION_UNSUPPORTED:" + executorName + ":" + LocalDate.now().format(DAY_WINDOW);
        List<Long> admins = adminAlertRecipients.adminUserIds();
        for (Long adminId : admins) {
            try {
                notificationService.create(new NotificationCreateInput(
                        adminId, null, "VERSION_UNSUPPORTED",
                        "执行器协议版本不兼容告警", content, dedup + ":" + adminId));
            } catch (Exception e) {
                log.warn("VERSION_UNSUPPORTED 告警发送失败（接收人 id={}）: {}", adminId, e.getMessage());
            }
        }
    }
}
