package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.SqlStatementContract;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class DbExecAuditSupport {

    
    static final String AUDIT_ACTION = "db_exec";
    static final String OBJECT_TYPE = "exec_task";
    
    private static final Long SYSTEM_OPERATOR_ID = 0L;
    
    private static final int SQL_DIGEST_MAX = 200;

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public DbExecAuditSupport(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    
    public void onResultSubmitted(ExecTaskInfo task, Object stepsTimeline) {
        JsonNode timeline = toNode(stepsTimeline);
        int executed = countSqlExecSteps(timeline);
        if (executed == 0) {
            return;
        }
        List<Map<String, Object>> digests = sqlExecDigests(toNode(task.actionJson()));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("executedSqlExecSteps", executed);
        before.put("statements", digests);
        auditLogService.record(new AuditLogInput(SYSTEM_OPERATOR_ID, task.projectId(),
                OBJECT_TYPE, String.valueOf(task.id()), AUDIT_ACTION, before, null));
    }

    
    private List<Map<String, Object>> sqlExecDigests(JsonNode root) {
        List<Map<String, Object>> digests = new ArrayList<>();
        if (root == null) {
            return digests;
        }
        collectDigests(root.path("setup"), digests);
        collectDigests(root.path("steps"), digests);
        return digests;
    }

    private void collectDigests(JsonNode stepArray, List<Map<String, Object>> digests) {
        if (!stepArray.isArray()) {
            return;
        }
        for (JsonNode step : stepArray) {
            if (!step.isObject()) {
                continue;
            }
            if ("sql.exec".equals(step.path("action").asText(""))) {
                Map<String, Object> digest = new LinkedHashMap<>();
                digest.put("conn", step.path("conn").asText(""));
                digest.put("sql", digestSql(step.path("sql").asText("")));
                digests.add(digest);
            }
            collectDigests(step.path("steps"), digests);
            collectDigests(step.path("elseSteps"), digests);
        }
    }

    
    private int countSqlExecSteps(JsonNode node) {
        if (node == null) {
            return 0;
        }
        if (node.isArray()) {
            int count = 0;
            for (JsonNode item : node) {
                count += countSqlExecSteps(item);
            }
            return count;
        }
        if (!node.isObject()) {
            return 0;
        }
        int self = "sql.exec".equals(node.path("action").asText("")) ? 1 : 0;
        return self + countSqlExecSteps(node.path("steps"))
                + countSqlExecSteps(node.path("children"));
    }

    
    private String digestSql(String sql) {
        String skeleton = SqlStatementContract.stripLiteralsAndComments(sql)
                .replaceAll("\\s+", " ").trim();
        return skeleton.length() <= SQL_DIGEST_MAX
                ? skeleton : skeleton.substring(0, SQL_DIGEST_MAX) + "…";
    }

    private JsonNode toNode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof String s
                    ? (s.isBlank() ? null : objectMapper.readTree(s))
                    : objectMapper.valueToTree(value);
        } catch (Exception e) {
            return null;
        }
    }
}
