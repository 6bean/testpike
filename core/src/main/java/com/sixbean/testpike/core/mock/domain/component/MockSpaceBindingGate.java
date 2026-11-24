package com.sixbean.testpike.core.mock.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

/**
 * Single save-chain gate for declarative mock bindings: a case or regression suite may name a mock space as the simulation
 * scope its runs stand in for — but only a PUBLISHED space of its OWN project.
 * <p>One gate stands behind all four write chains (case create/update, suite
 * create/update) so the refusal wording cannot diverge between surfaces, and so
 * H1 project isolation is checked exactly once (never re-implemented in admin
 * bridges or controllers). Unbind (null / 0) is always legal whatever the space
 * state: releasing a declaration must never be blocked by the gate guarding it.
 * <p>Draft refusal teaches the operator the why (a draft serves no rules yet);
 * retired refusal names the terminal-state rule (retired is history, copy or
 * publish a new space instead of pointing live runs at dead rules).
 */
@Component
public class MockSpaceBindingGate {

    private final NamedParameterJdbcTemplate jdbc;

    public MockSpaceBindingGate(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Validate one binding value against the owning project.
     *
     * @param projectId the project of the case/suite being saved
     * @param spaceId   declared binding (null or 0 = unbind, always accepted)
     */
    public void requireBindable(long projectId, Long spaceId) {
        if (spaceId == null || spaceId == 0L) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT project_id, status FROM tp_mock_space WHERE id = :id
                """, new MapSqlParameterSource("id", spaceId));
        if (rows.isEmpty()) {
            throw new ServiceException(MockRuleRefusals.bindingSpaceMissing(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> row = rows.get(0);
        long ownerId = ((Number) row.get("project_id")).longValue();
        if (ownerId != projectId) {
            throw new ServiceException(MockRuleRefusals.bindingSpaceOtherProject(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        int status = ((Number) row.get("status")).intValue();
        if (status == 0) {
            throw new ServiceException(MockRuleRefusals.bindingSpaceDraft(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (status == 2) {
            throw new ServiceException(MockRuleRefusals.bindingSpaceRetired(spaceId),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
