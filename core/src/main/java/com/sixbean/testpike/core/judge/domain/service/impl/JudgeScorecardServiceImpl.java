package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.judge.api.dto.GenerationSessionCardView;
import com.sixbean.testpike.core.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffRowView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardView;
import com.sixbean.testpike.core.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.ScorecardTrendPointView;
import com.sixbean.testpike.core.judge.domain.component.JudgeDiffStats;
import com.sixbean.testpike.core.judge.domain.component.JudgeJsonCodec;
import com.sixbean.testpike.core.judge.domain.component.JudgeReportConfig;
import com.sixbean.testpike.core.judge.domain.service.JudgeRunService;
import com.sixbean.testpike.core.judge.domain.service.JudgeScorecardService;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.RecordCardRow;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.ReportAggregateRow;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.ScorecardAggregateRow;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.TrendRunRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;


@Service
public class JudgeScorecardServiceImpl implements JudgeScorecardService {

    
    private static final int STATUS_DONE = 2;
    private static final int TREND_LIMIT_MAX = 200;
    private static final int DEFAULT_PASS_SCORE = 60;

    private static final String SAMPLE_TOTAL_SQL = "SELECT count(*) FROM tp_judge_sample "
            + "WHERE set_name = :setName AND version = :version";

    /**
 * Diff-table sort whitelist : sample_id / score_a / score_b
 * / delta. score columns are Java-side derived values of the merged window
 * (authoritative score per side, delta = B−A), so they never enter SQL —
 * the gate exists fail-closed against injection-shaped keys and the actual
 * ordering happens in memory over the whole union before skip/limit.
 */
    private static final Map<String, String> DIFF_SORT_COLUMNS = Map.of(
            "sample_id", "sample_id",
            "score_a", "score_a",
            "score_b", "score_b",
            "delta", "delta");

    
    private static final String REPORT_AGGREGATE_SQL = """
            SELECT count(*)::int AS scored,
                   avg(coalesce(manual_final_score, final_score)) AS avg_final,
                   coalesce(sum(tokens), 0)::bigint AS tokens_total,
                   avg(latency_ms) AS latency_avg,
                   coalesce(sum(CASE WHEN coalesce(manual_final_score, final_score) >= :passScore
                                     THEN 1 ELSE 0 END), 0)::int AS pass_count
            FROM tp_judge_record WHERE run_id = :runId
            """;

    
    private static final String DIFF_SIDES_SQL = """
            SELECT run_id, sample_id,
                   coalesce(manual_final_score, final_score) AS auth_score
            FROM tp_judge_record WHERE run_id IN (:runA, :runB)
            """;

    private static final String SAMPLE_PREVIEW_SQL =
            "SELECT id, input FROM tp_judge_sample WHERE id IN (:ids)";

    private static final String CARD_PAGE_SQL = """
            SELECT r.id AS record_id, r.sample_id, s.input, s.expected, s.source,
                   r.rule_score, r.rule_hits::text AS rule_hits_json,
                   r.llm_scores::text AS llm_scores_json, r.reasons::text AS reasons_json,
                   r.final_score, r.manual_final_score, r.tokens, r.latency_ms
            FROM tp_judge_record r
            JOIN tp_judge_sample s ON s.id = r.sample_id
            WHERE r.run_id = :runId
            ORDER BY coalesce(r.manual_final_score, r.final_score) ASC, r.id ASC
            LIMIT :limit OFFSET :offset
            """;

    private static final String TREND_RUN_SQL = """
            SELECT ru.id, ru.rubric_version, ru.evalset_name, ru.evalset_version,
                   ru.rubric_snapshot::text AS rubric_snapshot_json, ru.finished_at,
                   ru.target_type, ru.target_ref, ru.llm_config_id,
                   cf.provider AS llm_provider, cf.model_name AS llm_model_name
            FROM tp_judge_run ru
            LEFT JOIN tp_llm_config cf ON cf.id = ru.llm_config_id
            WHERE ru.rubric_name = :name AND ru.rubric_domain = :domain AND ru.status = """
            + STATUS_DONE
            + " ORDER BY finished_at DESC, ru.id DESC LIMIT :limit";

    private final NamedParameterJdbcTemplate named;
    private final JudgeJsonCodec codec;
    private final JudgeRunService runService;
    private final JudgeReportConfig reportConfig;

    public JudgeScorecardServiceImpl(NamedParameterJdbcTemplate named, JudgeJsonCodec codec,
                                     JudgeRunService runService, JudgeReportConfig reportConfig) {
        this.named = named;
        this.codec = codec;
        this.runService = runService;
        this.reportConfig = reportConfig;
    }

