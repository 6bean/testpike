package com.sixbean.testpike.core.aichat.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Single-source allocator of the per-target issue order on the draft ledger
 * .
 * <p>Every mint path that writes a same-target draft card — dialog frames,
 * system-origin drafts and extraction update proposals — consumes this one
 * component instead of hand-rolling its own numbering SQL: the renewal
 * policy's stale-exclusivity gate trusts the queue order, so two mint sites
 * numbering differently would silently mis-pick the surviving card.
 * <p>Numbering follows the highest seq ever issued for the target, never the
 * live row count: voided and applied cards keep their issue slot, so a
 * retired newest card cannot have its number re-handed to a newcomer.
 * <p>Mints racing for the same target may draw the same number (read-then-
 * insert without a lock); callers therefore order by {@code (seq, id)} —
 * the id tie-break keeps the queue deterministic under such a race.
 */
@Component
public class DraftSeqAllocator {

    private final NamedParameterJdbcTemplate named;

    public DraftSeqAllocator(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /**
     * @param targetId the entity the card aims at; {@code null} for
     *                 create-style proposals, which join no queue
     * @return the next 1-based issue number for that target, or {@code null}
     *         when there is no target to queue behind
     */
    public Integer nextSeq(Long targetId) {
        if (targetId == null) {
            return null;
        }
        Integer highest = named.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM tp_ai_chat_draft WHERE target_id = :target",
                new MapSqlParameterSource("target", targetId), Integer.class);
        return (highest == null ? 0 : highest) + 1;
    }
}
