package com.sixbean.testpike.core.datafactory.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;


@Component
public class DataPoolVarsSupport {

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCipher configCipher;

    public DataPoolVarsSupport(JdbcTemplate jdbcTemplate, ConfigCipher configCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCipher = configCipher;
    }

    
    public Map<String, String> decryptPoolVars(long projectId, String envBaseUrl) {
        if (envBaseUrl == null || envBaseUrl.isBlank()) {
            return Map.of();
        }
        Long envId = jdbcTemplate.query(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND base_url = ?"
                        + " ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, projectId, envBaseUrl);
        if (envId == null) {
            return Map.of();
        }
        Map<String, String> vars = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT i.name, i.value FROM tp_data_pool_item i
                WHERE i.project_id = ? AND i.env_id = ? AND i.status = 1
                  AND i.name IS NOT NULL
                ORDER BY i.id
                """,
                rs -> {
                    vars.put(rs.getString(1), decryptValue(rs.getString(2)));
                }, projectId, envId);
        return vars;
    }

    
    private String decryptValue(String stored) {
        if (stored == null || stored.isBlank()) {
            return "";
        }
        if (!configCipher.isEnabled()) {
            return stored;
        }
        try {
            return configCipher.decrypt(stored);
        } catch (Exception e) {
            return stored;
        }
    }
}
