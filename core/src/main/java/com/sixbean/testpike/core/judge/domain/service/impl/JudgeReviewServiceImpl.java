package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewView;
import com.sixbean.testpike.core.judge.api.dto.ReviewQueueView;
import com.sixbean.testpike.core.judge.domain.component.JudgeJsonCodec;
import com.sixbean.testpike.core.judge.domain.service.JudgeReviewService;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.JudgeReviewRow;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.ReviewQueueRow;
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
public class JudgeReviewServiceImpl implements JudgeReviewService {

    private static final String SELECT_COLUMNS = """
            SELECT id, run_id, record_id, reviewer, dimension,
                   org_score, new_score, reason, created_at
            FROM tp_judge_review
            """;

    
    private static final int MAX_QUEUE_PAGE_SIZE = 500;

    
    private static final String SELECT_QUEUE = """
            SELECT r.id AS record_id, r.run_id, r.sample_id, r.rule_score, r.final_score,
                   r.llm_scores::text AS llm_scores_json, r.reasons::text AS reasons_json,
                   ru.rubric_snapshot::text AS rubric_snapshot_json, r.created_at
            FROM tp_judge_record r
            JOIN tp_judge_run ru ON ru.id = r.run_id
            WHERE NOT EXISTS (SELECT 1 FROM tp_judge_review v WHERE v.record_id = r.id)
              AND COALESCE(
                    (SELECT jsonb_agg(d.value) FROM jsonb_array_elements(ru.rubric_snapshot -> 'dimensions') d
                     WHERE (d.value ->> 'name') IS NOT NULL
                       AND (r.llm_scores -> (d.value ->> 'name')) IS NULL),
                    '[]'::jsonb) <> '[]'::jsonb
            """;

    private final NamedParameterJdbcTemplate named;
    private final JudgeJsonCodec codec;
    private final com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake behaviorOutcomeIntake;

    public JudgeReviewServiceImpl(NamedParameterJdbcTemplate named, JudgeJsonCodec codec,
                                  com.sixbean.testpike.core.behavior.domain.component.BehaviorOutcomeIntake behaviorOutcomeIntake) {
        this.named = named;
        this.codec = codec;
        this.behaviorOutcomeIntake = behaviorOutcomeIntake;
    }

