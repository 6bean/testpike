package com.sixbean.testpike.core.dashboard.domain.service.impl;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.dashboard.api.dto.ActivityItem;
import com.sixbean.testpike.core.dashboard.api.dto.DashboardOverview;
import com.sixbean.testpike.core.dashboard.api.dto.ProjectStat;
import com.sixbean.testpike.core.dashboard.domain.service.DashboardService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;


@Service
public class DashboardServiceImpl implements DashboardService {

    
    private static final int ACTIVITY_LIMIT = 5;
    
    private static final int DEFAULT_TIMEOUT_HOURS = 72;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public DashboardServiceImpl(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    @Override
    public DashboardOverview overview(long userId, boolean allVisible) {
        long pendingReview = countPendingReview(userId, allVisible);
        long unassignedReview = countUnassignedReview(userId, allVisible);
        long overdueReview = countOverdueReview(userId, allVisible);
        long runningTasks = countVisible(userId, allVisible,
                "t.project_id", "SELECT COUNT(*) FROM tp_exec_task t WHERE t.status IN (1, 2)");
        long unclosedDefects = countVisible(userId, allVisible,
                "d.project_id", "SELECT COUNT(*) FROM tp_defect d WHERE d.status <> 5");
        List<ProjectStat> projects = listProjectStats(userId, allVisible);
        List<ActivityItem> activities = listActivities(userId);
        return new DashboardOverview(pendingReview, overdueReview, runningTasks,
                unclosedDefects, projects, activities, unassignedReview);
    }

    
    private long countOverdueReview(long userId, boolean allVisible) {
        int timeoutHours = readTimeoutHours();
        if (timeoutHours <= 0) {
            return 0;
        }
        return countVisible(userId, allVisible,
                "tc.project_id", "SELECT COUNT(*) FROM tp_test_case tc WHERE tc.deleted = 0"
                        + " AND tc.status IN (1, 2) AND tc.assignee_id = ?"
                        + " AND tc.assigned_at IS NOT NULL"
                        + " AND tc.assigned_at <= now() - (? || ' hours')::interval",
                userId, String.valueOf(timeoutHours));
    }

    private int readTimeoutHours() {
        try {
            return Integer.parseInt(
                    configService.getValue("review.timeout-hours", String.valueOf(DEFAULT_TIMEOUT_HOURS)).trim());
        } catch (NumberFormatException e) {
            return DEFAULT_TIMEOUT_HOURS;
        }
    }

    
    private long countPendingReview(long userId, boolean allVisible) {
        String base = "SELECT COUNT(*) FROM tp_test_case tc WHERE tc.deleted = 0"
                + " AND tc.status IN (1, 2) AND (tc.assignee_id = ? OR (" + unassignedPredicate(allVisible) + "))";
        return allVisible
                ? countVisible(userId, true, "tc.project_id", base, userId)
                : countVisible(userId, false, "tc.project_id", base, userId, userId);
    }

    
    private String unassignedPredicate(boolean allVisible) {
        return allVisible
                ? "tc.assignee_id IS NULL"
                : "tc.assignee_id IS NULL AND EXISTS (SELECT 1 FROM tp_user_project up2"
                        + " WHERE up2.project_id = tc.project_id AND up2.user_id = ? AND up2.role >= 2)";
    }

 
    private long countUnassignedReview(long userId, boolean allVisible) {
        String base = "SELECT COUNT(*) FROM tp_test_case tc WHERE tc.deleted = 0"
                + " AND tc.status IN (1, 2) AND (" + unassignedPredicate(allVisible) + ")";
        return allVisible
                ? countVisible(userId, true, "tc.project_id", base)
                : countVisible(userId, false, "tc.project_id", base, userId);
    }

    
    private long countVisible(long userId, boolean allVisible, String projectColumnRef,
                              String baseSql, Object... args) {
        String sql = baseSql;
        List<Object> params = new ArrayList<>(List.of(args));
        if (!allVisible) {
            sql += " AND " + visibleExists(projectColumnRef);
            params.add(userId);
        }
        Long n = jdbcTemplate.queryForObject(sql, Long.class, params.toArray());
        return n != null ? n : 0L;
    }

    
    private String visibleExists(String projectColumnRef) {
        return "EXISTS (SELECT 1 FROM tp_user_project up"
                + " WHERE up.project_id = " + projectColumnRef + " AND up.user_id = ?)";
    }

    
    private List<ProjectStat> listProjectStats(long userId, boolean allVisible) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.name, p.code,
                       (SELECT COUNT(*) FROM tp_test_case tc
                         WHERE tc.project_id = p.id AND tc.deleted = 0) AS case_count
                  FROM tp_project p WHERE p.status = 1""");
        List<Object> args = new ArrayList<>();
        if (!allVisible) {
            sql.append(" AND ").append(visibleExists("p.id"));
            args.add(userId);
        }
        sql.append(" ORDER BY p.id");
        return jdbcTemplate.query(sql.toString(),
                (rs, i) -> new ProjectStat(rs.getLong("id"), rs.getString("name"),
                        rs.getString("code"), rs.getLong("case_count")),
                args.toArray());
    }

    
    private List<ActivityItem> listActivities(long userId) {
        return jdbcTemplate.query("""
                SELECT id, project_id, event_type, title, dedup_key,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                  FROM tp_notification WHERE user_id = ?
                 ORDER BY id DESC""" + " LIMIT " + ACTIVITY_LIMIT,
                (rs, i) -> new ActivityItem(rs.getLong("id"),
                        rs.getObject("project_id") != null ? rs.getLong("project_id") : null,
                        rs.getString("event_type"), rs.getString("title"),
                        rs.getString("dedup_key"), rs.getString("created_at")),
                userId);
    }
}
