package com.sixbean.testpike.core.ai.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInfo;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewInput;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewItem;
import com.sixbean.testpike.core.ai.api.dto.ScenarioPreviewResult;
import com.sixbean.testpike.core.ai.domain.service.ScenarioPreviewService;
import com.sixbean.testpike.core.ai.infrastructure.remote.client.AiServiceScenarioPreviewClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stage0 scenario preview ledger implementation .
 * <p>One line = one enumeration through the ai-service leg = one stored draft
 * row; the switch-off shape writes nothing so the legacy one-case-per-line
 * flow costs zero and drifts zero. Rows keep the ring accounting (rounds,
 * model calls, residual disputes) next to the scenario JSONB so the selection
 * UI can surface a non-converged list honestly.
 */
@Service
public class ScenarioPreviewServiceImpl implements ScenarioPreviewService {

    private final JdbcTemplate jdbcTemplate;
    private final AiServiceScenarioPreviewClient previewClient;
    private final ObjectMapper objectMapper;

    public ScenarioPreviewServiceImpl(JdbcTemplate jdbcTemplate,
                                      AiServiceScenarioPreviewClient previewClient,
                                      ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.previewClient = previewClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public long create(ScenarioPreviewInput input) {
        if (input.requirementText() == null || input.requirementText().isBlank()) {
            throw new ServiceException("需求内容不能为空，请在「AI 生成」页先输入需求内容或从「需求点管理」选取需求点再发起场景预览", BizErrorCode.VALIDATION_ERROR);
        }
        ScenarioPreviewResult result = previewClient.enumerate(input.projectId(),
                input.requirementText(), input.priority(), input.operatorId(), input.seq());
        if (!result.expansionEnabled()) {
            return 0L;
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_scenario_preview (batch_id, seq, project_id, operator_id,
                    requirement_text, priority, expansion_enabled, scenarios, residual_claims,
                    rounds_used, model_calls, converged)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, ?::jsonb, ?::jsonb, ?, ?, ?)
                RETURNING id
                """, Long.class, input.batchId(), input.seq(), input.projectId(),
                input.operatorId(), input.requirementText(), input.priority(),
                writeJson(result.scenarios()), writeJson(result.residualClaims()),
                result.roundsUsed(), result.modelCalls(), result.converged());
    }

    @Override
    public List<ScenarioPreviewInfo> listByBatch(long batchId) {
        return jdbcTemplate.query("""
                SELECT id, batch_id, seq, project_id, requirement_text, priority,
                       expansion_enabled, scenarios::text, residual_claims::text,
                       rounds_used, model_calls, converged
                FROM tp_ai_scenario_preview WHERE batch_id = ? ORDER BY seq, id
                """, (rs, rowNum) -> new ScenarioPreviewInfo(
                rs.getLong("id"),
                (Long) rs.getObject("batch_id"),
                rs.getInt("seq"),
                rs.getLong("project_id"),
                rs.getString("requirement_text"),
                rs.getInt("priority"),
                rs.getBoolean("expansion_enabled"),
                readItems(rs.getString("scenarios")),
                readStrings(rs.getString("residual_claims")),
                rs.getInt("rounds_used"),
                rs.getInt("model_calls"),
                rs.getBoolean("converged")),
                batchId);
    }

    @Override
    public List<Integer> scenarioCountsByBatch(long batchId) {
        return jdbcTemplate.query(
                "SELECT jsonb_array_length(scenarios) FROM tp_ai_scenario_preview "
                        + "WHERE batch_id = ? ORDER BY seq, id",
                (rs, rowNum) -> rs.getInt(1), batchId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException broken) {
            throw new ServiceException("场景预览数据序列化失败，请在「AI 生成」页重新发起场景预览；反复失败请联系管理员查看服务端日志", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private List<ScenarioPreviewItem> readItems(String json) {
        try {
            return objectMapper.readerForListOf(ScenarioPreviewItem.class).readValue(json);
        } catch (JsonProcessingException corrupted) {
            throw new ServiceException("场景预览数据损坏，无法读取圈选清单，请在「AI 生成」页对该批次重新生成场景预览", BizErrorCode.INTERNAL_ERROR);
        }
    }

    private List<String> readStrings(String json) {
        try {
            return objectMapper.readerForListOf(String.class).readValue(json);
        } catch (JsonProcessingException corrupted) {
            throw new ServiceException("场景预览数据损坏，无法读取残余分歧，请在「AI 生成」页对该批次重新生成场景预览", BizErrorCode.INTERNAL_ERROR);
        }
    }
}
