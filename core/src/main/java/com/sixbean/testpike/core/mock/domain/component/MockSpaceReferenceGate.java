package com.sixbean.testpike.core.mock.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of the "is anything still holding this space open" question
 * . Both consumers — the manual retire edge in
 * MockSpaceServiceImpl.transition and the scheduled expiry sweep in
 * MockSpaceExpirySweeper — ask the same gate, so a space whose cassette box is
 * open can never be retired or deleted down one path while the other allows it.
 * <p>Blocking references: an open recording (tp_mock_recording.status='recording' —
 * stopped/promoted/failed boxes are sealed history and never block), plus since the
 * M8 merge the declarative bindings of ruling C-4 — live (not soft-deleted)
 * test cases and active regression suites naming the space as their mock scope.
 * A space that a case or suite still runs against must not be retired out from
 * under them; both reason families are appended here, never as second queries
 * scattered in callers.
 */
@Component
public class MockSpaceReferenceGate {

    private final NamedParameterJdbcTemplate jdbc;

    public MockSpaceReferenceGate(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Human-readable blocking reasons (empty = free to retire/delete).
     * Plain words so the error itself teaches the operator what to close first.
     */
    public List<String> blockingReferences(long spaceId) {
        List<String> reasons = new ArrayList<>();
        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM tp_mock_recording
                 WHERE space_id = :sid AND status = 'recording'
                """, new MapSqlParameterSource("sid", spaceId), Integer.class);
        if (open != null && open > 0) {
            reasons.add("有 " + open + " 个录制开盒仍在采集流量，请先停止录制");
        }
        Integer cases = jdbc.queryForObject("""
                SELECT count(*) FROM tp_test_case
                 WHERE mock_space_id = :sid AND deleted = 0
                """, new MapSqlParameterSource("sid", spaceId), Integer.class);
        if (cases != null && cases > 0) {
            reasons.add("有 " + cases + " 条用例声明挂在本空间仿真，请先解绑或改挂其他空间");
        }
        Integer suites = jdbc.queryForObject("""
                SELECT count(*) FROM tp_regression_suite
                 WHERE mock_space_id = :sid AND status = 1
                """, new MapSqlParameterSource("sid", spaceId), Integer.class);
        if (suites != null && suites > 0) {
            reasons.add("有 " + suites + " 个启用中的回归套件挂在本空间仿真，请先解绑或停用套件");
        }
        return reasons;
    }
}
