package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.core.judge.api.dto.CalibrationGroupView;
import com.sixbean.testpike.core.judge.domain.component.CalibrationParams;
import com.sixbean.testpike.core.judge.domain.component.JudgeCalibrationConfig;
import com.sixbean.testpike.core.judge.domain.component.JudgeCalibrationStats;
import com.sixbean.testpike.core.judge.domain.service.JudgeCalibrationProfileService;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.CalibrationPairRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


@Service
public class JudgeCalibrationProfileServiceImpl implements JudgeCalibrationProfileService {

    
    private static final String SELECT_PAIRS = """
            SELECT ru.rubric_name AS rubric_name, ru.rubric_domain AS rubric_domain,
                   ru.llm_config_id AS llm_config_id,
                   rv.org_score AS org_score, rv.new_score AS new_score
            FROM tp_judge_review rv
            JOIN tp_judge_run ru ON ru.id = rv.run_id
            WHERE rv.dimension IS NULL AND rv.org_score IS NOT NULL AND rv.new_score IS NOT NULL
            """;

    private final NamedParameterJdbcTemplate named;
    private final JudgeCalibrationConfig calibrationConfig;

    public JudgeCalibrationProfileServiceImpl(NamedParameterJdbcTemplate named,
                                              JudgeCalibrationConfig calibrationConfig) {
        this.named = named;
        this.calibrationConfig = calibrationConfig;
    }

    @Override
    public List<CalibrationGroupView> profile(String rubricName, String rubricDomain, Long llmConfigId) {
        CalibrationParams params = calibrationConfig.load();
        List<CalibrationPairRow> rows = named.query(SELECT_PAIRS
                        + filterSql(rubricName, rubricDomain) + " ORDER BY rv.id",
                filterParams(rubricName, rubricDomain),
                new BeanPropertyRowMapper<>(CalibrationPairRow.class));

        Map<String, List<CalibrationPairRow>> groups = new TreeMap<>();
        for (CalibrationPairRow row : rows) {
            if (llmConfigId != null && !Objects.equals(llmConfigId, row.getLlmConfigId())) {
                continue;
            }
            groups.computeIfAbsent(groupKey(row), k -> new ArrayList<>()).add(row);
        }

        List<CalibrationGroupView> result = new ArrayList<>();
        groups.values().forEach(group -> result.add(toView(group, params)));
        result.sort(Comparator.comparing(CalibrationGroupView::qualified).reversed()
                .thenComparing(CalibrationGroupView::rubricName)
                .thenComparing(CalibrationGroupView::rubricDomain)
                .thenComparing(v -> v.llmConfigId() == null ? Long.MIN_VALUE : v.llmConfigId()));
        return result;
    }

    
    private String filterSql(String rubricName, String rubricDomain) {
        StringBuilder sql = new StringBuilder();
        if (rubricName != null && !rubricName.isBlank()) {
            sql.append(" AND ru.rubric_name = :rubricName");
        }
        if (rubricDomain != null && !rubricDomain.isBlank()) {
            sql.append(" AND ru.rubric_domain = :rubricDomain");
        }
        return sql.toString();
    }

    private MapSqlParameterSource filterParams(String rubricName, String rubricDomain) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        if (rubricName != null && !rubricName.isBlank()) {
            source.addValue("rubricName", rubricName.trim());
        }
        if (rubricDomain != null && !rubricDomain.isBlank()) {
            source.addValue("rubricDomain", rubricDomain.trim());
        }
        return source;
    }

    private String groupKey(CalibrationPairRow row) {
        return row.getRubricName() + " " + row.getRubricDomain()
                + " " + (row.getLlmConfigId() == null ? "-" : row.getLlmConfigId());
    }

    private CalibrationGroupView toView(List<CalibrationPairRow> group, CalibrationParams params) {
        List<Integer> machine = group.stream().map(CalibrationPairRow::getOrgScore).toList();
        List<Integer> human = group.stream().map(CalibrationPairRow::getNewScore).toList();
        double kappa = JudgeCalibrationStats.cohenKappaPass(machine, human, params.passThreshold());
        double rho = JudgeCalibrationStats.spearman(machine, human);
        boolean qualified = group.size() >= params.bootstrapSamples()
                && !Double.isNaN(kappa) && kappa >= params.kappaThreshold();
        return new CalibrationGroupView(group.get(0).getRubricName(),
                group.get(0).getRubricDomain(), group.get(0).getLlmConfigId(),
                group.size(), kappa, rho, qualified, note(group.size(), kappa, params, qualified));
    }

    private String note(int pairCount, double kappa, CalibrationParams params, boolean qualified) {
        if (qualified) {
            return "一致率达标（κ=" + String.format("%.3f", kappa) + " ≥ " + params.kappaThreshold()
                    + "，配对 " + pairCount + " ≥ 基线 " + params.bootstrapSamples() + "）——裁判分可入准出谓词";
        }
        if (Double.isNaN(kappa)) {
            return "一致率未定义=判不了（改判配对 " + pairCount + " 不足以定论或双侧同档退化）——"
                    + "维持全量双裁判补样，禁按达标放行也禁按跌破升档误读";
        }
        if (pairCount < params.bootstrapSamples()) {
            return "基线未建（配对 " + pairCount + " / 需 " + params.bootstrapSamples()
                    + "；当前 κ=" + String.format("%.3f", kappa) + " 仅作观察）——全量双裁判建基线中";
        }
        return "一致率跌破（κ=" + String.format("%.3f", kappa) + " < 达标线 "
                + params.kappaThreshold() + "）——机器分与人工真值确实不一致，单裁判分降级为参考分";
    }
}
