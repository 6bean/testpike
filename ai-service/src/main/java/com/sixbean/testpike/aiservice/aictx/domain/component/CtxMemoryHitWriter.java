package com.sixbean.testpike.aiservice.aictx.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Project-memory hit accounting .
 *
 * <p>Every entry that actually took part in a system-prompt assembly bumps
 * {@code tp_ai_ctx_memory.hit_count} — the ledger face will read for
 * "is this convention still pulling weight". One parameterized statement per
 * batch (IN :ids), never a per-id round trip.
 *
 * <p>Write-side ownership: tp_ai_ctx_memory is written exclusively inside the
 * aictx domain (distill + hits, CtxDistillServiceImpl same-domain single
 * source); core only READS it through the skill
 * assembly view, so no cross-service write door is opened here. Callers keep
 * this off the generation critical path (SkillUsageRecorder bounded queue).
 */
@Component
public class CtxMemoryHitWriter {

    private final NamedParameterJdbcTemplate jdbc;

    public CtxMemoryHitWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Increment hit_count for the injected memory ids.
     *
     * @param ids tp_ai_ctx_memory ids that survived budget trimming; null/empty = no-op
     * @return rows actually updated (0 when a row vanished concurrently)
     */
    public int recordHits(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return jdbc.update("UPDATE tp_ai_ctx_memory SET hit_count = hit_count + 1"
                        + " WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));
    }
}