    @Override
    public RunScorecardView scorecard(Long runId) {
        RunView run = requireRun(runId, "评分卡");
        int passScore = snapshotPassScore(run.rubricSnapshot());
        Integer sampleTotal = named.queryForObject(SAMPLE_TOTAL_SQL,
                new MapSqlParameterSource()
                        .addValue("setName", run.evalsetName())
                        .addValue("version", run.evalsetVersion()),
                Integer.class);
        ScorecardAggregateRow agg = aggregate(runId, passScore);

        return new RunScorecardView(run.id(), run.rubricName(), run.rubricDomain(),
                run.rubricVersion(), run.evalsetName(), run.evalsetVersion(), run.status(),
                passScore, sampleTotal, agg.getScored(), agg.avgFinalScore(),
                agg.getPassCount(), agg.passRate(), agg.getTokensTotal(),
                agg.latencyAvgMs(), dimensionAverages(runId));
    }

    @Override
    public List<ScorecardTrendPointView> trend(String rubricName, String rubricDomain,
                                               int limit) {
        List<TrendRunRow> rows = recentFinishedRuns(rubricName, rubricDomain, limit);
        List<ScorecardTrendPointView> points = new ArrayList<>(rows.size());
        for (TrendRunRow row : rows) {
            int passScore = snapshotPassScore(codec.readObjectMap(row.getRubricSnapshotJson()));
            ScorecardAggregateRow agg = aggregate(row.getId(), passScore);
            points.add(new ScorecardTrendPointView(row.getId(), row.getRubricVersion(),
                    row.getEvalsetName(), row.getEvalsetVersion(),
                    ViewFormats.formatTs(row.getFinishedAt()), agg.getScored(),
                    agg.avgFinalScore(), agg.passRate(), agg.getTokensTotal()));
        }
        return List.copyOf(points);
    }

    @Override
    public List<GenerationSessionCardView> sessionCards(String rubricName, String rubricDomain,
                                                        int limit) {
        List<TrendRunRow> rows = recentFinishedRuns(rubricName, rubricDomain, limit);
        List<GenerationSessionCardView> cards = new ArrayList<>(rows.size());
        for (TrendRunRow row : rows) {
            int passScore = snapshotPassScore(codec.readObjectMap(row.getRubricSnapshotJson()));
            ScorecardAggregateRow agg = aggregate(row.getId(), passScore);
            cards.add(new GenerationSessionCardView(row.getId(), row.getTargetType(),
                    row.getTargetRef(), row.judgeModel(),
                    ViewFormats.formatTs(row.getFinishedAt()), agg.getScored(),
                    agg.avgFinalScore(), agg.getPassCount(), agg.passRate(),
                    agg.getTokensTotal(), agg.tokensPerSample(), agg.latencyAvgMs()));
        }
        return List.copyOf(cards);
    }

    @Override
    public RunSampleCardPageView sampleCards(Long runId, int page, int size) {
        RunView run = requireRun(runId, "样本卡片流");
        requirePaging(page, size);
        ReportAggregateRow agg = reportAggregate(runId, snapshotPassScore(run.rubricSnapshot()));
        long total = agg.getScored() == null ? 0 : agg.getScored();
        List<RecordCardRow> rows = named.query(CARD_PAGE_SQL,
                new MapSqlParameterSource()
                        .addValue("runId", runId)
                        .addValue("limit", size)
                        .addValue("offset", (long) (page - 1) * size),
                (rs, rowNum) -> new RecordCardRow(
                        rs.getLong("record_id"), rs.getLong("sample_id"),
                        rs.getString("input"), rs.getString("expected"),
                        rs.getString("source"), (Integer) nullableInt(rs, "rule_score"),
                        rs.getString("rule_hits_json"), rs.getString("llm_scores_json"),
                        rs.getString("reasons_json"), (Integer) nullableInt(rs, "final_score"),
                        (Integer) nullableInt(rs, "manual_final_score"),
                        (Integer) nullableInt(rs, "tokens"), nullableLong(rs, "latency_ms")));
        List<RunSampleCardView> cards = new ArrayList<>(rows.size());
        for (RecordCardRow row : rows) {
            Integer auth = row.manualFinalScore() != null ? row.manualFinalScore()
                    : row.finalScore();
            cards.add(new RunSampleCardView(row.recordId(), row.sampleId(),
                    ViewFormats.preview(row.input()), ViewFormats.preview(row.expected()),
                    row.source(), row.ruleScore(), codec.readStringList(row.ruleHitsJson()),
                    codec.readObjectMap(row.llmScoresJson()),
                    codec.readObjectMap(row.reasonsJson()),
                    row.finalScore(), row.manualFinalScore(), auth,
                    row.manualFinalScore() != null, row.tokens(), row.latencyMs()));
        }
        return new RunSampleCardPageView(runId, page, size, total, List.copyOf(cards));
    }

