package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


@Component
public class ScriptVersionSupport {

    
    public static final String REASON_SAVE = "脚本保存";
 
    public static final String REASON_MANUAL_EDIT = "manual_edit";
 
    public static final String REASON_ROLLBACK_RESTORE = "用例回滚重挂";
 
    public static final String REASON_AI_REPAIR = "AI修复";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public ScriptVersionSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public void write(long caseId, String reason, Long createdBy) {
        jdbcTemplate.update("""
                INSERT INTO tp_script_version
                    (case_id, script_id, case_version, version, schema_version, actions_json, reason, created_by)
                SELECT s.case_id, s.id, c.version, s.version, s.schema_version, s.action_json, ?, ?
                FROM tp_script s
                JOIN tp_test_case c ON c.id = s.case_id
                WHERE s.case_id = ?
                """, reason, createdBy, caseId);
    }

    
    public static boolean isJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            return MAPPER.readTree(json).isObject();
        } catch (Exception e) {
            return false;
        }
    }
}
