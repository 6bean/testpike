package com.sixbean.testpike.core.prompt.domain.service.impl;

import com.sixbean.testpike.core.behavior.domain.component.GoldenDriftGate;
import com.sixbean.testpike.core.prompt.domain.component.PromptTemplateStatus;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateFamilyItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import com.sixbean.testpike.core.prompt.infrastructure.dataobject.PromptTemplateFamilyRow;
import com.sixbean.testpike.core.prompt.infrastructure.dataobject.PromptTemplateRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class PromptTemplateServiceImpl implements PromptTemplateService {

    
    static final int STATUS_GRAY = PromptTemplateStatus.GRAY;
    
    static final int STATUS_FULL = PromptTemplateStatus.FULL;
    
    static final int STATUS_HISTORY = PromptTemplateStatus.HISTORY;

    private static final String SELECT_COLUMNS = """
            SELECT id, name, domain, version, content, status, created_by, rollback_to, created_at
            FROM tp_prompt_template
            """;

    private final NamedParameterJdbcTemplate named;
    private final GoldenDriftGate goldenDriftGate;

    public PromptTemplateServiceImpl(NamedParameterJdbcTemplate named, GoldenDriftGate goldenDriftGate) {
        this.named = named;
        this.goldenDriftGate = goldenDriftGate;
    }

    @Override
    @Transactional
    public PromptTemplateItem save(PromptTemplateSaveInput input) {
        String name = requireText(input.name(), "模板名");
        String domain = requireText(input.domain(), "业务域");
        String content = requireText(input.content(), "模板内容");
        return insertNewVersion(name, domain, content, input.createdBy(), null);
    }

    @Override
    @Transactional
    public PromptTemplateItem rollback(Long sourceId, Long operatorId) {
        PromptTemplateRow source = queryOne(" WHERE id = :id",
                new MapSqlParameterSource("id", sourceId));
        if (source == null) {
            throw new ValidationException("回滚的模板版本不存在（id=" + sourceId + "）");
        }
        return insertNewVersion(source.getName(), source.getDomain(), source.getContent(),
                operatorId, sourceId);
    }

    @Override
    public PromptTemplateItem detail(Long id) {
        PromptTemplateRow row = queryOne(" WHERE id = :id", new MapSqlParameterSource("id", id));
        return row == null ? null : row.toInfo();
    }

    @Override
    public List<PromptTemplateItem> listVersions(String name, String domain) {
        return named.query(SELECT_COLUMNS + " WHERE name = :name AND domain = :domain"
                        + " ORDER BY version DESC",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain),
                new BeanPropertyRowMapper<>(PromptTemplateRow.class))
                .stream().map(PromptTemplateRow::toInfo).toList();
    }

    @Override
    public List<PromptTemplateFamilyItem> listFamilies(String domain) {
        // One row per family: newest version of each (domain, name) pair plus the family size.
        boolean filterDomain = domain != null && !domain.isBlank();
        String sql = """
                SELECT f.domain, f.name, t.id AS latestId, t.version AS latestVersion,
                       t.status AS latestStatus, f.version_count AS versionCount,
                       t.created_at AS updatedAt
                FROM tp_prompt_template t
                JOIN (SELECT domain, name, max(version) AS mv, count(*) AS version_count
                      FROM tp_prompt_template
                      GROUP BY domain, name) f
                  ON f.domain = t.domain AND f.name = t.name AND f.mv = t.version
                """
                + (filterDomain ? " WHERE t.domain = :domain" : "")
                + " ORDER BY t.domain, t.name";
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filterDomain) {
            params.addValue("domain", domain.trim());
        }
        return named.query(sql, params, new BeanPropertyRowMapper<>(PromptTemplateFamilyRow.class))
                .stream().map(PromptTemplateFamilyRow::toInfo).toList();
    }

    @Override
    public PromptTemplateItem latest(String name, String domain) {
        PromptTemplateRow row = queryOne(" WHERE name = :name AND domain = :domain"
                        + " ORDER BY version DESC LIMIT 1",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain));
        return row == null ? null : row.toInfo();
    }

    @Override
    public PromptTemplateItem latestByStatus(String name, String domain, int status) {
        if (status != STATUS_GRAY && status != STATUS_FULL && status != STATUS_HISTORY) {
            throw new ValidationException("模板状态非法（1=灰度 2=全量 3=历史，收到 " + status + "）");
        }
        PromptTemplateRow row = queryOne(" WHERE name = :name AND domain = :domain"
                        + " AND status = :status ORDER BY version DESC LIMIT 1",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain)
                        .addValue("status", status));
        return row == null ? null : row.toInfo();
    }

    @Override
    public PromptTemplateItem latestGrayBefore(String name, String domain, int upperExclusive) {
        PromptTemplateRow row = queryOne(" WHERE name = :name AND domain = :domain"
                        + " AND status = :status AND version < :upper ORDER BY version DESC LIMIT 1",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain)
                        .addValue("status", STATUS_GRAY).addValue("upper", upperExclusive));
        return row == null ? null : row.toInfo();
    }

    @Override
    @Transactional
    public PromptTemplateItem promote(Long id) {
        PromptTemplateRow target = queryOne(" WHERE id = :id", new MapSqlParameterSource("id", id));
        if (target == null) {
            throw new ValidationException("晋升的模板版本不存在（id=" + id + "）");
        }
        if (target.getStatus() != STATUS_GRAY) {
            throw new ValidationException("仅灰度版本可晋升全量（当前状态=" + target.getStatus() + "）");
        }
        // Pre-release admission : a gray version reaches full
        // release only with a passing golden replay verdict on record. Fail-closed:
        // no verdict blocks like a failed one; manual rollback is never touched.
        goldenDriftGate.requirePromotionAdmission(target.getId(), target.getDomain(), target.getName());
        named.update("UPDATE tp_prompt_template SET status = :history, updated_at = now()"
                        + " WHERE name = :name AND domain = :domain AND status = :full",
                new MapSqlParameterSource().addValue("history", STATUS_HISTORY)
                        .addValue("name", target.getName()).addValue("domain", target.getDomain())
                        .addValue("full", STATUS_FULL));
        named.update("UPDATE tp_prompt_template SET status = :full, updated_at = now()"
                        + " WHERE id = :id",
                new MapSqlParameterSource().addValue("full", STATUS_FULL).addValue("id", id));
        return detail(id);
    }

    
    private int nextVersion(String name, String domain) {
        Integer max = named.queryForObject(
                "SELECT coalesce(max(version), 0) FROM tp_prompt_template"
                        + " WHERE name = :name AND domain = :domain",
                new MapSqlParameterSource().addValue("name", name).addValue("domain", domain),
                Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    private PromptTemplateItem insertNewVersion(String name, String domain, String content,
                                                Long createdBy, Long rollbackTo) {
        int version = nextVersion(name, domain);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name).addValue("domain", domain).addValue("version", version)
                .addValue("content", content).addValue("status", STATUS_GRAY)
                .addValue("createdBy", createdBy).addValue("rollbackTo", rollbackTo);
        Long id = named.queryForObject(
                "INSERT INTO tp_prompt_template (name, domain, version, content, status,"
                        + " created_by, rollback_to)"
                        + " VALUES (:name, :domain, :version, :content, :status, :createdBy, :rollbackTo)"
                        + " RETURNING id",
                params, Long.class);
        params.addValue("id", id);
        PromptTemplateRow row = queryOne(" WHERE id = :id", params);
        return row == null ? null : row.toInfo();
    }

    private PromptTemplateRow queryOne(String whereSuffix, MapSqlParameterSource params) {
        List<PromptTemplateRow> rows = named.query(SELECT_COLUMNS + whereSuffix, params,
                new BeanPropertyRowMapper<>(PromptTemplateRow.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(label + "不能为空");
        }
        return value.trim();
    }
}
