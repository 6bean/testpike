package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftVerdict;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Maps a tp_ai_golden_drift_verdict row onto the verdict DTO ( second
 * round, item 4). pass_flag stays a 0/1 column so the CHECK constraint keeps
 * guarding the ledger without a boolean-type dependency.
 */
class GoldenDriftVerdictRowMapper implements RowMapper<GoldenDriftVerdict> {

    @Override
    public GoldenDriftVerdict mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new GoldenDriftVerdict(
                rs.getLong("id"), rs.getString("template_domain"), rs.getString("template_name"),
                rs.getLong("candidate_version_id"), rs.getLong("control_version_id"),
                rs.getString("pack_name"), (Integer) rs.getObject("pack_version"),
                rs.getString("snapshot_digest"), rs.getInt("sample_total"), rs.getInt("drifted_count"),
                rs.getBigDecimal("drift_percent_pp"), rs.getInt("threshold_pp"),
                rs.getInt("pass_flag") == 1, rs.getString("note"),
                created == null ? null : created.toLocalDateTime(),
                rs.getString("comparison_kind"), rs.getString("control_ref"),
                rs.getString("candidate_ref"));
    }
}
