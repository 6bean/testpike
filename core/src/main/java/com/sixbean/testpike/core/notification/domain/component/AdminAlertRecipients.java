package com.sixbean.testpike.core.notification.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class AdminAlertRecipients {

    private final JdbcTemplate jdbcTemplate;

    public AdminAlertRecipients(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public List<Long> adminUserIds() {
        return jdbcTemplate.queryForList("""
                SELECT id FROM tp_user
                 WHERE deleted = 0 AND (role = 4 OR id IN (
                       SELECT ur.user_id FROM tp_user_role ur
                       JOIN tp_role r ON r.id = ur.role_id
                       WHERE r.code = 'admin'))
                 ORDER BY id
                """, Long.class);
    }
}
