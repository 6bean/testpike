package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityScanResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStabilityService;
import com.sixbean.testpike.core.cases.domain.component.FlakyRuleCalculator;
import com.sixbean.testpike.core.cases.domain.component.FlakyVerdict;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class AppautoStabilityServiceImpl implements AppautoStabilityService {

    
    private static final int TASK_TYPE_APPAUTO = 6;

    
    private static final int STATUS_SUCCESS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final FlakyRuleCalculator flakyRuleCalculator;
    private final AppAutoDispatchSupport appAutoDispatchSupport;
    private final AppautoConfigReader appautoConfigReader;

    public AppautoStabilityServiceImpl(JdbcTemplate jdbcTemplate,
                                       FlakyRuleCalculator flakyRuleCalculator,
                                       AppAutoDispatchSupport appAutoDispatchSupport,
                                       AppautoConfigReader appautoConfigReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.flakyRuleCalculator = flakyRuleCalculator;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
        this.appautoConfigReader = appautoConfigReader;
    }

    @Override
    public List<AppautoStabilityView> stabilityRows(long projectId, long caseId) {
        return aggregate(projectRows(projectId, caseId));
    }

    @Override
    public AppautoStabilityScanResult scan() {
        List<AppautoStabilityView> rows = aggregate(projectRows(null, null));
        int flakyGroups = 0;
        for (AppautoStabilityView row : rows) {
            if (row.flaky()) {
                flakyGroups++;
            }
        }
        return new AppautoStabilityScanResult(rows.size(), flakyGroups,
                appautoConfigReader.flakySentinelEnabled());
    }

    
    private List<Map<String, Object>> projectRows(Long projectId, Long caseId) {
        StringBuilder sql = new StringBuilder("""
                SELECT case_id, action_json, status FROM tp_exec_task
                WHERE task_type = 6 AND status IN (3, 4, 5)
                  AND case_id > 0
                """);
        List<Object> args = new ArrayList<>();
        if (projectId != null) {
            sql.append(" AND project_id = ?");
            args.add(projectId);
        }
        if (caseId != null) {
            sql.append(" AND case_id = ?");
            args.add(caseId);
        }
        sql.append(" ORDER BY case_id, COALESCE(finished_at, started_at, created_at), id");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    
    private List<AppautoStabilityView> aggregate(List<Map<String, Object>> rows) {
        Map<String, List<Integer>> statusByGroup = new LinkedHashMap<>();
        Map<String, long[]> metaByGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long caseId = ((Number) row.get("case_id")).longValue();
            Long deviceId = appAutoDispatchSupport.leaseDeviceId((String) row.get("action_json"));
            String key = caseId + "|" + (deviceId == null ? "?" : deviceId);
            statusByGroup.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(((Number) row.get("status")).intValue());
            metaByGroup.putIfAbsent(key, new long[]{caseId, deviceId == null ? -1L : deviceId});
        }
        List<AppautoStabilityView> out = new ArrayList<>(statusByGroup.size());
        for (Map.Entry<String, List<Integer>> entry : statusByGroup.entrySet()) {
            long[] meta = metaByGroup.get(entry.getKey());
            List<Integer> statuses = entry.getValue();
            FlakyVerdict verdict = flakyRuleCalculator.evaluate(statuses, false);
            int successes = 0;
            for (int status : statuses) {
                if (status == STATUS_SUCCESS) {
                    successes++;
                }
            }
            double score = (double) successes / statuses.size();
            out.add(new AppautoStabilityView(meta[0],
                    meta[1] < 0 ? null : meta[1],
                    verdict.samples(), verdict.flips(), verdict.flaky(), verdict.reason(),
                    Math.round(score * 10000.0) / 10000.0));
        }
        return out;
    }
}
