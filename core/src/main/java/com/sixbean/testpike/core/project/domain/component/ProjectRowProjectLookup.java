package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.component.RowIdValues;
import com.sixbean.testpike.core.apiaccess.domain.component.RowProjectLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Project family bridge from a governed read row to the project that owns it.
 *
 * <p>A project owns its own data, so the verdict is the row itself: the bridge asks only whether
 * the project row exists and answers with that id. Running the full detail assembly per governed
 * read would compute member-facing projections nobody asks for. An absent row resolves to nothing
 * and the door refuses the call.
 */
@Component
public class ProjectRowProjectLookup implements RowProjectLookup {

    private static final String SELECT_EXISTING_PROJECT =
            "SELECT id FROM tp_project WHERE id = :id";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ProjectRowProjectLookup(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String entity() {
        return "project";
    }

    @Override
    public List<Long> ownerProjectIds(Object rowIdValue) {
        Long projectId = RowIdValues.longOrNull(rowIdValue);
        if (projectId == null) {
            return null;
        }
        List<Long> rows = jdbcTemplate.queryForList(SELECT_EXISTING_PROJECT,
                Map.of("id", projectId), Long.class);
        return rows.isEmpty() ? null : List.of(rows.get(0));
    }
}
