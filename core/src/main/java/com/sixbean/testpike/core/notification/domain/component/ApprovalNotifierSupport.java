package com.sixbean.testpike.core.notification.domain.component;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;


@Component
public class ApprovalNotifierSupport {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifierSupport.class);

    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final JdbcTemplate jdbcTemplate;

    public ApprovalNotifierSupport(NotificationService notificationService,
                                   AfterCommitExecutor afterCommitExecutor,
                                   JdbcTemplate jdbcTemplate) {
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public void send(String eventType, Long projectId, Collection<Long> recipients,
                     String title, String content, String dedupPrefix) {
        afterCommitExecutor.execute(() -> {
            for (Long userId : recipients) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, projectId,
                            eventType, title, content, dedupPrefix + ":" + userId));
                } catch (Exception e) {
                    log.warn("{} 通知失败（user={}）: {}", eventType, userId, e.getMessage());
                }
            }
        });
    }

    
    public String projectLabel(Long projectId) {
        if (projectId == null) {
            return "系统级";
        }
        String projectName = jdbcTemplate.query(
                "SELECT name FROM tp_project WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null,
                projectId);
        return "项目「" + (projectName == null ? projectId : projectName) + "」";
    }

    
    public String displayName(Long userId) {
        if (userId == null) {
            return "系统";
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COALESCE(NULLIF(display_name, ''), username) AS name FROM tp_user WHERE id = ?",
                userId);
        return rows.isEmpty() ? "用户" + userId : String.valueOf(rows.get(0).get("name"));
    }
}
