package com.sixbean.testpike.core.judge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleItem;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricFamilyView;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import com.sixbean.testpike.core.judge.domain.component.JudgeJsonCodec;
import com.sixbean.testpike.core.judge.domain.service.JudgeRubricService;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.JudgeRubricRow;
import com.sixbean.testpike.core.judge.infrastructure.dataobject.RubricFamilyRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class JudgeRubricServiceImpl implements JudgeRubricService {

    
    static final int STATUS_GRAY = 1;

    private static final String SELECT_COLUMNS = """
            SELECT id, name, domain, version, dimensions::text AS dimensions_json,
                   rules::text AS rules_json, pass_score, w_rule, w_llm, status,
                   created_by, created_at
            FROM tp_judge_rubric
            """;

    private final NamedParameterJdbcTemplate named;
    private final JudgeJsonCodec codec;

    public JudgeRubricServiceImpl(NamedParameterJdbcTemplate named, JudgeJsonCodec codec) {
        this.named = named;
        this.codec = codec;
    }

    @Override
    @Transactional
    public RubricView save(RubricSaveInput input) {
        validate(input);
        return insertNewVersion(input.name().trim(), input.domain().trim(),
                input.dimensions(), input.rules(), input.passScore(),
                input.wRule(), input.wLlm(), input.createdBy());
    }

    @Override
    @Transactional
    public RubricView cloneVersion(Long sourceId, Long operatorId) {
        JudgeRubricRow source = queryOne(" WHERE id = :id",
                new MapSqlParameterSource("id", sourceId));
        if (source == null) {
            throw new ValidationException("要克隆的评分标准版本不存在（id=" + sourceId + "）");
        }
        return insertNewVersion(source.getName(), source.getDomain(),
                readDimensions(source.getDimensionsJson()), readRules(source.getRulesJson()),
                source.getPassScore(), source.getWRule(), source.getWLlm(), operatorId);
    }

    @Override
    public RubricView detail(Long id) {
        JudgeRubricRow row = queryOne(" WHERE id = :id", new MapSqlParameterSource("id", id));
        return row == null ? null : toView(row);
    }

    @Override
    public List<RubricView> listVersions(String name, String domain) {
        return named.query(SELECT_COLUMNS + " WHERE name = :name AND domain = :domain"
                        + " ORDER BY version DESC",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain),
                new BeanPropertyRowMapper<>(JudgeRubricRow.class))
                .stream().map(this::toView).toList();
    }

    @Override
    public List<RubricFamilyView> listLatest() {
        return named.query("""
                        SELECT DISTINCT ON (name, domain)
                               id, name, domain, version, pass_score, status, created_at
                        FROM tp_judge_rubric
                        ORDER BY name, domain, version DESC
                        """,
                new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(RubricFamilyRow.class))
                .stream()
                .map(row -> new RubricFamilyView(row.getId(), row.getName(), row.getDomain(),
                        row.getVersion(), row.getPassScore(), row.getStatus(),
                        ViewFormats.formatTs(row.getCreatedAt())))
                .toList();
    }

    private RubricView insertNewVersion(String name, String domain,
                                        List<RubricDimension> dimensions, List<JudgeRuleItem> rules,
                                        int passScore, BigDecimal wRule, BigDecimal wLlm,
                                        Long createdBy) {
        int next = nextVersion(name, domain);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("domain", domain)
                .addValue("version", next)
                .addValue("dimensions", codec.write(dimensions))
                .addValue("rules", codec.write(rules == null ? List.of() : rules))
                .addValue("passScore", passScore)
                .addValue("wRule", wRule)
                .addValue("wLlm", wLlm)
                .addValue("status", STATUS_GRAY)
                .addValue("createdBy", createdBy);
        List<Long> ids = named.queryForList("INSERT INTO tp_judge_rubric "
                        + "(name, domain, version, dimensions, rules, pass_score, w_rule, w_llm, "
                        + "status, created_by) "
                        + "VALUES (:name, :domain, :version, CAST(:dimensions AS jsonb), "
                        + "CAST(:rules AS jsonb), :passScore, :wRule, :wLlm, :status, :createdBy) "
                        + "RETURNING id",
                params, Long.class);
        return detail(ids.get(0));
    }

    private int nextVersion(String name, String domain) {
        Integer max = named.queryForObject("SELECT coalesce(max(version), 0) "
                        + "FROM tp_judge_rubric WHERE name = :name AND domain = :domain",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain),
                Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    private JudgeRubricRow queryOne(String whereClause, MapSqlParameterSource params) {
        List<JudgeRubricRow> rows = named.query(SELECT_COLUMNS + whereClause, params,
                new BeanPropertyRowMapper<>(JudgeRubricRow.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validate(RubricSaveInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ValidationException("评分标准名不能为空");
        }
        if (input.domain() == null || input.domain().isBlank()) {
            throw new ValidationException("业务域标签不能为空（如 用例生成/归因准确性）");
        }
        if (input.dimensions() == null || input.dimensions().isEmpty()) {
            throw new ValidationException("评分维度至少一个（名称/定义/锚点样例/权重）");
        }
        for (RubricDimension d : input.dimensions()) {
            if (d.name() == null || d.name().isBlank()) {
                throw new ValidationException("维度名称不能为空");
            }
            if (d.weight() != null && (d.weight() < 0 || d.weight() > 1)) {
                throw new ValidationException("维度「" + d.name() + "」权重须在 0~1 之间（收到 "
                        + d.weight() + "）");
            }
        }
        if (input.passScore() < 0 || input.passScore() > 100) {
            throw new ValidationException("门禁阈值须在 0~100 之间（收到 " + input.passScore() + "）");
        }
        requireWeight("规则权重", input.wRule());
        requireWeight("LLM 权重", input.wLlm());
    }

    private void requireWeight(String label, BigDecimal w) {
        if (w == null || w.signum() < 0 || w.compareTo(BigDecimal.ONE) > 0) {
            throw new ValidationException(label + "须在 0~1 之间（收到 " + w + "）");
        }
    }

    private RubricView toView(JudgeRubricRow row) {
        return new RubricView(row.getId(), row.getName(), row.getDomain(), row.getVersion(),
                readDimensions(row.getDimensionsJson()), readRules(row.getRulesJson()),
                row.getPassScore(), row.getWRule(), row.getWLlm(), row.getStatus(),
                row.getCreatedBy(), ViewFormats.formatTs(row.getCreatedAt()));
    }

    private List<RubricDimension> readDimensions(String json) {
        List<RubricDimension> out = new ArrayList<>();
        for (Map<String, Object> m : codec.readMapList(json)) {
            Object anchors = m.get("anchorExamples");
            List<String> anchorList = anchors instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            out.add(new RubricDimension(String.valueOf(m.get("name")),
                    m.get("definition") == null ? "" : String.valueOf(m.get("definition")),
                    m.get("weight") == null ? null : Double.valueOf(String.valueOf(m.get("weight"))),
                    anchorList));
        }
        return List.copyOf(out);
    }

    private List<JudgeRuleItem> readRules(String json) {
        return codec.readMapList(json).stream()
                .map(m -> new JudgeRuleItem(
                        String.valueOf(m.getOrDefault("checker", "")),
                        asParams(m.get("params"))))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asParams(Object raw) {
        return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of();
    }
}