    @Override
    public RunDiffView diffRuns(Long runAId, Long runBId, int page, int size) {
        return diffRuns(runAId, runBId, page, size, null, null);
    }

    /**
 * Order the merged union window in memory . diffColumn null
 * = historical sample-id ascending (TreeSet natural order, zero drift).
 * Score keys are derived per-sample lookups; a missing side sorts last in
 * BOTH directions (never read as the extreme value), and tied groups are
 * pinned by ascending sample id so paging stays deterministic.
 */
    private Stream<Long> orderDiffWindow(TreeSet<Long> union, String diffColumn,
                                         String sortDir,
                                         Map<Long, Integer> scoreA, Map<Long, Integer> scoreB) {
        if (diffColumn == null || "sample_id".equals(diffColumn)) {
            boolean idDesc = "desc".equalsIgnoreCase(sortDir);
            return idDesc ? union.descendingSet().stream() : union.stream();
        }
        Function<Long, Integer> value;
        if ("score_a".equals(diffColumn)) {
            value = scoreA::get;
        } else if ("score_b".equals(diffColumn)) {
            value = scoreB::get;
        } else {
            value = sid -> {
                Integer sa = scoreA.get(sid);
                Integer sb = scoreB.get(sid);
                return sa != null && sb != null ? sb - sa : null;
            };
        }
        boolean asc = !"desc".equalsIgnoreCase(sortDir);
        List<Long> ordered = new ArrayList<>(union);
        ordered.sort((x, y) -> {
            Integer vx = value.apply(x);
            Integer vy = value.apply(y);
            if (vx == null || vy == null) {
                if (vx == null && vy == null) {
                    return Long.compare(x, y);
                }
                return vx == null ? 1 : -1; // missing side always last, both ways
            }
            int byValue = asc ? Integer.compare(vx, vy) : Integer.compare(vy, vx);
            return byValue != 0 ? byValue : Long.compare(x, y); // ties pinned asc id
        });
        return ordered.stream();
    }

