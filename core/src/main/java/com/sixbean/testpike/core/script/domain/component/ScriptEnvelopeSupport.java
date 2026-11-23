package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.common.util.JsonOutputExtractor;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;


@Component
public class ScriptEnvelopeSupport {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScriptEnvelopeSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public String ensureEnvelope(String actionJson, CaseInfo caseInfo) {
        JsonNode root;
        try {
            root = objectMapper.readTree(JsonOutputExtractor.extractJson(actionJson));
        } catch (Exception e) {
            return actionJson;
        }
        if (!root.isObject()) {
            return actionJson;
        }
        ObjectNode obj = (ObjectNode) root;
        boolean changed = false;
        if (obj.path("version").asText("").isBlank()) {
            obj.put("version", ActionJsonValidator.ACTION_JSON_VERSION);
            changed = true;
        }
        if (obj.path("caseId").asText("").isBlank()) {
            obj.put("caseId", caseInfo.caseNo());
            changed = true;
        }
        if (obj.path("env").isMissingNode() || obj.path("env").isNull()) {
            ObjectNode env = objectMapper.createObjectNode();
            String envName = resolveEnvName(caseInfo.projectId(), caseInfo.id());
            if (envName != null) {
                env.put("ENV", envName);
            }
            obj.set("env", env);
            changed = true;
        }
        if (!changed) {
            return actionJson;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return actionJson;
        }
    }

    
    private String resolveEnvName(long projectId, long caseId) {
        String recent = jdbcTemplate.query("""
                        SELECT e.name
                        FROM tp_exec_task t
                        JOIN tp_exec_batch b ON b.id = t.batch_id
                        JOIN tp_project_env e ON e.id = b.env_id
                        WHERE t.project_id = ? AND t.case_id = ?
                        ORDER BY t.id DESC LIMIT 1
                        """, (rs, n) -> rs.getString(1), projectId, caseId)
                .stream().findFirst().orElse(null);
        if (recent != null) {
            return recent;
        }
        return jdbcTemplate.query(
                "SELECT name FROM tp_project_env WHERE project_id = ? ORDER BY id LIMIT 1",
                (rs, n) -> rs.getString(1), projectId)
                .stream().findFirst().orElse(null);
    }
}
