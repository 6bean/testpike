package com.sixbean.testpike.core.monitor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;


@Component
public class MonitorProbeSupport {

    private static final Logger log = LoggerFactory.getLogger(MonitorProbeSupport.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MonitorProbeSupport(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    
    public String envConfigJson(Long envId) {
        if (envId == null) {
            return null;
        }
        try {
            return jdbc.queryForObject("SELECT config_json::text FROM tp_project_env WHERE id = :eid",
                    new MapSqlParameterSource("eid", envId), String.class);
        } catch (Exception e) {
            log.warn("读取环境配置失败（envId={}）: {}", envId, e.getMessage());
            return null;
        }
    }

    
    public JsonNode parseProbeConfig(String probeConfigJson) {
        if (probeConfigJson == null || probeConfigJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(probeConfigJson);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    
    public String brief(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.split("\n", 2)[0].trim();
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    
    public int elapsedMs(long startNano) {
        long ms = (System.nanoTime() - startNano) / 1_000_000;
        return (int) Math.max(ms, 0);
    }
}
