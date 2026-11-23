package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Single mapper from one {@code tp_linkage_impact} row to the api DTO
 * (: extracted because the proposal store's {@code impactOf} and the
 * new user-facing query service read the same ledger — a second copy of the
 * column list and conversions would drift the moment either side adds a
 * field).
 */
public final class LinkageImpactRowMapper implements RowMapper<LinkageImpactInfo> {

    /** Column list every impact read must select. */
    public static final String COLUMNS =
            "id, edge_key, upstream_object_type, upstream_object_id, "
                    + "upstream_version, downstream_object_type, downstream_id, "
                    + "disposition_strategy, status, annotated_at, consumed_at";

    @Override
    public LinkageImpactInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LinkageImpactInfo(
                rs.getLong("id"),
                rs.getString("edge_key"),
                rs.getString("upstream_object_type"),
                rs.getLong("upstream_object_id"),
                (Integer) rs.getObject("upstream_version", Integer.class),
                rs.getString("downstream_object_type"),
                rs.getLong("downstream_id"),
                rs.getString("disposition_strategy"),
                (Integer) rs.getObject("status", Integer.class),
                rs.getObject("annotated_at", java.time.LocalDateTime.class),
                rs.getObject("consumed_at", java.time.LocalDateTime.class));
    }
}
