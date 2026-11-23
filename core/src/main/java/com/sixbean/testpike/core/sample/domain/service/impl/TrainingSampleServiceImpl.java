package com.sixbean.testpike.core.sample.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.sample.api.dto.TrainingSampleItem;
import com.sixbean.testpike.core.sample.api.dto.WordingAdoptionStat;
import com.sixbean.testpike.core.sample.api.dto.WordingPair;
import com.sixbean.testpike.core.sample.domain.service.TrainingSampleService;
import com.sixbean.testpike.core.sample.infrastructure.dataobject.TrainingSampleRow;
import com.sixbean.testpike.core.sample.infrastructure.dataobject.WordingAdoptionStatRow;
import com.sixbean.testpike.core.sample.infrastructure.dataobject.WordingPairRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class TrainingSampleServiceImpl implements TrainingSampleService {

    
    static final Set<String> SAMPLE_TYPES = Set.of("驳回", "修改差异");

    
    private static final String SELECT_SAMPLE = """
            SELECT s.id, s.case_id, c.case_no, c.project_id,
                   s.sample_type, s.sample_json::text AS sample_json,
                   s.source_version, s.created_at, s.adopted
            FROM tp_training_sample s
            LEFT JOIN tp_test_case c ON c.id = s.case_id AND c.deleted = 0
            """;

    private final NamedParameterJdbcTemplate named;

    public TrainingSampleServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public PageResult<TrainingSampleItem> list(Long projectId, String sampleType, int page, int size) {
        return list(projectId, sampleType, page, size, null, null);
    }

    @Override
    public PageResult<TrainingSampleItem> list(Long projectId, String sampleType, int page, int size,
                                               String sortBy, String sortDir) {
        if (sampleType != null && !SAMPLE_TYPES.contains(sampleType)) {
            throw new ValidationException("样本类型仅支持 驳回/修改差异，当前请求：" + sampleType);
        }
        int safeSize = PageQueryPolicy.enforceSize(size);
        long offset = (long) Math.max(page - 1, 0) * safeSize;

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (projectId != null) {
            where.append(" AND c.deleted = 0 AND c.project_id = :projectId");
            params.addValue("projectId", projectId);
        }
        if (sampleType != null) {
            where.append(" AND s.sample_type = :sampleType");
            params.addValue("sampleType", sampleType);
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_training_sample s"
                        + " LEFT JOIN tp_test_case c ON c.id = s.case_id AND c.deleted = 0" + where,
                params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<TrainingSampleItem> list = named.query(
                SELECT_SAMPLE + where + orderClause(sortBy, sortDir) + " LIMIT :size OFFSET :offset",
                params, new BeanPropertyRowMapper<>(TrainingSampleRow.class))
                .stream().map(TrainingSampleRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    /**
 * Sort whitelist for the training-sample ledger . Values
 * are table-qualified physical columns because the ledger query joins
 * tp_test_case for the derived case/project dimensions — bare column names
 * like created_at would be ambiguous there. The map output is the only
 * thing reaching the ORDER BY clause, enforced fail-closed by the shared
 * sort-column gate. sample_json stays out: the table never renders it as
 * a column (the drawer shows the full sanitized payload of one row), so
 * exposing it would widen the contract without a consumer.
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "s.id",
            "case_no", "c.case_no",
            "project_id", "c.project_id",
            "sample_type", "s.sample_type",
            "source_version", "s.source_version",
            "created_at", "s.created_at");

    /**
     * ORDER BY builder for the ledger window: a null/blank key keeps the
     * historical s.id DESC default; a whitelisted key orders by that column
     * with the s.id DESC tie-break pinned (deterministic paging under equal
     * values); an off-list key is refused fail-closed before any read.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "训练样本");
        if (column == null) {
            return " ORDER BY s.id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", s.id DESC";
    }

    @Override
    public List<WordingPair> listWordingPairs(long projectId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return named.query("""
                SELECT r.id AS reject_sample_id, d.id AS diff_sample_id,
                       r.sample_json -> 'review' ->> 'remark' AS remark,
                       d.sample_json -> 'changes' ::text AS diff_changes_json,
                       r.created_at
                FROM tp_training_sample r
                JOIN tp_test_case c ON c.id = r.case_id AND c.deleted = 0
                LEFT JOIN LATERAL (
                    SELECT s2.id, s2.sample_json
                    FROM tp_training_sample s2
                    WHERE s2.case_id = r.case_id AND s2.sample_type = '修改差异' AND s2.adopted = 1
                    ORDER BY s2.id DESC LIMIT 1
                ) d ON true
                WHERE r.sample_type = '驳回' AND r.adopted = 1
                  AND c.project_id = :projectId
                  AND r.sample_json -> 'review' -> 'feedbackTypes' @> '["措辞不准确"]'::jsonb
                ORDER BY r.id DESC LIMIT :limit
                """, new MapSqlParameterSource().addValue("projectId", projectId)
                .addValue("limit", safeLimit),
                new BeanPropertyRowMapper<>(WordingPairRow.class))
                .stream().map(WordingPairRow::toInfo).toList();
    }

    @Override
    public void updateAdoption(long sampleId, int adopted) {
        if (adopted != 0 && adopted != 1) {
            throw new ValidationException("采纳状态仅支持 1=采纳 / 0=撤销，当前请求：" + adopted);
        }
        int updated = named.update("UPDATE tp_training_sample SET adopted = :adopted WHERE id = :id",
                new MapSqlParameterSource().addValue("adopted", adopted).addValue("id", sampleId));
        if (updated == 0) {
            throw new ServiceException("训练样本不存在（id=" + sampleId + "），无法变更采纳状态",
                    BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public List<TrainingSampleItem> listByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return named.query(SELECT_SAMPLE + " WHERE s.id IN (:ids) ORDER BY s.id",
                new MapSqlParameterSource("ids", ids),
                new BeanPropertyRowMapper<>(TrainingSampleRow.class))
                .stream().map(TrainingSampleRow::toInfo).toList();
    }

    @Override
    public Map<Long, TrainingSampleItem> latestDiffByCaseIds(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) {
            return Map.of();
        }
        return named.query("SELECT DISTINCT ON (s.case_id) s.id, s.case_id, c.case_no, c.project_id,\n"
                        + "       s.sample_type, s.sample_json::text AS sample_json,\n"
                        + "       s.source_version, s.created_at, s.adopted\n"
                        + "FROM tp_training_sample s\n"
                        + "LEFT JOIN tp_test_case c ON c.id = s.case_id AND c.deleted = 0\n"
                        + "WHERE s.case_id IN (:caseIds) AND s.sample_type = '修改差异' AND s.adopted = 1\n"
                        + "ORDER BY s.case_id, s.id DESC",
                new MapSqlParameterSource("caseIds", caseIds),
                new BeanPropertyRowMapper<>(TrainingSampleRow.class))
                .stream().map(TrainingSampleRow::toInfo)
                .collect(Collectors.toMap(TrainingSampleItem::caseId, i -> i));
    }

    @Override
    public List<WordingAdoptionStat> summaryWordingAdoption() {
        return named.query("""
                SELECT p.id AS project_id, p.name AS project_name,
                       w.cnt AS wording_rejects, COALESCE(d.cnt, 0) AS adopted_diffs
                FROM (
                    SELECT c.project_id, count(*) AS cnt
                    FROM tp_training_sample s
                    JOIN tp_test_case c ON c.id = s.case_id AND c.deleted = 0
                    WHERE s.sample_type = '驳回' AND s.adopted = 1
                      AND s.sample_json -> 'review' -> 'feedbackTypes' @> '["措辞不准确"]'::jsonb
                    GROUP BY c.project_id
                ) w
                JOIN tp_project p ON p.id = w.project_id
                LEFT JOIN (
                    SELECT c.project_id, count(*) AS cnt
                    FROM tp_training_sample s
                    JOIN tp_test_case c ON c.id = s.case_id AND c.deleted = 0
                    WHERE s.sample_type = '修改差异' AND s.adopted = 1
                    GROUP BY c.project_id
                ) d ON d.project_id = w.project_id
                ORDER BY w.cnt DESC, p.id
                """, new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(WordingAdoptionStatRow.class))
                .stream().map(WordingAdoptionStatRow::toInfo).toList();
    }
}
