package com.sixbean.testpike.core.retention.domain.component;

import com.sixbean.testpike.core.audit.domain.component.AuditHashChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;


@Component
public class AuditRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionCleaner.class);

    
    private static final int BATCH_LIMIT = 20_000;

    private final JdbcTemplate jdbcTemplate;

    public AuditRetentionCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public long previewExpiredPrefixCount(LocalDateTime cutoff) {
        Long count = jdbcTemplate.queryForObject("""
                WITH marked AS (
                    SELECT id, created_at,
                           COALESCE(SUM(CASE WHEN created_at >= ? THEN 1 ELSE 0 END)
                               OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
                               AS not_expired_before
                    FROM tp_audit_log
                )
                SELECT count(*) FROM marked WHERE created_at < ? AND not_expired_before = 0
                """, Long.class, cutoff, cutoff);
        return count == null ? 0 : count;
    }

    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long cleanExpiredPrefix(LocalDateTime cutoff) {
        List<Long> ids = jdbcTemplate.queryForList("""
                WITH marked AS (
                    SELECT id, created_at,
                           COALESCE(SUM(CASE WHEN created_at >= ? THEN 1 ELSE 0 END)
                               OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0)
                               AS not_expired_before
                    FROM tp_audit_log
                )
                SELECT id FROM marked
                 WHERE created_at < ? AND not_expired_before = 0
                 ORDER BY id LIMIT ?""",
                Long.class, cutoff, cutoff, BATCH_LIMIT);
        if (ids.isEmpty()) {
            return 0;
        }
        long throughId = ids.get(ids.size() - 1);

        String headAtBreak = jdbcTemplate.query(
                "SELECT cur_hash FROM tp_audit_log WHERE id = ?",
                (rs, n) -> rs.getString(1), throughId).stream().findFirst().orElse(AuditHashChain.CHAIN_HEAD);

        jdbcTemplate.execute("SELECT set_config('tp.audit_retention_allowed', 'on', true)");
        int deleted = jdbcTemplate.update(
                "DELETE FROM tp_audit_log WHERE id <= ?", throughId);
        if (deleted != ids.size()) {
            throw new IllegalStateException("审计前缀清理行数不一致: expect=" + ids.size() + " actual=" + deleted);
        }

        Long remainCount = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_audit_log", Long.class);
        String newHeadHash = (remainCount == null || remainCount == 0L)
                ? AuditHashChain.CHAIN_HEAD : headAtBreak;
        Long beforeCount = (remainCount == null ? 0 : remainCount) + (long) deleted;
        jdbcTemplate.update("""
                INSERT INTO tp_audit_retention_break
                    (segment_from_id, removed_through_id, new_head_hash, removed_count, cutoff_at, before_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """, ids.get(0), throughId, newHeadHash, deleted, cutoff, beforeCount);
        log.info("审计日志保留清理：删除 id {}~{} 共 {} 条（截止 {}），链起点已登记账本",
                ids.get(0), throughId, deleted, cutoff);
        return deleted;
    }
}
