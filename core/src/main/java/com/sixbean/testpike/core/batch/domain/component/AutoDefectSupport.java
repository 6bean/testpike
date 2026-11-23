package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.defects.domain.component.FailureSignatureSupport;
import com.sixbean.testpike.core.defects.domain.component.JiraSyncSupport;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Component
public class AutoDefectSupport {

    private static final Logger log = LoggerFactory.getLogger(AutoDefectSupport.class);

    
    public static final String KEY_ENABLED = "defect.auto-create.enabled";
    public static final String KEY_DEDUP_WINDOW = "defect.auto-create.dedup-window";
    private static final String DEFAULT_WINDOW_HOURS = "24";

    
    private static final String FAILED_TASK_STATUSES = "(4, 5)";
    
    private static final String HOST_DEFECT = "defect";
    
    private static final int DEFECT_NEW = 1;
    private static final int DEFECT_REOPENED = 6;

    private final JdbcTemplate jdbcTemplate;
    private final FailureSignatureSupport failureSignatureSupport;
    private final ProjectOwners projectOwners;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final JiraSyncSupport jiraSyncSupport;

    public AutoDefectSupport(JdbcTemplate jdbcTemplate,
                             FailureSignatureSupport failureSignatureSupport,
                             ProjectOwners projectOwners,
                             NotificationService notificationService,
                             AfterCommitExecutor afterCommitExecutor,
                             JiraSyncSupport jiraSyncSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.failureSignatureSupport = failureSignatureSupport;
        this.projectOwners = projectOwners;
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
        this.jiraSyncSupport = jiraSyncSupport;
    }

    
    public void onBatchPartialFailed(long batchId) {
        try {
            if (!enabled()) {
                return;
            }
            List<Map<String, Object>> tasks = queryFailedTasks(batchId);
            if (tasks.isEmpty()) {
                return;
            }
            Map<String, List<Map<String, Object>>> groups = groupBySignature(tasks);
            for (List<Map<String, Object>> group : groups.values()) {
                mergeOrCreate(batchId, group);
            }
        } catch (Exception e) {
            log.warn("批次 {} 自动建缺陷失败（不阻断收口）: {}", batchId, e.getMessage(), e);
        }
    }

    private boolean enabled() {
        return "1".equals(readConfig(KEY_ENABLED, "0"));
    }

    private String readConfig(String key, String defaultValue) {
        String value = jdbcTemplate.query(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null, key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    
    private List<Map<String, Object>> queryFailedTasks(long batchId) {
        return jdbcTemplate.queryForList("""
                SELECT t.id AS task_id, t.case_id, t.error_type, t.error_detail,
                       COALESCE((SELECT (elem->>'stepNo')::int
                                   FROM jsonb_array_elements(t.steps_timeline) elem
                                  WHERE elem->>'status' = 'fail' LIMIT 1), 0) AS fail_step,
                       tc.title AS case_title, b.project_id
                FROM tp_exec_task t
                JOIN tp_test_case tc ON tc.id = t.case_id AND tc.deleted = 0
                JOIN tp_exec_batch b ON b.id = t.batch_id
                WHERE t.batch_id = ? AND t.status IN """ + FAILED_TASK_STATUSES + """
                 AND t.case_id IS NOT NULL
                ORDER BY t.id
                """, batchId);
    }

    
    private Map<String, List<Map<String, Object>>> groupBySignature(List<Map<String, Object>> tasks) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> task : tasks) {
            long caseId = ((Number) task.get("case_id")).longValue();
            String signature = signatureOf(task, caseId);
            groups.computeIfAbsent(caseId + "|" + signature, k -> new ArrayList<>()).add(task);
        }
        return groups;
    }

