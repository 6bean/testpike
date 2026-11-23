package com.sixbean.testpike.core.cluster.domain.component;

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

/**
 * Platform version candidate detection reminder (alerting component of the release feed probing chain).
 * <p>Sent only in detected state; confirmed/dismissed/replaced are already acknowledged by humans, so re-probing does not spam.
 * This is the consumer-side counterpart of the monotonically increasing state spectrum.
 * <p>REQUIRES_NEW is independent from the probing transaction: candidate ledger persistence and reminder dispatch are separated by a gate, and a single recipient failure only logs a warning.
 * It follows the precedent approach of VersionUnsupportedAlerter.
 * <p>dedup_key=RELEASE_CANDIDATE:{version}:{daily window}:{adminId}; same-day same-version entries are merged.
 * tp_notification performs single-point deduplication interception; a new window the next day can trigger another reminder, so a continuously pending confirmation cannot be permanently muted.
 */
@Component
public class ReleaseCandidateAlerter {

    private static final Logger log = LoggerFactory.getLogger(ReleaseCandidateAlerter.class);
    private static final DateTimeFormatter DAY_WINDOW = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;

    public ReleaseCandidateAlerter(NotificationService notificationService,
                                   AdminAlertRecipients adminAlertRecipients) {
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    /** Send RELEASE_CANDIDATE reminders awaiting confirmation to all system administrators (a single recipient failure only logs a warning and does not block). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void alert(String version, String content) {
        String dedup = "RELEASE_CANDIDATE:" + version + ":" + LocalDate.now().format(DAY_WINDOW);
        List<Long> admins = adminAlertRecipients.adminUserIds();
        for (Long adminId : admins) {
            try {
                notificationService.create(new NotificationCreateInput(
                        adminId, null, "RELEASE_CANDIDATE",
                        "发现新版本待确认", content, dedup + ":" + adminId));
            } catch (Exception e) {
                log.warn("RELEASE_CANDIDATE 提醒发送失败（接收人 id={}）: {}", adminId, e.getMessage());
            }
        }
    }
}
