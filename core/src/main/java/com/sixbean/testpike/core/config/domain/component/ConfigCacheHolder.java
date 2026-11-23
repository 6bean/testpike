package com.sixbean.testpike.core.config.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class ConfigCacheHolder {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    
    public void loadAll(JdbcTemplate jdbcTemplate) {
        cache.clear();
        jdbcTemplate.query("SELECT config_key, config_value FROM tp_sys_config",
                (ResultSetExtractor<Void>) rs -> {
                    while (rs.next()) {
                        cache.put(rs.getString(1), rs.getString(2));
                    }
                    return null;
                });
    }

    public void put(String configKey, String plainValue) {
        cache.put(configKey, plainValue);
    }

    public void remove(String configKey) {
        cache.remove(configKey);
    }

    
    public String get(String configKey) {
        return cache.get(configKey);
    }
}
