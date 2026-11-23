package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.infrastructure.dataobject.LinkageProposalRow;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maps one tp_linkage_proposal row into the domain row record — the single
 * select-shape source for every proposal query in the linkage domain (same
 * placement pattern as ChangeLinkageRowMapper).
 */
public class LinkageProposalRowMapper implements RowMapper<LinkageProposalRow> {

    public static final LinkageProposalRowMapper INSTANCE = new LinkageProposalRowMapper();

    private LinkageProposalRowMapper() {
    }

    @Override
    public LinkageProposalRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LinkageProposalRow(
                rs.getLong("id"),
                rs.getLong("impact_id"),
                rs.getString("downstream_object_type"),
                rs.getLong("downstream_object_id"),
                rs.getString("proposal_json"),
                rs.getString("schema_version"),
                rs.getString("model_name"),
                (Integer) rs.getObject("base_script_version", Integer.class),
                (Integer) rs.getObject("status", Integer.class),
                rs.getString("reject_reason"),
                rs.getObject("created_at", Timestamp.class),
                rs.getObject("updated_at", Timestamp.class));
    }
}
