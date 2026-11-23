package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.executor.api.dto.BatchCancelResult;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportNotifyService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunStopService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class PerfRunStopServiceImpl implements PerfRunStopService {

    private static final int STATUS_MANUAL_STOP = 4;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfRunService perfRunService;
    private final ExecTaskService execTaskService;
    private final PerfReportNotifyService perfReportNotifyService;

    public PerfRunStopServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                  PerfRunService perfRunService, ExecTaskService execTaskService,
                                  PerfReportNotifyService perfReportNotifyService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.perfRunService = perfRunService;
        this.execTaskService = execTaskService;
        this.perfReportNotifyService = perfReportNotifyService;
    }

    @Override
    @Transactional
    @Audit(objectType = "perf_run", action = "stop", objectId = "#runId")
    public int stopRun(long projectId, long runId, Long operatorId) {
        perfRunService.finish(projectId, runId, STATUS_MANUAL_STOP,
                "操作人人工停压（操作人 id=" + operatorId + "，终止指令将随下一次心跳下发执行器终止引擎）");
        // GB-3 automatic SLA red-light alert on the manual-stop terminal leg
        // (send itself is after-commit deferred + dedup guarded; green runs silent).
        perfReportNotifyService.sendSlaBreachCard(projectId, runId);
        return execTaskService.batchCancel(operatorId == null ? 0L : operatorId,
                findOpenTaskIdsForRun(projectId, runId)).cancelled();
    }

    @Override
    @Transactional
    @Audit(objectType = "perf_run", action = "stop_all", objectId = "#projectId")
    public int stopAll(long projectId, Long operatorId) {
        List<Long> running = jdbc.queryForList("SELECT id FROM tp_perf_run "
                        + "WHERE project_id = :p AND status = 1 ORDER BY id",
                new MapSqlParameterSource("p", projectId), Long.class);
        for (Long runId : running) {
            stopRun(projectId, runId, operatorId);
        }
        return running.size();
    }

    
    private List<Long> findOpenTaskIdsForRun(long projectId, long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, action_json FROM tp_exec_task "
                        + "WHERE project_id = :p AND schema_version = 'perf-v1' AND status IN (1, 2)",
                new MapSqlParameterSource("p", projectId));
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (runId == envelopeRunId(String.valueOf(row.get("action_json")))) {
                ids.add(((Number) row.get("id")).longValue());
            }
        }
        return ids;
    }

    
    private long envelopeRunId(String actionJson) {
        try {
            return objectMapper.readTree(actionJson).path("runId").asLong(0);
        } catch (Exception corrupt) {
            return 0L;
        }
    }
}
