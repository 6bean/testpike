package com.sixbean.testpike.core.usage.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.usage.api.dto.UsageCalibrationInfo;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInput;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInfo;
import com.sixbean.testpike.core.usage.domain.service.UsageCalibrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class UsageCalibrationServiceImpl implements UsageCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(UsageCalibrationServiceImpl.class);

 
    public static final int DAYS_MAX = 90;
    public static final int DAYS_DEFAULT = 7;
    
    public static final int PAGE_SIZE_MAX = 100;

    private final JdbcTemplate jdbcTemplate;

    public UsageCalibrationServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void record(UsageSampleInput input) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO tp_llm_usage_sample
                        (llm_config_id, provider, model, chain, project_id,
                         est_tokens, usage_prompt_tokens, completion_tokens, latency_ms,
                         tokenizer_engine, cached_tokens)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, input.llmConfigId(), input.provider(), input.model(), input.chain(),
                    input.projectId(), input.estTokens(), input.usagePromptTokens(),
                    input.completionTokens(), input.latencyMs(), input.tokenizerEngine(),
                    input.cachedTokens());
        } catch (Exception e) {
            log.warn("usage 采样落库失败（观测面忽略）model={} err={}", input.model(), e.getMessage());
        }
    }

    @Override
    public List<UsageCalibrationInfo> calibration(int days) {
        if (days < 1 || days > DAYS_MAX) {
            throw new ServiceException("统计窗口须为 1~" + DAYS_MAX + " 天（当前 " + days + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return jdbcTemplate.query("""
                SELECT model,
                       (array_agg(provider ORDER BY created_at DESC))[1]            AS provider,
                       count(*)                                                      AS sample_count,
                       avg(deviation_rate) * 100                                     AS avg_dev_pct,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY abs(deviation_rate)) * 100 AS p95_abs_dev_pct,
                       avg(est_tokens)                                               AS avg_est,
                       avg(usage_prompt_tokens)                                      AS avg_usage,
                       to_char(max(created_at), 'YYYY-MM-DD HH24:MI:SS')             AS last_seen
                FROM tp_llm_usage_sample
                WHERE created_at >= now() - (? || ' days')::interval
                GROUP BY model
                ORDER BY sample_count DESC, model
                """, (rs, rowNum) -> new UsageCalibrationInfo(
                rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getObject(4) == null ? null : rs.getDouble(4),
                rs.getObject(5) == null ? null : rs.getDouble(5),
                rs.getObject(6) == null ? null : rs.getDouble(6),
                rs.getObject(7) == null ? null : rs.getDouble(7),
                rs.getString(8)), days);
    }

    @Override
    public PageResult<UsageSampleInfo> detailPage(String model, String chain, String keyword,
                                                  int page, int size) {
        return detailPage(model, chain, keyword, page, size, null, null);
    }

    /**
 * Server-side sort whitelist for the sample detail window .
 * Values are physical columns of tp_llm_usage_sample; deviation_rate is the
 * generated column the rows render, so it sorts like any other key. The map
 * output is the sole thing reaching the ORDER BY clause.
 */
    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("id", "id"),
            Map.entry("model", "model"),
            Map.entry("provider", "provider"),
            Map.entry("chain", "chain"),
            Map.entry("project_id", "project_id"),
            Map.entry("est_tokens", "est_tokens"),
            Map.entry("usage_prompt_tokens", "usage_prompt_tokens"),
            Map.entry("deviation_rate", "deviation_rate"),
            Map.entry("latency_ms", "latency_ms"),
            Map.entry("cached_tokens", "cached_tokens"),
            Map.entry("created_at", "created_at"));

    /**
     * ORDER BY builder for the sample window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column with
     * the id DESC tie-break pinned; an off-list key is refused fail-closed.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "usage 采样");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public PageResult<UsageSampleInfo> detailPage(String model, String chain, String keyword,
                                                  int page, int size, String sortBy, String sortDir) {
        List<String> where = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (model != null && !model.isBlank()) {
            where.add("model = ?");
            args.add(model.trim());
        }
        if (chain != null && !chain.isBlank()) {
            where.add("chain = ?");
            args.add(chain.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            where.add("(model ILIKE ? OR provider ILIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_llm_usage_sample" + whereSql, Long.class, args.toArray());
        int effectiveSize = Math.min(Math.max(size, 1), PAGE_SIZE_MAX);
        int offset = Math.max(page - 1, 0) * effectiveSize;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(effectiveSize);
        pageArgs.add(offset);
        List<UsageSampleInfo> list = jdbcTemplate.query(
                "SELECT id, model, provider, chain, project_id, est_tokens, usage_prompt_tokens,"
                        + " deviation_rate, latency_ms,"
                        + " to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'),"
                        + " tokenizer_engine, cached_tokens FROM tp_llm_usage_sample"
                        + whereSql + orderClause(sortBy, sortDir) + " LIMIT ? OFFSET ?",
                this::mapSample, pageArgs.toArray());
        return new PageResult<>(list, total == null ? 0 : total);
    }

    private UsageSampleInfo mapSample(ResultSet rs, int rowNum) throws SQLException {
        Object dev = rs.getObject(8);
        return new UsageSampleInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), (Long) rs.getObject(5), rs.getInt(6), rs.getInt(7),
                dev == null ? null : ((Number) dev).doubleValue(),
                rs.getLong(9), rs.getString(10), rs.getString(11), rs.getInt(12));
    }
}
