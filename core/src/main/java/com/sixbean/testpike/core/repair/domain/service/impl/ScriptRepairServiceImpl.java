package com.sixbean.testpike.core.repair.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairItem;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairRecordInput;
import com.sixbean.testpike.core.repair.api.dto.TrialRepairTaskInput;
import com.sixbean.testpike.core.repair.domain.component.RepairOutcome;
import com.sixbean.testpike.core.repair.domain.service.ScriptRepairService;
import com.sixbean.testpike.core.repair.infrastructure.dataobject.ScriptRepairRow;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;


@Service
public class ScriptRepairServiceImpl implements ScriptRepairService {

    
    static final String ROUND_WINDOW = "interval '24 hours'";

    private static final int TEXT_CAP = 2000;

    private final NamedParameterJdbcTemplate named;
    private final ExecTaskService execTaskService;

    public ScriptRepairServiceImpl(NamedParameterJdbcTemplate named,
                                   ExecTaskService execTaskService) {
        this.named = named;
        this.execTaskService = execTaskService;
    }

    @Override
    @Transactional
    public ScriptRepairItem record(ScriptRepairRecordInput input) {
        if (!RepairOutcome.isValid(input.outcome())) {
            throw new ServiceException("修复结局仅支持 1=通过 2=未过 3=闸外跳过（当前 "
                    + input.outcome() + "）", BizErrorCode.VALIDATION_ERROR);
        }
        Long caseExists = named.queryForObject("SELECT count(*) FROM tp_test_case WHERE id = :caseId",
                new MapSqlParameterSource("caseId", input.caseId()), Long.class);
        if (caseExists == null || caseExists == 0L) {
            throw new ServiceException("修复记录挂接的用例不存在（case_id=" + input.caseId() + "）",
                    BizErrorCode.NOT_FOUND);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", input.projectId())
                .addValue("caseId", input.caseId())
                .addValue("sourceTaskId", input.sourceTaskId())
                .addValue("trialTaskId", input.trialTaskId())
                .addValue("proposalSummary", cap(input.proposalSummary()))
                .addValue("outcome", input.outcome())
                .addValue("failNote", cap(input.failNote()));
        Long id = named.queryForObject(
                "INSERT INTO tp_script_repair"
                        + " (project_id, case_id, source_task_id, trial_task_id, round,"
                        + "  proposal_summary, outcome, fail_note)"
                        + " SELECT :projectId, :caseId, :sourceTaskId, :trialTaskId,"
                        + "       (SELECT count(*) + 1 FROM tp_script_repair w"
                        + "         WHERE w.case_id = :caseId"
                        + "           AND w.created_at >= now() - " + ROUND_WINDOW + "),"
                        + "       :proposalSummary, :outcome, :failNote"
                        + " RETURNING id",
                params, Long.class);
        return getById(id);
    }

    
    @Override
    public long trialTask(TrialRepairTaskInput input) {
        List<Map<String, Object>> source = named.query(
                "SELECT project_id, case_id, schema_version, env_base_url, browser"
                        + " FROM tp_exec_task WHERE id = :sourceTaskId",
                new MapSqlParameterSource("sourceTaskId", input.sourceTaskId()),
                new ColumnMapRowMapper());
        if (source.isEmpty()) {
            throw new ServiceException("来源任务不存在（task_id=" + input.sourceTaskId()
                    + "），无法发起修复验证", BizErrorCode.NOT_FOUND);
        }
        Map<String, Object> row = source.get(0);
        long projectId = ((Number) row.get("project_id")).longValue();
        long caseId = ((Number) row.get("case_id")).longValue();
        String schemaVersion = input.schemaVersion() == null || input.schemaVersion().isBlank()
                ? (String) row.get("schema_version") : input.schemaVersion();
        List<Long> envIds = named.queryForList(
                "SELECT id FROM tp_project_env WHERE project_id = :projectId AND base_url = :url"
                        + " ORDER BY id LIMIT 1",
                new MapSqlParameterSource().addValue("projectId", projectId)
                        .addValue("url", row.get("env_base_url")), Long.class);
        if (envIds.isEmpty()) {
            throw new ServiceException("原执行环境已删除或基址变更，无法发起修复验证（task_id="
                    + input.sourceTaskId() + "）", BizErrorCode.NOT_FOUND);
        }
        String browser = (String) row.get("browser");
        return execTaskService.create(new ExecTaskCreateInput(projectId, caseId, schemaVersion,
                input.actionJson(), envIds.get(0), null, null, browser));
    }

    @Override
    public long countRoundsWithin24h(long caseId) {
        Long n = named.queryForObject(
                "SELECT count(*) FROM tp_script_repair WHERE case_id = :caseId"
                        + " AND created_at >= now() - " + ROUND_WINDOW,
                new MapSqlParameterSource("caseId", caseId), Long.class);
        return n != null ? n : 0L;
    }

    @Override
    public PageResult<ScriptRepairItem> list(Long projectId, Long caseId, int page, int size) {
        int safeSize = PageQueryPolicy.enforceSize(size);
        long offset = (long) Math.max(page - 1, 0) * safeSize;

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (projectId != null) {
            where.append(" AND project_id = :projectId");
            params.addValue("projectId", projectId);
        }
        if (caseId != null) {
            where.append(" AND case_id = :caseId");
            params.addValue("caseId", caseId);
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_script_repair" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<ScriptRepairItem> list = named.query(
                "SELECT id, project_id, case_id, source_task_id, trial_task_id, round,"
                        + " proposal_summary, outcome, fail_note, created_at"
                        + " FROM tp_script_repair" + where
                        + " ORDER BY id DESC LIMIT :size OFFSET :offset",
                params, new BeanPropertyRowMapper<>(ScriptRepairRow.class))
                .stream().map(ScriptRepairRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    private ScriptRepairItem getById(Long id) {
        List<ScriptRepairItem> rows = named.query(
                "SELECT id, project_id, case_id, source_task_id, trial_task_id, round,"
                        + " proposal_summary, outcome, fail_note, created_at"
                        + " FROM tp_script_repair WHERE id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(ScriptRepairRow.class))
                .stream().map(ScriptRepairRow::toInfo).toList();
        if (rows.isEmpty()) {
            throw new ServiceException("修复记录写入后读取失败（id=" + id + "）", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static String cap(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= TEXT_CAP ? text : text.substring(0, TEXT_CAP);
    }
}
