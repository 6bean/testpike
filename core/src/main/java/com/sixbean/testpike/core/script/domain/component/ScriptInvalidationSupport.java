package com.sixbean.testpike.core.script.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


@Component
public class ScriptInvalidationSupport {

    private final JdbcTemplate jdbcTemplate;

    public ScriptInvalidationSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public void markPending(long caseId) {
        jdbcTemplate.update(
                "UPDATE tp_script SET status = 2, updated_at = now() WHERE case_id = ?", caseId);
    }
}