    private String signatureOf(Map<String, Object> task, long caseId) {
        Integer errorType = task.get("error_type") == null ? null : ((Number) task.get("error_type")).intValue();
        int failStep = ((Number) task.get("fail_step")).intValue();
        return failureSignatureSupport.signature(caseId, failStep, errorType,
                (String) task.get("error_detail"));
    }

    
    private void mergeOrCreate(long batchId, List<Map<String, Object>> group) {
        Map<String, Object> first = group.get(0);
        long caseId = ((Number) first.get("case_id")).longValue();
        long projectId = ((Number) first.get("project_id")).longValue();
        String signature = signatureOf(first, caseId);

        Long targetId = findMergeTarget(caseId, signature);
        boolean fresh = false;
        if (targetId == null) {
            try {
                targetId = insertDefect(batchId, projectId, caseId, first, signature);
                fresh = true;
            } catch (DuplicateKeyException e) {
                targetId = findMergeTarget(caseId, signature);
                if (targetId == null) {
                    log.warn("批次 {} 用例 {} 自动建单撞唯一索引但追评目标缺失（跳过该组）", batchId, caseId);
                    return;
                }
            }
        } else {
            reopenIfClosed(targetId);
        }
        appendComments(targetId, fresh ? group.subList(1, group.size()) : group);
        if (fresh && targetId != null) {
            long syncedId = targetId;
            afterCommitExecutor.execute(() -> jiraSyncSupport.syncDefect(syncedId));
        }
    }

    
    private Long findMergeTarget(long caseId, String signature) {
        Long open = jdbcTemplate.query(
                "SELECT id FROM tp_defect WHERE case_id = ? AND source = 2 AND status <> 5"
                        + " AND fail_signature = ? ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, caseId, signature);
        if (open != null) {
            return open;
        }
        int windowHours = parseWindowHours();
        return jdbcTemplate.query(
                "SELECT id FROM tp_defect WHERE case_id = ? AND source = 2 AND status = 5"
                        + " AND fail_signature = ?"
                        + " AND last_seen_at >= now() - make_interval(hours => ?) ORDER BY id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, caseId, signature, windowHours);
    }

    private int parseWindowHours() {
        try {
            return Integer.parseInt(readConfig(KEY_DEDUP_WINDOW, DEFAULT_WINDOW_HOURS));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_WINDOW_HOURS);
        }
    }

    
    private void reopenIfClosed(long defectId) {
        jdbcTemplate.update("UPDATE tp_defect SET status = ?, updated_at = now() WHERE id = ? AND status = 5",
                DEFECT_REOPENED, defectId);
    }

    private Long insertDefect(long batchId, long projectId, long caseId,
                              Map<String, Object> first, String signature) {
        Long assignee = resolveAssignee(projectId);
        Long reporter = resolveReporter(projectId, assignee);
        String errorLabel = DefectLabels.errorTypeLabel(
                first.get("error_type") == null ? 0 : ((Number) first.get("error_type")).intValue());
        int failStep = ((Number) first.get("fail_step")).intValue();
        String title = truncate("【自动建单】" + first.get("case_title") + " 执行失败：" + errorLabel
                + (failStep > 0 ? "（第 " + failStep + " 步）" : ""), 200);
        String description = truncate("批次 #" + batchId + " 结束后检测到失败任务（错误类别：" + errorLabel
                + "）。错误详情：" + Objects.toString(first.get("error_detail"), "（无详情）")
                + "。去重签名 " + signature.substring(0, 8) + "…，本批同签名复现将追加评论不另立单。", 4000);
        Long defectId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_defect (project_id, case_id, title, description, severity, status,
                                       assignee_id, reporter_id, source, fail_signature,
                                       last_seen_at, occurrence_count)
                VALUES (?, ?, ?, ?, 2, ?, ?, ?, 2, ?, now(), 1)
                RETURNING id
                """, Long.class, projectId, caseId, title, description, DEFECT_NEW,
                assignee, reporter, signature);
        if (assignee == null) {
            notifyUnassigned(projectId, defectId, String.valueOf(first.get("case_title")));
        }
        return defectId;
    }

    
    private Long resolveAssignee(long projectId) {
        Long devOwner = jdbcTemplate.query(
                "SELECT p.dev_owner_id FROM tp_project p WHERE p.id = ? AND p.dev_owner_id IS NOT NULL"
                        + " AND EXISTS (SELECT 1 FROM tp_user_project up"
                        + " WHERE up.project_id = p.id AND up.user_id = p.dev_owner_id)",
                rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (devOwner != null) {
            return devOwner;
        }
        List<Long> owners = projectOwners.resolve(projectId);
        return owners.isEmpty() ? null : owners.get(0);
    }

    
    private Long resolveReporter(long projectId, Long assignee) {
        if (assignee != null) {
            return assignee;
        }
        Long member = jdbcTemplate.query(
                "SELECT user_id FROM tp_user_project WHERE project_id = ? ORDER BY user_id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, projectId);
        if (member != null) {
            return member;
        }
        return jdbcTemplate.query("SELECT created_by FROM tp_project WHERE id = ? AND created_by IS NOT NULL",
                rs -> rs.next() ? rs.getLong(1) : null, projectId);
    }

    
    private void appendComments(long defectId, List<Map<String, Object>> group) {
        int appended = 0;
        for (Map<String, Object> task : group) {
            long taskId = ((Number) task.get("task_id")).longValue();
            int inserted = jdbcTemplate.update("""
                    INSERT INTO tp_comment (host_type, host_id, source, content, ref_key)
                    VALUES (?, ?, 2, ?, ?)
                    ON CONFLICT (host_type, host_id, ref_key) WHERE ref_key IS NOT NULL DO NOTHING
                    """, HOST_DEFECT, defectId,
                    truncate("任务 #" + taskId + " 复现相同失败："
                            + Objects.toString(task.get("error_detail"), "（无详情）"), 1900),
                    "task:" + taskId);
            appended += inserted;
        }
        if (appended > 0) {
            jdbcTemplate.update("UPDATE tp_defect SET occurrence_count = occurrence_count + ?,"
                    + " last_seen_at = now(), updated_at = now() WHERE id = ?", appended, defectId);
        }
    }

    
    private void notifyUnassigned(long projectId, long defectId, String caseTitle) {
        List<Long> members = jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id FROM tp_user_project WHERE project_id = ? ORDER BY user_id",
                Long.class, projectId);
        if (members.isEmpty()) {
            log.warn("缺陷 {} 待指派但项目 {} 无成员可通知", defectId, projectId);
            return;
        }
        String title = "自动建单待指派：" + caseTitle;
        String content = "用例「" + caseTitle + "」失败自动创建的缺陷 #" + defectId
                + " 暂无可指派人（开发负责人与项目负责人均缺位），请前往缺陷列表认领处理。";
        afterCommitExecutor.execute(() -> {
            for (Long userId : members) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, projectId,
                            "DEFECT_UNASSIGNED", title, content,
                            "DEFECT_UNASSIGNED:" + defectId + ":" + userId));
                } catch (Exception e) {
                    log.warn("DEFECT_UNASSIGNED 通知失败（defect={} user={}）: {}",
                            defectId, userId, e.getMessage());
                }
            }
        });
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
