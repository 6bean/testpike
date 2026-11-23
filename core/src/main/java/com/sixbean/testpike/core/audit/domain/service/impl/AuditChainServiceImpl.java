package com.sixbean.testpike.core.audit.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.ChainVerifyResult;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.api.dto.ChainVerifyOutcome;
import com.sixbean.testpike.core.audit.domain.component.AuditChainVerifier;
import com.sixbean.testpike.core.audit.domain.service.AuditChainService;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.report.domain.component.ExportTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class AuditChainServiceImpl implements AuditChainService {

    private static final Logger log = LoggerFactory.getLogger(AuditChainServiceImpl.class);

 
    static final String TASK_TYPE = "audit_chain_verify";
    static final String KEY_THRESHOLD = "audit.chain-verify.async-threshold-rows";
    private static final int DEFAULT_THRESHOLD = 100000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final AuditChainVerifier verifier;
    private final AuditLogService auditLogService;
    private final ConfigService configService;
    private final ExportTaskExecutor exportTaskExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditChainServiceImpl(JdbcTemplate jdbcTemplate, AuditChainVerifier verifier,
                                 AuditLogService auditLogService, ConfigService configService,
                                 ExportTaskExecutor exportTaskExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.verifier = verifier;
        this.auditLogService = auditLogService;
        this.configService = configService;
        this.exportTaskExecutor = exportTaskExecutor;
    }

    @Override
    public ChainVerifyResult verify(long operatorId) {
        long rows = countChainRows();
        if (rows <= threshold()) {
            ChainVerifyOutcome outcome = verifier.verify();
            recordVerifyAudit(operatorId, outcome, "sync");
            return syncResult(outcome);
        }
        List<Long> running = jdbcTemplate.queryForList(
                "SELECT id FROM tp_export_task WHERE report_type = ? AND status = 1"
                        + " ORDER BY id DESC LIMIT 1", Long.class, TASK_TYPE);
        if (!running.isEmpty()) {
            return ChainVerifyResult.running(running.get(0));
        }
        Long taskId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_export_task (project_id, report_type, masked, status, row_count, created_by)
                VALUES (NULL, ?, 0, 1, ?, ?) RETURNING id
                """, Long.class, TASK_TYPE, rows, operatorId);
        long submittedId = taskId;
        exportTaskExecutor.submit(() -> doAsyncVerify(submittedId, operatorId));
        return ChainVerifyResult.submitted(submittedId);
    }

    @Override
    public ChainVerifyResult taskResult(long taskId) {
        List<Object[]> rows = jdbcTemplate.query("""
                SELECT status, result_json, to_char(finished_at, 'YYYY-MM-DD HH24:MI:SS')
                FROM tp_export_task WHERE id = ? AND report_type = ?
                """, (rs, i) -> new Object[]{rs.getInt(1), rs.getString(2), rs.getString(3)},
                taskId, TASK_TYPE);
        if (rows.isEmpty()) {
            throw new ServiceException("链校验任务不存在", BizErrorCode.NOT_FOUND);
        }
        Object[] row = rows.get(0);
        int status = (Integer) row[0];
        if (status == 1) {
            return ChainVerifyResult.running(taskId);
        }
        if (status == 3) {
            return ChainVerifyResult.failed(taskId);
        }
        try {
            JsonNode json = objectMapper.readTree(row[1] == null ? "{}" : (String) row[1]);
            return new ChainVerifyResult(true, taskId, 2,
                    json.path("valid").asBoolean(false),
                    json.hasNonNull("brokenAtId") ? json.get("brokenAtId").asLong() : null,
                    json.hasNonNull("checkedCount") ? json.get("checkedCount").asLong() : null,
                    (String) row[2]);
        } catch (Exception e) {
            throw new IllegalStateException("链校验任务结果解析失败: " + e.getMessage(), e);
        }
    }


    
    private void doAsyncVerify(long taskId, long operatorId) {
        try {
            ChainVerifyOutcome outcome = verifier.verify();
            jdbcTemplate.update("""
                    UPDATE tp_export_task SET status = 2, result_json = ?::jsonb,
                           row_count = ?, finished_at = now() WHERE id = ?
                    """, resultJson(outcome), outcome.checkedCount(), taskId);
            recordVerifyAudit(operatorId, outcome, "async");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            jdbcTemplate.update(
                    "UPDATE tp_export_task SET status = 3, error_detail = ?, finished_at = now() WHERE id = ?",
                    msg.length() > 500 ? msg.substring(0, 500) : msg, taskId);
        }
    }

    private String resultJson(ChainVerifyOutcome outcome) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valid", outcome.valid());
            m.put("brokenAtId", outcome.brokenAtId());
            m.put("checkedCount", outcome.checkedCount());
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("链校验结果序列化失败", e);
        }
    }

    
    private void recordVerifyAudit(long operatorId, ChainVerifyOutcome outcome, String mode) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("valid", outcome.valid());
        after.put("brokenAtId", outcome.brokenAtId());
        after.put("checkedCount", outcome.checkedCount());
        after.put("mode", mode);
        auditLogService.record(new AuditLogInput(operatorId, null, "audit_chain", "0",
                "verify", null, after));
    }

    private ChainVerifyResult syncResult(ChainVerifyOutcome outcome) {
        return ChainVerifyResult.syncDone(outcome.valid(), outcome.brokenAtId(),
                outcome.checkedCount(), LocalDateTime.now().format(TS));
    }

    private long countChainRows() {
        Long c = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_audit_log", Long.class);
        return c == null ? 0 : c;
    }

    
    private int threshold() {
        String raw = configService.getValue(KEY_THRESHOLD, "");
        try {
            int v = Integer.parseInt(raw.trim());
            if (v > 0) {
                return v;
            }
        } catch (RuntimeException ignored) {
        }
        return DEFAULT_THRESHOLD;
    }
}
