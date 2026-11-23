package com.sixbean.testpike.core.batch.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.report.PassRateSql;
import com.sixbean.testpike.core.batch.api.dto.CiVerdictView;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class CiGatePolicy {

    
    private static final int BATCH_DONE = 6;
    private static final int BATCH_PARTIAL_FAILED = 7;
    private static final int BATCH_CANCELLED = 8;
    
    private static final String FAILED_TASK_STATUSES = "(4, 5)";
    
    public static final String KEY_PASS_RATE = "ci.verdict.pass-rate";
    public static final String KEY_P0_ZERO = "ci.verdict.p0-zero";
    private static final String DEFAULT_PASS_RATE = "0.95";
    private static final String DEFAULT_P0_ZERO = "1";

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public CiGatePolicy(JdbcTemplate jdbcTemplate, ConfigService configService,
                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    
    public CiVerdictView evaluate(long batchId, int batchStatus, Long planId) {
        BigDecimal threshold = resolveThreshold(planId);
        String thresholdSource = planId != null && planThresholdPresent(planId)
                ? "plan" : "config";
        if (!isTerminal(batchStatus)) {
            return new CiVerdictView(batchId, "pending",
                    "批次尚未结束（当前状态：" + statusLabel(batchStatus)
                            + "），请等待执行中批次进入终态后再取判定结论",
                    null, threshold, thresholdSource, List.of());
        }
        BigDecimal passRate = queryPassRate(batchId);
        if (passRate == null) {
            return new CiVerdictView(batchId, "pending",
                    "批次暂无终态任务（可能全部跳过或取消），无法判定通过率",
                    null, threshold, thresholdSource, List.of());
        }
        List<Map<String, Object>> unmet = new ArrayList<>();
        if (passRate.compareTo(threshold) < 0) {
            unmet.add(item("passRate", "required", threshold, "actual", passRate));
        }
        if (p0ZeroEnabled()) {
            collectP0Failures(batchId, unmet);
        }
        boolean pass = unmet.isEmpty();
        return new CiVerdictView(batchId, pass ? "pass" : "fail",
                pass ? "门禁通过：通过率达标且无 P0 失败" : "门禁未通过：存在未达项（见明细）",
                passRate, threshold, thresholdSource, unmet);
    }

    private boolean isTerminal(int batchStatus) {
        return batchStatus == BATCH_DONE || batchStatus == BATCH_PARTIAL_FAILED
                || batchStatus == BATCH_CANCELLED;
    }

    private String statusLabel(int batchStatus) {
        return com.sixbean.testpike.core.batch.api.dto.BatchInfo.statusLabel(batchStatus);
    }

    
    private BigDecimal queryPassRate(long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT (" + PassRateSql.RATE_EXPR + ") AS pass_rate FROM tp_exec_task t"
                        + " WHERE t.batch_id = ?",
                (rs, n) -> (BigDecimal) rs.getObject("pass_rate"), batchId);
    }

    
    private BigDecimal resolveThreshold(Long planId) {
        BigDecimal planValue = planThreshold(planId);
        if (planValue != null) {
            return planValue;
        }
        return parseRate(configService.getValue(KEY_PASS_RATE, DEFAULT_PASS_RATE), DEFAULT_PASS_RATE);
    }

    
    private boolean planThresholdPresent(Long planId) {
        return planThreshold(planId) != null;
    }

    private BigDecimal planThreshold(Long planId) {
        if (planId == null) {
            return null;
        }
        List<String> jsons = jdbcTemplate.query(
                "SELECT exit_criteria_json FROM tp_exec_plan WHERE id = ?",
                (rs, n) -> rs.getString(1), planId);
        if (jsons.isEmpty() || jsons.get(0) == null || jsons.get(0).isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(jsons.get(0)).path("minPassRate");
            if (node.isNumber()) {
                return new BigDecimal(node.asText());
            }
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
        return null;
    }

    private BigDecimal parseRate(String raw, String fallback) {
        try {
            return new BigDecimal(raw.trim());
        } catch (RuntimeException e) {
            return new BigDecimal(fallback);
        }
    }

    private boolean p0ZeroEnabled() {
        String raw = configService.getValue(KEY_P0_ZERO, DEFAULT_P0_ZERO);
        try {
            return Integer.parseInt(raw.trim()) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    
    private void collectP0Failures(long batchId, List<Map<String, Object>> unmet) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT t.case_id, c.title, t.error_detail FROM tp_exec_task t"
                        + " JOIN tp_test_case c ON c.id = t.case_id"
                        + " WHERE t.batch_id = ? AND t.status IN " + FAILED_TASK_STATUSES
                        + " AND c.priority = 0 ORDER BY t.id",
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("caseId", rs.getLong("case_id"));
                    row.put("title", rs.getString("title"));
                    row.put("errorDetail", rs.getString("error_detail"));
                    return row;
                }, batchId);
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item", "p0Fail");
            item.putAll(row);
            unmet.add(item);
        }
    }

    
    private Map<String, Object> item(String name, String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", name);
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