    @Override
    @Transactional
    public JudgeReviewView recordReview(JudgeReviewInput input) {
        if (input == null || input.recordId() == null) {
            throw new ValidationException("必须指定被改判的评分记录（recordId 不能为空）");
        }
        if (input.reviewer() == null) {
            throw new ValidationException("必须指定改判人（reviewer 不能为空——一致率统计归因到人）");
        }
        if (input.newScore() == null || input.newScore() < 0 || input.newScore() > 100) {
            throw new ValidationException("改判分须在 0~100 之间（收到 " + input.newScore() + "）");
        }

        Map<String, Object> record;
        try {
            record = named.queryForMap("SELECT run_id, final_score, llm_scores::text AS llm_scores_json "
                            + "FROM tp_judge_record WHERE id = :id",
                    new MapSqlParameterSource("id", input.recordId()));
        } catch (EmptyResultDataAccessException e) {
            throw new ValidationException("评分记录不存在（id=" + input.recordId()
                    + "）——改判必先有机器落分，禁凭空建人工分");
        }

        String dimension = (input.dimension() == null || input.dimension().isBlank())
                ? null : input.dimension().trim();
        int orgScore = resolveOrgScore(record, dimension, input.recordId());

        Long id = named.queryForObject("INSERT INTO tp_judge_review "
                        + "(run_id, record_id, reviewer, dimension, org_score, new_score, reason) "
                        + "VALUES (:runId, :recordId, :reviewer, :dimension, :orgScore, :newScore, :reason) "
                        + "RETURNING id",
                new MapSqlParameterSource()
                        .addValue("runId", ((Number) record.get("run_id")).longValue())
                        .addValue("recordId", input.recordId())
                        .addValue("reviewer", input.reviewer())
                        .addValue("dimension", dimension)
                        .addValue("orgScore", orgScore)
                        .addValue("newScore", input.newScore())
                        .addValue("reason", input.reason()), Long.class);

        // Outcome ledger intake : a human override of the
        // machine score is a corrected verdict on AI-produced material; the
        // judged record id is the scope reference, no task lineage exists here.
        behaviorOutcomeIntake.recordReviewVerdict(
                com.sixbean.testpike.core.behavior.domain.component.OutcomeChannel.JUDGE_REVIEW,
                id, "corrected", String.valueOf(input.recordId()), null);

        if (dimension == null) {
            named.update("UPDATE tp_judge_record SET manual_final_score = :score, "
                            + "updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("score", input.newScore())
                            .addValue("id", input.recordId()));
        }

        JudgeReviewRow row = named.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(JudgeReviewRow.class)).get(0);
        return toView(row);
    }

    @Override
    public List<JudgeReviewView> listReviews(Long recordId) {
        if (recordId == null) {
            throw new ValidationException("必须指定评分记录（recordId 不能为空）");
        }
        return named.query(SELECT_COLUMNS + " WHERE record_id = :recordId "
                        + "ORDER BY created_at DESC, id DESC",
                new MapSqlParameterSource("recordId", recordId),
                new BeanPropertyRowMapper<>(JudgeReviewRow.class))
                .stream().map(this::toView).toList();
    }

    
    private static final String NO_EVIDENCE_REASON_KEY = "判分无据剔除维度";

    @Override
    public List<ReviewQueueView> reviewQueue(Long runId, int page, int size) {
        if (page < 0) {
            throw new ValidationException("页码不能为负（收到 " + page + "）——人审队列从第 0 页起");
        }
        if (size < 1 || size > MAX_QUEUE_PAGE_SIZE) {
            throw new ValidationException("每页条数须在 1~" + MAX_QUEUE_PAGE_SIZE
                    + " 之间（收到 " + size + "）——防全表扫拖垮人审台");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("page", page).addValue("size", size);
        String runFilter = "";
        if (runId != null) {
            runFilter = " AND r.run_id = :runId";
            params.addValue("runId", runId);
        }
        List<ReviewQueueRow> rows = named.query(SELECT_QUEUE + runFilter
                        + " ORDER BY r.created_at DESC, r.id DESC LIMIT :size OFFSET :offset",
                withOffset(params, page, size),
                new BeanPropertyRowMapper<>(ReviewQueueRow.class));
        List<ReviewQueueView> queue = new ArrayList<>();
        for (ReviewQueueRow row : rows) {
            ReviewQueueView view = toQueueView(row);
            if (view != null) {
                queue.add(view);
            }
        }
        return queue;
    }

    
    private ReviewQueueView toQueueView(ReviewQueueRow row) {
        List<String> declared = declaredDimensions(row.getRubricSnapshotJson());
        Map<String, Object> scored = codec.readObjectMap(row.getLlmScoresJson());
        List<String> missing = declared.stream().filter(d -> !(scored.get(d) instanceof Number))
                .toList();
        if (missing.isEmpty()) {
            return null;
        }
        Map<String, Object> reasons = codec.readObjectMap(row.getReasonsJson());
        boolean noEvidence = scored.isEmpty() && reasons.containsKey(NO_EVIDENCE_REASON_KEY);
        String reason = noEvidence
                ? "判分无据：裁判两次回包均为孤儿分，" + missing.size() + " 个维度（"
                        + String.join("、", missing) + "）无有效分，仅规则层残存 "
                        + residual(row) + "——需人工重评"
                : "维度缺失：裁判仅打完 " + (declared.size() - missing.size()) + "/" + declared.size()
                        + " 维，缺「" + String.join("、", missing) + "」——可做维度改判或总分改判";
        return new ReviewQueueView(row.getRecordId(), row.getRunId(), row.getSampleId(),
                row.getRuleScore(), row.getFinalScore(), missing, reason, row.getCreatedAt());
    }

    private String residual(ReviewQueueRow row) {
        return row.getFinalScore() == null ? "未落分" : "合成分 " + row.getFinalScore();
    }

    
    private List<String> declaredDimensions(String snapshotJson) {
        return codec.readMapList(snapshotJson == null ? null : dimensionsOf(snapshotJson)).stream()
                .map(dim -> String.valueOf(dim.get("name")))
                .filter(name -> !name.isBlank() && !"null".equals(name))
                .toList();
    }

    private String dimensionsOf(String snapshotJson) {
        Map<String, Object> snapshot = codec.readObjectMap(snapshotJson);
        Object dimensions = snapshot.get("dimensions");
        return dimensions == null ? "[]" : codec.write(dimensions);
    }

    private MapSqlParameterSource withOffset(MapSqlParameterSource params, int page, int size) {
        return params.addValue("offset", (long) page * size);
    }

    
    private int resolveOrgScore(Map<String, Object> record, String dimension, Long recordId) {
        if (dimension == null) {
            Object finalScore = record.get("final_score");
            if (finalScore == null) {
                throw new ValidationException("评分记录（id=" + recordId
                        + "）尚无合成分——改判需有机器原分可比对，禁在无分记录上记人工分");
            }
            return ((Number) finalScore).intValue();
        }
        Map<String, Object> llm = codec.readObjectMap((String) record.get("llm_scores_json"));
        Object dimScore = llm.get(dimension);
        if (!(dimScore instanceof Number n)) {
            throw new ValidationException("维度「" + dimension + "」在该记录中无 LLM 原分"
                    + "（现有维度：" + (llm.isEmpty() ? "无" : String.join("、", llm.keySet()))
                    + "）——维度级改判只能落在裁判实际打过的维度上");
        }
        return (int) Math.round(n.doubleValue());
    }

    private JudgeReviewView toView(JudgeReviewRow row) {
        return new JudgeReviewView(row.getId(), row.getRunId(), row.getRecordId(),
                row.getReviewer(), row.getDimension(), row.getOrgScore(),
                row.getNewScore(), row.getReason(), row.getCreatedAt());
    }
}
