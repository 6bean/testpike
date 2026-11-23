package com.sixbean.testpike.core.audit.domain.service;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.AuditChainVerifier;
import com.sixbean.testpike.core.audit.domain.component.AuditHashChain;
import com.sixbean.testpike.core.audit.domain.component.AuditJsonCanonicalizer;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.common.clientobject.CursorPage;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final String INSERT_SQL = """
            INSERT INTO tp_audit_log
                (operator_id, project_id, object_type, object_id, action,
                 before_json, after_json, prev_hash, cur_hash)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final AuditJsonCanonicalizer canonicalizer;
    private final AuditChainVerifier chainVerifier;

    public AuditLogServiceImpl(JdbcTemplate jdbcTemplate, AuditJsonCanonicalizer canonicalizer,
                               AuditChainVerifier chainVerifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.canonicalizer = canonicalizer;
        this.chainVerifier = chainVerifier;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLogInput input) {
        jdbcTemplate.execute("LOCK TABLE tp_audit_log IN EXCLUSIVE MODE");

        String prevHash = AuditHashChain.CHAIN_HEAD;
        List<String> tail = jdbcTemplate.query(
                "SELECT cur_hash FROM tp_audit_log ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1));
        if (!tail.isEmpty()) {
            prevHash = tail.get(0);
        }

        String beforeJson = canonicalizer.toCanonicalJson(input.before());
        String afterJson = canonicalizer.toCanonicalJson(input.after());
        String content = AuditHashChain.canonical(input.operatorId(), input.projectId(),
                input.objectType(), input.objectId(), input.action(),
                canonicalizer.normalizeJson(beforeJson), canonicalizer.normalizeJson(afterJson));
        String curHash = AuditHashChain.hash(prevHash, content);

        jdbcTemplate.update(INSERT_SQL,
                input.operatorId(), input.projectId(), input.objectType(), input.objectId(),
                input.action(), beforeJson, afterJson, prevHash, curHash);
    }

    
    public PageResult<AuditLog> query(Long operatorId, Long projectId, String objectType,
                                      String objectId, String action, int page, int size) {
        return query(operatorId, projectId, objectType, objectId, action, page, size, null, null);
    }

    /**
     * Columns the audit list may order by — physical tp_audit_log columns only;
     * the map output is the sole thing reaching the ORDER BY clause, so user
     * input can never be concatenated into SQL.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "operator_id", "operator_id",
            "object_type", "object_type",
            "object_id", "object_id",
            "action", "action",
            "created_at", "created_at");

    /**
     * ORDER BY builder for the audit window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column with
     * the id DESC tie-break pinned; an off-list key is refused fail-closed.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "审计日志");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public PageResult<AuditLog> query(Long operatorId, Long projectId, String objectType,
                                      String objectId, String action, int page, int size,
                                      String sortBy, String sortDir) {
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        int offset = (safePage - 1) * safeSize;

        AuditLogFilter where = buildFilter(operatorId, projectId, objectType, objectId, action);

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log" + where.sql(), Long.class, where.argsArray());

        List<AuditLog> list = jdbcTemplate.query(
                "SELECT id, operator_id, project_id, object_type, object_id, action,"
                        + " before_json, after_json, prev_hash, cur_hash, created_at"
                        + " FROM tp_audit_log" + where.sql()
                        + orderClause(sortBy, sortDir) + " LIMIT ? OFFSET ?",
                this::mapRow, where.withLimitOffset(safeSize, offset));
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    public CursorPage<AuditLog> queryCursor(Long operatorId, Long projectId, String objectType,
                                            String objectId, String action, Long afterId, int size) {
        int safeSize = PageQueryPolicy.enforceSize(size);
        AuditLogFilter where = buildFilter(operatorId, projectId, objectType, objectId, action);
        if (afterId != null) {
            where = where.with("id < ?", afterId);
        }
        List<AuditLog> rows = jdbcTemplate.query(
                "SELECT id, operator_id, project_id, object_type, object_id, action,"
                        + " before_json, after_json, prev_hash, cur_hash, created_at"
                        + " FROM tp_audit_log" + where.sql()
                        + " ORDER BY id DESC LIMIT ?",
                this::mapRow, where.withLimit(safeSize + 1));
        boolean hasMore = rows.size() > safeSize;
        List<AuditLog> list = hasMore ? rows.subList(0, safeSize) : rows;
        return new CursorPage<>(list, hasMore ? list.get(list.size() - 1).id() : null, hasMore);
    }

    
    public boolean verifyChain() {
        return chainVerifier.verify().valid();
    }

    private AuditLogFilter buildFilter(Long operatorId, Long projectId, String objectType,
                                       String objectId, String action) {
        return AuditLogFilter.of()
                .withIfPresent("operator_id = ?", operatorId)
                .withIfPresent("project_id = ?", projectId)
                .withIfPresent("object_type = ?", objectType)
                .withIfPresent("object_id = ?", objectId)
                .withIfPresent("action = ?", action);
    }

    private AuditLog mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AuditLog(
                rs.getLong("id"),
                nullableLong(rs, "operator_id"),
                nullableLong(rs, "project_id"),
                rs.getString("object_type"),
                rs.getString("object_id"),
                rs.getString("action"),
                rs.getString("before_json"),
                rs.getString("after_json"),
                rs.getString("prev_hash"),
                rs.getString("cur_hash"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }
}
