package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleCacheHitRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleRecordRequest;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAiSampleService;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class AppautoAiSampleServiceImpl implements AppautoAiSampleService {

 
    // WE S2 "dom" is the Web-family provenance (anchor rebuilt from a DOM
    // snapshot after a redesign). Single source for suggestion + sample doors.
    static final Set<String> HIT_SOURCES = Set.of("vision", "a11y", "cache", "manual", "dom");

 
    static final Set<String> REVIEW_RESULTS = Set.of("confirmed", "rejected");

    private static final String SELECT_COLUMNS = """
            id, project_id, case_id, device_id, step_no, anchor, hit_source,
            review_result, x, y, source_task_id, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ExecTaskService execTaskService;

    public AppautoAiSampleServiceImpl(NamedParameterJdbcTemplate jdbc,
                                      ExecTaskService execTaskService) {
        this.jdbc = jdbc;
        this.execTaskService = execTaskService;
    }

    @Override
    public long recordSample(AppautoAiSampleInput input) {
        validate(input);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", input.projectId())
                .addValue("cid", input.caseId())
                .addValue("dev", input.deviceId())
                .addValue("stepNo", input.stepNo())
                .addValue("anchor", input.anchor().trim())
                .addValue("source", normalize(input.hitSource()))
                .addValue("result", normalize(input.reviewResult()))
                .addValue("x", input.x())
                .addValue("y", input.y())
                .addValue("srcTask", input.sourceTaskId());
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_appauto_ai_sample
                    (project_id, case_id, device_id, step_no, anchor, hit_source,
                     review_result, x, y, source_task_id)
                VALUES (:pid, :cid, :dev, :stepNo, :anchor, :source,
                        :result, :x, :y, :srcTask)
                RETURNING id
                """, params, Long.class);
        return id == null ? 0L : id;
    }

    @Override
    public Map<String, Object> findCacheHit(long caseId, Long deviceId, int stepNo,
                                            String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cid", caseId)
                .addValue("dev", deviceId)
                .addValue("stepNo", stepNo)
                .addValue("anchor", anchor.trim());
        List<Map<String, Object>> rows = jdbc.queryForList(cacheHitSql(), params);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("sampleId", row.get("id"));
        hit.put("source", row.get("hit_source"));
        hit.put("x", row.get("x"));
        hit.put("y", row.get("y"));
        hit.put("anchor", row.get("anchor"));
        return hit;
    }

    
    private String cacheHitSql() {
        return """
                SELECT id, hit_source, x, y, anchor
                FROM tp_appauto_ai_sample
                WHERE case_id = :cid AND step_no = :stepNo AND anchor = :anchor
                  AND review_result = 'confirmed' AND x IS NOT NULL AND y IS NOT NULL
                  AND device_id IS NOT DISTINCT FROM :dev
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """;
    }

    @Override
    public List<Map<String, Object>> listSamples(long projectId, long caseId, int limit) {
        int cap = Math.min(Math.max(limit, 1), 500);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("cid", caseId)
                .addValue("cap", cap);
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + """
                FROM tp_appauto_ai_sample
                WHERE project_id = :pid AND case_id = :cid
                ORDER BY created_at DESC, id DESC
                LIMIT :cap
                """, params).stream().map(AppautoAiSampleServiceImpl::toCamel).toList();
    }

    
    private static Map<String, Object> toCamel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sampleId", row.get("id"));
        out.put("projectId", row.get("project_id"));
        out.put("caseId", row.get("case_id"));
        out.put("deviceId", row.get("device_id"));
        out.put("stepNo", row.get("step_no"));
        out.put("anchor", row.get("anchor"));
        out.put("hitSource", row.get("hit_source"));
        out.put("reviewResult", row.get("review_result"));
        out.put("x", row.get("x"));
        out.put("y", row.get("y"));
        out.put("sourceTaskId", row.get("source_task_id"));
        out.put("createdAt", row.get("created_at"));
        return out;
    }

    @Override
    public Map<String, Object> hitRateStats(long projectId, long caseId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("cid", caseId);
        Map<String, Object> agg = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE review_result = 'confirmed') AS confirmed,
                       count(*) FILTER (WHERE review_result = 'rejected') AS rejected
                FROM tp_appauto_ai_sample
                WHERE project_id = :pid AND case_id = :cid
                """, params);
        long total = ((Number) agg.get("total")).longValue();
        long confirmed = ((Number) agg.get("confirmed")).longValue();
        long rejected = ((Number) agg.get("rejected")).longValue();
        double rate = total == 0 ? 0.0 : Math.round(confirmed * 10000.0 / total) / 10000.0;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("confirmed", confirmed);
        stats.put("rejected", rejected);
        stats.put("confirmRate", rate);
        return stats;
    }

    @Override
    public long recordFromExecutor(AppautoAiSampleRecordRequest request) {
        ExecTaskInfo task = resolveTask(request == null ? null : request.taskId(),
                "回灌定位样本");
        return recordSample(new AppautoAiSampleInput(task.projectId(), task.caseId(),
                request.deviceId(), request.stepNo(), request.anchor(),
                request.hitSource(), request.reviewResult(), request.x(), request.y(),
                task.id()));
    }

    @Override
    public Map<String, Object> cacheHitForExecutor(AppautoAiSampleCacheHitRequest request) {
        ExecTaskInfo task = resolveTask(request == null ? null : request.taskId(),
                "读取同屏历史样本");
        if (request.stepNo() == null) {
            throw new ServiceException("读取同屏历史样本缺少步序，无法核对同屏锚，已拒绝读票"
                    + "——请在「定位样本库」查询时补填步序后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> hit = findCacheHit(task.caseId(), request.deviceId(),
                request.stepNo(), request.anchor());
        return hit == null ? Map.of() : hit;
    }

    
    private ExecTaskInfo resolveTask(Long taskId, String actionWords) {
        if (taskId == null) {
            throw new ServiceException(actionWords + "缺少执行任务号，无法核对用例归属，已拒绝"
                    + "——请补传执行任务号（可在「执行监看」查看当前任务号）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return execTaskService.get(taskId);
    }

    private void validate(AppautoAiSampleInput input) {
        if (input.projectId() == null || input.caseId() == null) {
            throw new ServiceException("定位样本缺少项目或用例号，无法入库"
                    + "——请从具体用例的「用例详情」发起采集，项目与用例号随入口带入",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.stepNo() == null || input.stepNo() < 0) {
            throw new ServiceException("定位样本步号非法（须 ≥0）——请把步号改成 0 或更大整数"
                    + "（正确写法示例：1），步号可在「用例详情」步骤列表核对",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.anchor() == null || input.anchor().isBlank()) {
            throw new ServiceException("定位样本缺少同屏锚签名（读写样同源锚，缺锚=样本不可用）"
                    + "——请在采集入口保留锚签名，或到「定位样本库」手工补录时粘贴锚值",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String source = normalize(input.hitSource());
        if (!HIT_SOURCES.contains(source)) {
            throw new ServiceException("定位样本出处非法：" + input.hitSource()
                    + "（合法值 vision/a11y/cache/manual）——请在「定位样本库」表单的出处选择框改成合法值",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String result = normalize(input.reviewResult());
        if (!REVIEW_RESULTS.contains(result)) {
            throw new ServiceException("定位样本复核结论非法：" + input.reviewResult()
                    + "（合法值 confirmed/rejected）——请在「定位样本库」复核表单改成合法值",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("confirmed".equals(result) && (input.x() == null || input.y() == null)) {
            throw new ServiceException("复核确认样本缺少坐标——确认票必须带可复用的点击点位"
                    + "（无坐标=仅可作驳回留痕样本）——请在「定位样本库」复核弹窗先在画面取点再确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
