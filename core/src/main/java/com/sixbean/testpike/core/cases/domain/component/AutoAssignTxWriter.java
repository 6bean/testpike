package com.sixbean.testpike.core.cases.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Automatically assigns batched transactional writes (>500 batched — each batch is an independent transaction, atomic within the batch with no partial writes).
 * <p>Row-by-row conditional update ({@code assignee_id IS NULL} precondition) is naturally idempotent: concurrent double clicks yield only one winner,
 * Rows already assigned or whose status has changed result in zero updated rows and are skipped without error.
 *
 */
@Component
public class AutoAssignTxWriter {

    private final JdbcTemplate jdbcTemplate;

    public AutoAssignTxWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Dispatches a batch (within the same transaction).
     *
     * @param pairs The list of pairs of case IDs and target member IDs (the caller splits into batches of 500)
     * @return List of case IDs that actually won the conditional update (losers/already assigned rows are not included)
     */
    @Transactional
    public List<Long> assignChunk(List<long[]> pairs) {
        List<Long> winners = new ArrayList<>(pairs.size());
        for (long[] pair : pairs) {
            int updated = jdbcTemplate.update(
                    "UPDATE tp_test_case SET assignee_id = ?, assigned_at = now(), updated_at = now()"
                            + " WHERE id = ? AND deleted = 0 AND assignee_id IS NULL AND status IN (1, 2)",
                    pair[1], pair[0]);
            if (updated > 0) {
                winners.add(pair[0]);
            }
        }
        return winners;
    }
}
