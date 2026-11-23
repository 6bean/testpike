package com.sixbean.testpike.core.cases.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class AutoAssignSupport {

    private final JdbcTemplate jdbcTemplate;

    public AutoAssignSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public List<AutoAssignCandidate> candidates(long projectId) {
        String sql = """
                WITH cand AS (
                    SELECT m.user_id,
                           COALESCE(u.display_name, u.username) AS name
                    FROM tp_user_project m
                    LEFT JOIN tp_user u ON u.id = m.user_id AND u.deleted = 0
                    LEFT JOIN tp_user_role ur ON ur.user_id = m.user_id
                    LEFT JOIN tp_role r ON r.id = ur.role_id AND r.status = 1
                    WHERE m.project_id = ?
                      AND (r.code IN ('tester', 'test_lead') OR u.role IN (1, 2))
                    GROUP BY m.user_id, u.display_name, u.username
                ),
                load_in_proj AS (
                    SELECT tc.assignee_id, count(*) AS n
                    FROM tp_test_case tc
                    JOIN cand c ON c.user_id = tc.assignee_id
                    WHERE tc.project_id = ? AND tc.status IN (1, 2) AND tc.deleted = 0
                    GROUP BY tc.assignee_id
                ),
                load_total AS (
                    SELECT tc.assignee_id, count(*) AS n
                    FROM tp_test_case tc
                    JOIN cand c ON c.user_id = tc.assignee_id
                    WHERE tc.deleted = 0
                    GROUP BY tc.assignee_id
                )
                SELECT c.user_id, c.name,
                       COALESCE(l1.n, 0), COALESCE(l2.n, 0)
                FROM cand c
                LEFT JOIN load_in_proj l1 ON l1.assignee_id = c.user_id
                LEFT JOIN load_total l2 ON l2.assignee_id = c.user_id
                ORDER BY c.user_id
                """;
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new AutoAssignCandidate(
                        rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)),
                projectId, projectId);
    }

    
    public List<AutoAssignCandidate> dispatch(List<AutoAssignCandidate> candidates, int caseCount) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException("候选池为空不应进入派发（调用方须先行校验并给出可读错误）");
        }
        Map<Long, Integer> virtual = new HashMap<>();
        for (AutoAssignCandidate c : candidates) {
            virtual.put(c.userId(), c.pendingLoad());
        }
        List<AutoAssignCandidate> pool = new ArrayList<>(candidates);
        List<AutoAssignCandidate> result = new ArrayList<>(caseCount);
        for (int i = 0; i < caseCount; i++) {
            AutoAssignCandidate best = pool.get(0);
            for (AutoAssignCandidate c : pool) {
                int load = virtual.get(c.userId());
                int bestLoad = virtual.get(best.userId());
                if (load < bestLoad
                        || (load == bestLoad && c.totalAssigned() < best.totalAssigned())
                        || (load == bestLoad && c.totalAssigned() == best.totalAssigned()
                                && c.userId() < best.userId())) {
                    best = c;
                }
            }
            virtual.put(best.userId(), virtual.get(best.userId()) + 1);
            result.add(best);
        }
        return result;
    }
}
