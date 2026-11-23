package com.sixbean.testpike.core.monitor.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.monitor.api.dto.MonitorLinkedAssetsView;
import com.sixbean.testpike.core.monitor.domain.service.MonitorLinkedAssetService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <ul>
 * </ul>
 */
@Service
public class MonitorLinkedAssetServiceImpl implements MonitorLinkedAssetService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MonitorLinkedAssetServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public MonitorLinkedAssetsView linkedAssets(long envId) {
        List<String> planNames = queryJsonArrayRefs("tp_exec_plan", envId);
        List<String> suiteNames = queryJsonArrayRefs("tp_regression_suite", envId);
        List<String> factoryNames = queryGrouped("""
                SELECT f.name AS asset_name
                FROM tp_data_factory f
                WHERE f.env_id = :envId
                ORDER BY f.name""", envId);
        List<String> apiNames = queryGrouped("""
                SELECT DISTINCT r.name AS asset_name
                FROM tp_apitest_run run
                JOIN tp_apitest_request r ON r.id = run.request_id
                WHERE run.env_id = :envId
                ORDER BY r.name""", envId);
        return new MonitorLinkedAssetsView(envId,
                planNames.size(), truncatable(planNames),
                suiteNames.size(), truncatable(suiteNames),
                factoryNames.size(), truncatable(factoryNames),
                apiNames.size(), truncatable(apiNames));
    }

    
    private List<String> queryJsonArrayRefs(String table, long envId) {
        List<Object[]> rows = jdbc.query(
                "SELECT name, env_ids_json FROM " + table + " ORDER BY name",
                new MapSqlParameterSource(),
                (rs, rowNum) -> new Object[]{rs.getString(1), rs.getString(2)});
        List<String> hits = new ArrayList<>();
        for (Object[] row : rows) {
            if (referencesEnv((String) row[1], envId)) {
                hits.add((String) row[0]);
            }
        }
        return hits;
    }

    
    private boolean referencesEnv(String envIdsJson, long envId) {
        if (envIdsJson == null || envIdsJson.isBlank()) {
            return false;
        }
        try {
            JsonNode tree = objectMapper.readTree(envIdsJson);
            if (!tree.isArray()) {
                return false;
            }
            for (JsonNode element : tree) {
                if (element.isIntegralNumber() && element.longValue() == envId) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    
    private List<String> queryGrouped(String sql, long envId) {
        return jdbc.query(sql, new MapSqlParameterSource("envId", envId),
                (rs, rowNum) -> rs.getString(1));
    }

    
    private static List<String> truncatable(List<String> names) {
        return names.size() <= MonitorLinkedAssetsView.MAX_NAMES
                ? List.copyOf(names)
                : List.copyOf(names.subList(0, MonitorLinkedAssetsView.MAX_NAMES));
    }
}
