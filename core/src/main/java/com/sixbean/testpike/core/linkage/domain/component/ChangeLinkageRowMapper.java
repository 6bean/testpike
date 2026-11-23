package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.api.dto.ChangeLinkageEdgeInput;
import com.sixbean.testpike.core.linkage.infrastructure.dataobject.ChangeLinkageRow;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maps one tp_change_linkage row into the domain's row record, assembling the
 * registered definition as a single {@link ChangeLinkageEdgeInput} (single
 * source for every select in the linkage domain; same placement pattern as
 * GoldenDriftVerdictRowMapper).
 */
public class ChangeLinkageRowMapper implements RowMapper<ChangeLinkageRow> {

    public static final ChangeLinkageRowMapper INSTANCE = new ChangeLinkageRowMapper();

    private ChangeLinkageRowMapper() {
    }

    @Override
    public ChangeLinkageRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        ChangeLinkageEdgeInput definition = new ChangeLinkageEdgeInput(
                rs.getString("edge_key"),
                rs.getString("upstream_object_type"),
                rs.getString("downstream_object_type"),
                (Integer) rs.getObject("detector_kind", Integer.class),
                rs.getString("detector_ref"),
                rs.getString("trigger_field_predicate"),
                rs.getString("disposition_strategy"),
                (Integer) rs.getObject("risk_level", Integer.class),
                (Integer) rs.getObject("exempt", Integer.class),
                rs.getString("exempt_reason"),
                rs.getString("edge_note"));
        return new ChangeLinkageRow(
                rs.getLong("id"),
                definition,
                (Integer) rs.getObject("enabled", Integer.class),
                rs.getObject("created_at", Timestamp.class),
                rs.getObject("updated_at", Timestamp.class));
    }
}
