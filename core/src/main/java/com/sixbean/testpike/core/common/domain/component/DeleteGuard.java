package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.core.common.api.dto.DeleteCheckResult;
import com.sixbean.testpike.core.common.api.dto.DeleteRefInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deletion-protection reference-check component for the deletion-protection reference matrix in the database design.
 * <p>Reference matrix: project to environments, cases, plans, and members, environment to batches, role to users, plan to batches and deviations,
 * Tag to cases, case to plans, and the pre-deletion check returns a human-readable count message such as 'This project still has 3 environments and 156 cases'.
 * <p>Domain deletion APIs throw a human-readable 40904 error when force=false and the resource is referenced, and the frontend first calls the delete-check endpoint to display references,
 * After the user confirms cascade deletion, the delete request is sent with force=true as a second confirmation.
 *
 */
@Component
public class DeleteGuard {

    private final JdbcTemplate jdbcTemplate;

    public DeleteGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Project reference check: environments, cases, plans, and members. */
    public DeleteCheckResult checkProject(long projectId) {
        List<DeleteRefInfo> refs = new ArrayList<>();
        long envCount = count("SELECT count(*) FROM tp_project_env WHERE project_id = ?", projectId);
        if (envCount > 0) {
            refs.add(new DeleteRefInfo("env", "环境", envCount, envCount + " 套环境"));
        }
        long caseCount = count("SELECT count(*) FROM tp_test_case WHERE project_id = ?", projectId);
        if (caseCount > 0) {
            refs.add(new DeleteRefInfo("case", "用例", caseCount, caseCount + " 条用例"));
        }
        long planCount = count("SELECT count(*) FROM tp_exec_plan WHERE project_id = ?", projectId);
        if (planCount > 0) {
            refs.add(new DeleteRefInfo("plan", "执行计划", planCount, planCount + " 个执行计划"));
        }
        long memberCount = count("""
                SELECT count(*) FROM tp_user_project up JOIN tp_project p ON p.id = up.project_id
                WHERE up.project_id = ? AND up.user_id <> p.created_by
                """, projectId);
        if (memberCount > 0) {
            refs.add(new DeleteRefInfo("member", "成员", memberCount, memberCount + " 名成员"));
        }
        return build(refs);
    }

    /** Environment reference check: execution batches (tp_exec_batch.env_id). */
    public DeleteCheckResult checkEnv(long envId) {
        List<DeleteRefInfo> refs = new ArrayList<>();
        long batchCount = count("SELECT count(*) FROM tp_exec_batch WHERE env_id = ?", envId);
        if (batchCount > 0) {
            refs.add(new DeleteRefInfo("batch", "执行批次", batchCount, batchCount + " 个执行批次"));
        }
 // If the environment is referenced by a database connection, deletion is blocked with a human-readable message, and force cascades deletion of the connection rows.
        long connCount = count("SELECT count(*) FROM tp_db_connection WHERE env_id = ?", envId);
        if (connCount > 0) {
            refs.add(new DeleteRefInfo("db_connection", "数据库连接", connCount, connCount + " 个数据库连接"));
        }
        return build(refs);
    }

    /** Role reference check: assigned users (built-in roles are additionally marked as non-deletable). */
    public DeleteCheckResult checkRole(long roleId) {
        List<DeleteRefInfo> refs = new ArrayList<>();
        long userCount = count("SELECT count(*) FROM tp_user_role WHERE role_id = ?", roleId);
        if (userCount > 0) {
            refs.add(new DeleteRefInfo("user", "用户", userCount, userCount + " 名用户"));
        }
        Integer builtin = jdbcTemplate.queryForObject(
                "SELECT builtin FROM tp_role WHERE id = ?", Integer.class, roleId);
        if (builtin != null && builtin == 1) {
            refs.add(new DeleteRefInfo("builtin", "内置角色", 1, "内置角色不可删除"));
        }
        return build(refs);
    }

    /** Plan reference check: execution batches and deviation records. */
    public DeleteCheckResult checkPlan(long planId) {
        List<DeleteRefInfo> refs = new ArrayList<>();
        long batchCount = count("SELECT count(*) FROM tp_exec_batch WHERE plan_id = ?", planId);
        if (batchCount > 0) {
            refs.add(new DeleteRefInfo("batch", "执行批次", batchCount, batchCount + " 个执行批次"));
        }
        long deviationCount = count("SELECT count(*) FROM tp_plan_deviation WHERE plan_id = ?", planId);
        if (deviationCount > 0) {
            refs.add(new DeleteRefInfo("deviation", "偏差记录", deviationCount, deviationCount + " 条偏差记录"));
        }
        return build(refs);
    }

    /** Tag reference check: associated cases. */
    public DeleteCheckResult checkTag(long tagId) {
        List<DeleteRefInfo> refs = new ArrayList<>();
        long caseCount = count("SELECT count(*) FROM tp_case_tag WHERE tag_id = ?", tagId);
        if (caseCount > 0) {
            refs.add(new DeleteRefInfo("case", "用例", caseCount, caseCount + " 条用例"));
        }
        return build(refs);
    }

    /** Case reference check: execution plans included via tp_plan_case. */
    public DeleteCheckResult checkCase(long caseId) {
        List<DeleteRefInfo> refs = new ArrayList<>();
        long planCount = count("""
                SELECT count(*) FROM tp_plan_case pc JOIN tp_exec_plan p ON p.id = pc.plan_id
                WHERE pc.case_id = ? AND p.status <> 5
                """, caseId);
        if (planCount > 0) {
            refs.add(new DeleteRefInfo("plan", "执行计划", planCount, planCount + " 个执行计划"));
        }
        return build(refs);
    }

    /** Human-readable summary: deletable and message (e.g., 'This project still has 3 environments and 156 cases'). */
    public static DeleteCheckResult build(List<DeleteRefInfo> refs) {
        if (refs.isEmpty()) {
            return DeleteCheckResult.clean();
        }
        String message = "该项目下还有 " + joinMessages(refs);
        return new DeleteCheckResult(refs, false, message);
    }

    /** Compose '3 environments and 156 cases' (reused as the human-readable 409 message by domain deletion APIs). */
    public static String joinMessages(List<DeleteRefInfo> refs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                sb.append("和");
            }
            sb.append(refs.get(i).message());
        }
        return sb.toString();
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value != null ? value : 0L;
    }
}