    @Override
    public RunDiffView diffRuns(Long runAId, Long runBId, int page, int size,
                                String sortBy, String sortDir) {
        RunView a = requireRun(runAId, "对比运行 A");
        RunView b = requireRun(runBId, "对比运行 B");
        if (runAId.equals(runBId)) {
            throw new ValidationException("两个对比运行不能相同（id=" + runAId
                    + "）——请选不同时间/不同目标的两次评测");
        }
        if (!a.evalsetName().equals(b.evalsetName())
                || !a.evalsetVersion().equals(b.evalsetVersion())) {
            throw new ValidationException("两次评测绑定的评测集不同源，分数不可比：运行 "
                    + a.id() + " 绑「" + a.evalsetName() + " v" + a.evalsetVersion()
                    + "」，运行 " + b.id() + " 绑「" + b.evalsetName() + " v"
                    + b.evalsetVersion() + "」——同题才有涨跌语义（归因红线）");
        }
        requirePaging(page, size);
        // Fail-closed sort gate: an off-list or injection-shaped key is refused
        // before any DB read (the short-circuit itself is what the test pins).
        String diffColumn = SortColumnPolicy.require(sortBy, DIFF_SORT_COLUMNS, "评测对比");

        Map<Long, Integer> scoreA = new TreeMap<>();
        Map<Long, Integer> scoreB = new TreeMap<>();
        named.query(DIFF_SIDES_SQL,
                new MapSqlParameterSource().addValue("runA", runAId).addValue("runB", runBId),
                rs -> {
                    long rid = rs.getLong("run_id");
                    long sid = rs.getLong("sample_id");
                    int sc = rs.getInt("auth_score");
                    if (rs.wasNull()) {
                        return;
                    }
                    (rid == runAId ? scoreA : scoreB).put(sid, sc);
                });

        TreeSet<Long> union = new TreeSet<>();
        union.addAll(scoreA.keySet());
        union.addAll(scoreB.keySet());
        // Whole-window ordering happens here in memory (score columns are
        // derived per-sample values, not physical SQL columns): order the full
        // union first, then skip/limit, so page 2 continues the order instead
        // of reshuffling one pageful.
        List<Long> pageIds = orderDiffWindow(union, diffColumn, sortDir, scoreA, scoreB)
                .skip((long) (page - 1) * size).limit(size)
                .toList();
        Map<Long, String> inputById = pageIds.isEmpty() ? Map.of()
                : named.query("SELECT id, input FROM tp_judge_sample WHERE id IN (:ids)",
                        new MapSqlParameterSource("ids", pageIds),
                        (rs, rowNum) -> new String[]{String.valueOf(rs.getLong("id")),
                                rs.getString("input")})
                .stream().collect(TreeMap::new,
                        (m, kv) -> m.put(Long.valueOf(kv[0]), kv[1]), TreeMap::putAll);

        int up = 0;
        int down = 0;
        int flat = 0;
        int onlyA = 0;
        int onlyB = 0;
        List<Integer> pairedA = new ArrayList<>();
        List<Integer> pairedB = new ArrayList<>();
        for (Long sid : union) {
            Integer sa = scoreA.get(sid);
            Integer sb = scoreB.get(sid);
            if (sa != null && sb != null) {
                pairedA.add(sa);
                pairedB.add(sb);
                if (sb > sa) {
                    up++;
                } else if (sb < sa) {
                    down++;
                } else {
                    flat++;
                }
            } else if (sa != null) {
                onlyA++;
            } else {
                onlyB++;
            }
        }
        List<RunDiffRowView> rows = new ArrayList<>(pageIds.size());
        for (Long sid : pageIds) {
            Integer sa = scoreA.get(sid);
            Integer sb = scoreB.get(sid);
            rows.add(new RunDiffRowView(sid, ViewFormats.preview(inputById.get(sid)),
                    sa, sb, sa != null && sb != null ? sb - sa : null,
                    verdict(sa, sb)));
        }

        ReportAggregateRow aggA = reportAggregate(runAId, snapshotPassScore(a.rubricSnapshot()));
        ReportAggregateRow aggB = reportAggregate(runBId, snapshotPassScore(b.rubricSnapshot()));
        Double avgA = aggA.avgExact();
        Double avgB = aggB.avgExact();
        double welch = JudgeDiffStats.welchT(new ArrayList<>(scoreA.values()),
                new ArrayList<>(scoreB.values()));
        double p = JudgeDiffStats.twoSidedPNormal(welch);
        double alpha = reportConfig.diffAlpha();
        boolean significant = !Double.isNaN(p) && p < alpha;
        String note = significanceNote(scoreA.size(), scoreB.size(), welch, p, alpha,
                significant);

        return new RunDiffView(runAId, runBId, a.targetRef(), b.targetRef(),
                a.evalsetName(), a.evalsetVersion(), a.rubricName(),
                List.copyOf(rows), page, size, union.size(),
                up, down, flat, onlyA, onlyB, pairedA.size(),
                avgA, avgB, avgA != null && avgB != null
                        ? Math.round((avgB - avgA) * 100.0) / 100.0 : null,
                welch, p, significant, note);
    }

    @Override
    public RunCostCardView costCard(Long runId) {
        RunView run = requireRun(runId, "成本卡");
        ReportAggregateRow agg = reportAggregate(runId, snapshotPassScore(run.rubricSnapshot()));
        Integer sampleTotal = named.queryForObject(SAMPLE_TOTAL_SQL,
                new MapSqlParameterSource()
                        .addValue("setName", run.evalsetName())
                        .addValue("version", run.evalsetVersion()),
                Integer.class);
        int scored = agg.getScored() == null ? 0 : agg.getScored();
        long tokens = agg.getTokensTotal() == null ? 0 : agg.getTokensTotal();
        Double price = reportConfig.pricePer1kTokens();

        Double samplingRate = sampleTotal == null || sampleTotal == 0 ? null
                : Math.round(scored * 100.0 / sampleTotal) / 100.0;
        Long tokensPerSample = scored == 0 ? null : tokens / scored;
        Double est = null;
        Double perSample = null;
        String note;
        if (price == null) {
            note = "未设牌价：成本只报 token 实耗 " + tokens + "（在「系统配置 → AI评测」设 "
                    + "judge.cost.price-per-1k-tokens＝元/千 token，热更即生效，禁编造金额）";
        } else {
            est = Math.round(tokens / 1000.0 * price * 100.0) / 100.0;
            perSample = scored == 0 ? null : est / scored;
            perSample = perSample == null ? null : Math.round(perSample * 100.0) / 100.0;
            note = "牌价 " + price + " 元/千 token × 实耗 " + tokens + " token ≈ " + est
                    + " 元（judge.cost.price-per-1k-tokens 热更即生效）";
        }
        return new RunCostCardView(runId, tokens, scored, sampleTotal, samplingRate,
                tokensPerSample, price != null, price, est, perSample, note);
    }

    
    private static String verdict(Integer sa, Integer sb) {
        if (sa != null && sb != null) {
            return sb > sa ? "涨" : sb < sa ? "跌" : "持平";
        }
        return sa != null ? "仅A" : "仅B";
    }

    
    private static String significanceNote(int nA, int nB, double t, double p,
                                           double alpha, boolean significant) {
        if (Double.isNaN(t)) {
            return "均值差显著性判不了（A 侧 " + nA + " 条 / B 侧 " + nB
                    + " 条，样本数<2 或两侧全同分=无波动可检验）——不视为显著，禁据此下结论";
        }
        String pText = p < 0.0001 ? "<0.0001" : String.format("%.4f", p);
        return "A " + nA + " 条 vs B " + nB + " 条，Welch t="
                + String.format("%.4f", t) + "、双侧 p≈" + pText + "（正态近似口径）"
                + (significant ? " ＜ α=" + alpha + "：均值差显著"
                : " ≥ α=" + alpha + "：差异不显著（样本少或波动大时属常态，禁把不显著读成没差别）");
    }

