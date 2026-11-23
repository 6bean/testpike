package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.judge.api.dto.JudgeArtifactGrantView;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.domain.component.JudgeJsonCodec;
import com.sixbean.testpike.core.judge.domain.service.JudgeRecordService;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.JudgeRecordRow;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class JudgeRecordServiceImpl implements JudgeRecordService {

    private static final String SELECT_COLUMNS = """
            SELECT id, run_id, sample_id, rule_score,
                   rule_hits::text AS ruleHitsJson,
                   llm_scores::text AS llmScoresJson,
                   final_score, reasons::text AS reasonsJson,
                   latency_ms, tokens, manual_final_score
            FROM tp_judge_record
            """;

    private final NamedParameterJdbcTemplate named;
    private final JudgeJsonCodec codec;
    private final JudgeRunService runService;
    private final ArtifactStorage artifactStorage;

    public JudgeRecordServiceImpl(NamedParameterJdbcTemplate named, JudgeJsonCodec codec,
                                  JudgeRunService runService, ArtifactStorage artifactStorage) {
        this.named = named;
        this.codec = codec;
        this.runService = runService;
        this.artifactStorage = artifactStorage;
    }

    @Override
    @Transactional
    public RecordView saveScore(RecordInput input) {
        if (input.runId() == null) {
            throw new ValidationException("必须指定评测运行（runId 不能为空）");
        }
        RunView run = runService.detail(input.runId());
        if (run == null) {
            throw new ValidationException("评测运行不存在（id=" + input.runId() + "）");
        }
        if (input.sampleId() == null) {
            throw new ValidationException("必须指定被评样本（sampleId 不能为空）");
        }
        Integer owned = named.queryForObject("SELECT count(*) FROM tp_judge_sample "
                        + "WHERE id = :sid AND set_name = :setName AND version = :version",
                new MapSqlParameterSource()
                        .addValue("sid", input.sampleId())
                        .addValue("setName", run.evalsetName())
                        .addValue("version", run.evalsetVersion()),
                Integer.class);
        if (owned == null || owned == 0) {
            throw new ValidationException("样本（id=" + input.sampleId() + "）不存在或不属于本运行"
                    + "绑定的评测集「" + run.evalsetName() + "」版本 " + run.evalsetVersion()
                    + "——禁串集落分（归因红线）");
        }
        if (input.ruleScore() == null && (input.llmScores() == null || input.llmScores().isEmpty())) {
            throw new ValidationException("规则分与 LLM 分不能同时为空——无任何评分依据的落分无意义");
        }
        requireRange("规则分", input.ruleScore());
        if (input.llmScores() != null) {
            for (Map.Entry<String, Double> e : input.llmScores().entrySet()) {
                if (e.getValue() < 0 || e.getValue() > 100) {
                    throw new ValidationException("维度「" + e.getKey() + "」LLM 分须在 0~100 之间（收到 "
                            + e.getValue() + "）");
                }
            }
        }

        int finalScore = composite(run.rubricSnapshot(), input.ruleScore(), input.llmScores());

        Long id = named.queryForObject("INSERT INTO tp_judge_record "
                        + "(run_id, sample_id, rule_score, rule_hits, llm_scores, final_score, "
                        + "reasons, latency_ms, tokens) "
                        + "VALUES (:runId, :sampleId, :ruleScore, CAST(:hits AS jsonb), "
                        + "CAST(:llm AS jsonb), :finalScore, CAST(:reasons AS jsonb), "
                        + ":latency, :tokens) "
                        + "ON CONFLICT (run_id, sample_id) DO UPDATE SET "
                        + "rule_score = EXCLUDED.rule_score, rule_hits = EXCLUDED.rule_hits, "
                        + "llm_scores = EXCLUDED.llm_scores, final_score = EXCLUDED.final_score, "
                        + "reasons = EXCLUDED.reasons, latency_ms = EXCLUDED.latency_ms, "
                        + "tokens = EXCLUDED.tokens, updated_at = now() "
                        + "RETURNING id",
                new MapSqlParameterSource()
                        .addValue("runId", input.runId())
                        .addValue("sampleId", input.sampleId())
                        .addValue("ruleScore", input.ruleScore())
                        .addValue("hits", codec.write(input.ruleHits()))
                        .addValue("llm", codec.write(input.llmScores()))
                        .addValue("finalScore", finalScore)
                        .addValue("reasons", codec.write(input.reasons()))
                        .addValue("latency", input.latencyMs())
                        .addValue("tokens", input.tokens()), Long.class);

        JudgeRecordRow row = named.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(JudgeRecordRow.class)).get(0);
        return toView(row);
    }

    
    @Override
    public JudgeArtifactGrantView artifactGrant(long runId, long sampleId) {
        if (runId <= 0) {
            throw new ValidationException("必须指定评测运行（runId 须为正整数，收到 " + runId + "）");
        }
        if (sampleId <= 0) {
            throw new ValidationException("必须指定样本（sampleId 须为正整数，收到 " + sampleId + "）");
        }
        StorageGrant grant = artifactStorage.grantJudgeArtifactUpload(runId, sampleId);
        return new JudgeArtifactGrantView(runId, grant.key(), grant.url());
    }

    
    private int composite(Map<String, Object> snapshot, Integer ruleScore,
                          Map<String, Double> llmScores) {
        if (llmScores == null || llmScores.isEmpty()) {
            return ruleScore;
        }
        double llmWeighted = weightedDimensionMean(snapshot, llmScores);
        if (ruleScore == null) {
            return (int) Math.round(llmWeighted);
        }
        double wRule = numberOr(snapshot.get("wRule"), 0.4);
        double wLlm = numberOr(snapshot.get("wLlm"), 0.6);
        return (int) Math.round(ruleScore * wRule + llmWeighted * wLlm);
    }

    
    private double weightedDimensionMean(Map<String, Object> snapshot, Map<String, Double> llmScores) {
        Map<String, Double> weights = dimensionWeights(snapshot);
        double sum = 0;
        double weightTotal = 0;
        for (Map.Entry<String, Double> e : llmScores.entrySet()) {
            double w = weights.getOrDefault(e.getKey(), 1.0);
            sum += e.getValue() * w;
            weightTotal += w;
        }
        if (weightTotal <= 0) {
            return llmScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        return sum / weightTotal;
    }

    private Map<String, Double> dimensionWeights(Map<String, Object> snapshot) {
        Map<String, Double> out = new java.util.HashMap<>();
        Object raw = snapshot.get("dimensions");
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m && m.get("name") != null && m.get("weight") != null) {
                out.put(String.valueOf(m.get("name")), numberOr(m.get("weight"), 0));
            }
        }
        return out;
    }

    private double numberOr(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private void requireRange(String label, Integer score) {
        if (score != null && (score < 0 || score > 100)) {
            throw new ValidationException(label + "须在 0~100 之间（收到 " + score + "）");
        }
    }

    private RecordView toView(JudgeRecordRow row) {
        return new RecordView(row.getId(), row.getRunId(), row.getSampleId(),
                row.getRuleScore(), readStringList(row.getRuleHitsJson()),
                codec.readObjectMap(row.getLlmScoresJson()), row.getFinalScore(),
                codec.readObjectMap(row.getReasonsJson()), row.getLatencyMs(),
                row.getTokens(), row.getManualFinalScore());
    }

    private List<String> readStringList(String json) {
        List<String> out = new ArrayList<>(codec.readStringList(json));
        return out;
    }
}
