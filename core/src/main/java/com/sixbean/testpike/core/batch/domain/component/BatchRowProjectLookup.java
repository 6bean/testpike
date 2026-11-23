package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Batch family bridge from a governed read row to the project that owns it.
 *
 * <p>The batch table carries the owning project as its isolation column, so the bridge reads
 * exactly that column by row id — no detail assembly, no second ownership rule. An empty result
 * means the row cannot be judged and the door refuses the call.
 */
@Component
public class BatchRowProjectLookup implements RowProjectLookup {

    private static final String SELECT_PROJECT_OF_BATCH =
            "SELECT project_id FROM tp_exec_batch WHERE id = :id";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BatchRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "batch";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long batchId = RowIdValues.longOrNull(rowIdValue);
        if (batchId == null) {
            return null;
        }
        List<Long> rows = jdbcTemplate.queryForList(SELECT_PROJECT_OF_BATCH,
                Map.of("id", batchId), Long.class);
        Long owner = rows.isEmpty() ? null : rows.get(0);
        return owner == null ? null : List.of(owner);
    }
}
