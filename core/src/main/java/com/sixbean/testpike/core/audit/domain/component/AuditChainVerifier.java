package com.sixbean.testpike.core.audit.domain.component;

import com.sixbean.testpike.core.audit.api.dto.ChainVerifyOutcome;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.RetentionBreak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class AuditChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(AuditChainVerifier.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuditJsonCanonicalizer canonicalizer;

    public AuditChainVerifier(JdbcTemplate jdbcTemplate, AuditJsonCanonicalizer canonicalizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.canonicalizer = canonicalizer;
    }

    
    public ChainVerifyOutcome verify() {
        String prevHash = AuditHashChain.CHAIN_HEAD;
        long afterId = 0L;
        List<AuditLog> head = jdbcTemplate.query(
                "SELECT id, prev_hash FROM tp_audit_log ORDER BY id ASC LIMIT 1",
                (rs, n) -> new AuditLog(rs.getLong("id"), null, null, null, null, null,
                        null, null, rs.getString("prev_hash"), null, null));
        if (!head.isEmpty() && !AuditHashChain.CHAIN_HEAD.equals(head.get(0).prevHash())) {
            long firstId = head.get(0).id();
            List<RetentionBreak> breaks = jdbcTemplate.query(
                    "SELECT removed_through_id, new_head_hash FROM tp_audit_retention_break"
                            + " WHERE removed_through_id < ? ORDER BY removed_through_id DESC LIMIT 1",
                    (rs, n) -> new RetentionBreak(rs.getLong(1), rs.getString(2)), firstId);
            if (!breaks.isEmpty() && breaks.get(0).newHeadHash().equals(head.get(0).prevHash())) {
                afterId = breaks.get(0).throughId();
                prevHash = breaks.get(0).newHeadHash();
            }
        }
        long scanned = 0;
        List<AuditLog> all = jdbcTemplate.query(
                "SELECT id, operator_id, project_id, object_type, object_id, action,"
                        + " before_json, after_json, prev_hash, cur_hash, created_at"
                        + " FROM tp_audit_log WHERE id > ? ORDER BY id ASC",
                this::mapRow, afterId);
        for (AuditLog row : all) {
            scanned++;
            if (!prevHash.equals(row.prevHash())) {
                log.warn("哈希链断裂：id={} prevHash 不匹配（期望 {}，实际 {}）", row.id(), prevHash, row.prevHash());
                return ChainVerifyOutcome.broken(row.id(), scanned);
            }
            String expected = AuditHashChain.hash(prevHash, AuditHashChain.canonical(
                    row.operatorId(), row.projectId(), row.objectType(), row.objectId(),
                    row.action(), canonicalizer.normalizeJson(row.beforeJson()),
                    canonicalizer.normalizeJson(row.afterJson())));
            if (!expected.equals(row.curHash())) {
                log.warn("哈希链篡改：id={} curHash 不匹配（期望 {}，实际 {}）", row.id(), expected, row.curHash());
                return ChainVerifyOutcome.broken(row.id(), scanned);
            }
            prevHash = row.curHash();
        }
        return ChainVerifyOutcome.ok(scanned);
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