    private RunView requireRun(Long runId, String consumer) {
        if (runId == null || runId <= 0) {
            throw new ValidationException("必须指定评测运行（runId 须为正整数，收到 " + runId + "）");
        }
        RunView run = runService.detail(runId);
        if (run == null) {
            throw new ValidationException("评测运行不存在（id=" + runId + "）——"
                    + consumer + "无从聚合");
        }
        return run;
    }

    private void requirePaging(int page, int size) {
        if (page < 1) {
            throw new ValidationException("页码须从 1 起（收到 " + page + "）");
        }
        if (size < 1 || size > TREND_LIMIT_MAX) {
            throw new ValidationException("页尺寸须在 1~" + TREND_LIMIT_MAX
                    + " 之间（收到 " + size + "）——禁无界扫");
        }
    }

    
    private List<TrendRunRow> recentFinishedRuns(String rubricName, String rubricDomain,
                                                 int limit) {
        requireText(rubricName, "评分标准族名");
        requireText(rubricDomain, "评分标准业务域");
        if (limit < 1 || limit > TREND_LIMIT_MAX) {
            throw new ValidationException("取点数须在 1~" + TREND_LIMIT_MAX
                    + " 之间（收到 " + limit + "）——禁无界扫");
        }
        List<TrendRunRow> rows = named.query(TREND_RUN_SQL,
                new MapSqlParameterSource()
                        .addValue("name", rubricName.trim())
                        .addValue("domain", rubricDomain.trim())
                        .addValue("limit", limit),
                new BeanPropertyRowMapper<>(TrendRunRow.class));
        Collections.reverse(rows);
        return rows;
    }

    
    private ScorecardAggregateRow aggregate(Long runId, int passScore) {
        return named.queryForObject(REPORT_AGGREGATE_SQL,
                new MapSqlParameterSource().addValue("runId", runId)
                        .addValue("passScore", passScore),
                new BeanPropertyRowMapper<>(ScorecardAggregateRow.class));
    }

    
    private ReportAggregateRow reportAggregate(Long runId, int passScore) {
        return named.queryForObject(REPORT_AGGREGATE_SQL,
                new MapSqlParameterSource().addValue("runId", runId)
                        .addValue("passScore", passScore),
                new BeanPropertyRowMapper<>(ReportAggregateRow.class));
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    
    private Map<String, Double> dimensionAverages(Long runId) {
        List<String> rows = named.queryForList("SELECT llm_scores::text FROM tp_judge_record "
                        + "WHERE run_id = :runId AND llm_scores IS NOT NULL",
                new MapSqlParameterSource("runId", runId), String.class);
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (String json : rows) {
            for (Map.Entry<String, Object> e : codec.readObjectMap(json).entrySet()) {
                if (e.getValue() instanceof Number n) {
                    double[] acc = sums.computeIfAbsent(e.getKey(), k -> new double[2]);
                    acc[0] += n.doubleValue();
                    acc[1] += 1;
                }
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : sums.entrySet()) {
            out.put(e.getKey(), Math.round(e.getValue()[0] * 100.0 / e.getValue()[1]) / 100.0);
        }
        return Map.copyOf(out);
    }

    private int snapshotPassScore(Map<String, Object> snapshot) {
        Object raw = snapshot == null ? null : snapshot.get("passScore");
        return raw instanceof Number n ? n.intValue() : DEFAULT_PASS_SCORE;
    }

    private void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + "不能为空（趋势曲线按标准族×业务域成组）");
        }
    }
}
