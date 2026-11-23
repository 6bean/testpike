package com.sixbean.testpike.core.batch.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;


@Component
public class BatchNotifySwitch {

    private final JdbcTemplate jdbcTemplate;

    public BatchNotifySwitch(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public boolean feishuEnabled(long batchId) {
        Map<String, Object> flags;
        try {
            flags = jdbcTemplate.queryForMap(
                    "SELECT b.notify_feishu, p.notify_feishu AS project_notify_feishu"
                            + " FROM tp_exec_batch b JOIN tp_project p ON p.id = b.project_id WHERE b.id = ?",
                    batchId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return false;
        }
        int batchFlag = flags.get("notify_feishu") == null
                ? 0 : ((Number) flags.get("notify_feishu")).intValue();
        if (batchFlag == 1) {
            return true;
        }
        int projectFlag = flags.get("project_notify_feishu") == null
                ? 0 : ((Number) flags.get("project_notify_feishu")).intValue();
        return projectFlag == 1;
    }
}
