package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.infrastructure.dataobject.ApiAccessGovernanceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the governance overlay of the pass-through door.
 *
 * <p>The overlay is small on purpose: it only carries rows where a human said something the
 * generated catalog cannot know (this endpoint has no project anchor but is safe to expose, or
 * this one is retired). A missing table is degraded to "no rows", which keeps unanchored
 * endpoints unreachable instead of failing the whole dialog turn — the master switch stays the
 * documented kill path.
 */
@Component
public class ApiAccessGovernanceRepository {

    /** Migration that creates the overlay table (named in the warning so an operator can find it). */
    static final String TABLE = "tp_ai_api_access_governance";

    private static final Logger log = LoggerFactory.getLogger(ApiAccessGovernanceRepository.class);

    private static final String SELECT_ALL =
            "SELECT endpoint_key, scope_verified, reachable, ai_desc, exclude_reason,"
                    + " row_owner_entity, row_owner_id_param, required_permission, search_terms FROM " + TABLE;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ApiAccessGovernanceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** All overlay rows by endpoint key (empty when nothing was registered). */
    public Map<String, ApiAccessGovernanceView> loadAll() {
        try {
            List<ApiAccessGovernanceRow> rows = jdbcTemplate.query(SELECT_ALL,
                    Map.of(), new BeanPropertyRowMapper<>(ApiAccessGovernanceRow.class));
            Map<String, ApiAccessGovernanceView> byKey = new LinkedHashMap<>();
            for (ApiAccessGovernanceRow row : rows) {
                byKey.put(row.getEndpointKey(), row.toInfo());
            }
            return byKey;
        } catch (RuntimeException ex) {
            log.warn("governance overlay unreadable ({}), unanchored endpoints stay unreachable: {}",
                    TABLE, ex.getMessage());
            return Map.of();
        }
    }
}
