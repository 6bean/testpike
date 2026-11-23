package com.sixbean.testpike.core.user.domain.service.impl;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.user.domain.service.LoginLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class LoginLockServiceImpl implements LoginLockService {

    private static final Logger log = LoggerFactory.getLogger(LoginLockServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;
    private final AfterCommitExecutor afterCommitExecutor;

    public LoginLockServiceImpl(JdbcTemplate jdbcTemplate, AuditLogService auditLogService,
                                NotificationService notificationService,
                                AdminAlertRecipients adminAlertRecipients,
                                AfterCommitExecutor afterCommitExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    @Override
    @Transactional
    public void applyLock(long userId, String username, int failCount, int lockMinutes) {
        String reason = "连续 " + failCount + " 次密码错误";
        jdbcTemplate.update("""
                UPDATE tp_user SET fail_count = ?, locked_until = now() + (? || ' minutes')::interval,
                lock_reason = ?, updated_at = now() WHERE id = ?
                """, failCount, lockMinutes, reason, userId);
        recordLockAudit(userId, reason, lockMinutes);
        String content = LoginLockService.lockMessage(username, failCount, lockMinutes);
        String dedupKey = LoginLockService.dedupKey(userId, LocalDate.now().toString());
        Set<Long> recipients = new LinkedHashSet<>();
        recipients.add(userId);
        recipients.addAll(adminAlertRecipients.adminUserIds());
        afterCommitExecutor.execute(() -> notifyLock(recipients, dedupKey, content));
    }

    
    private void notifyLock(Set<Long> recipients, String dedupKey, String content) {
        for (Long uid : recipients) {
            try {
                notificationService.create(new NotificationCreateInput(
                        uid, null, "LOGIN_LOCKED", "账号登录锁定告警", content, dedupKey + ":" + uid));
            } catch (Exception e) {
                log.warn("LOGIN_LOCKED 站内信发送失败（接收人 id={}）: {}", uid, e.getMessage());
            }
        }
    }

    
    private void recordLockAudit(long userId, String reason, int lockMinutes) {
        try {
            java.util.Map<String, Object> after = new java.util.LinkedHashMap<>();
            after.put("reason", reason);
            after.put("lockMinutes", lockMinutes);
            auditLogService.record(new AuditLogInput(0L, null, "user", String.valueOf(userId),
                    "lock", null, after));
        } catch (Exception e) {
            log.warn("登录锁定审计记录失败（不阻断登录）: userId={} err={}", userId, e.getMessage());
        }
    }
}
