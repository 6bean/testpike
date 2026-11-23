package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.core.cases.api.dto.DailyDigestResult;
import com.sixbean.testpike.core.cases.api.dto.DailyDigestStats;
import com.sixbean.testpike.core.cases.domain.service.DailyDigestService;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the daily report summary scan.
 * Assembles the four elements with a single GROUP BY project_id query using seven full-aggregation SQL statements and forbids per-project N+1 queries.
 * Yesterday's increment is defined as new test cases, successes, failures including timeouts counted as failures, new defects, and new coverage associations under the matrix active scope.
 * JOIN test cases excluding soft-deleted and archived records, and define the current snapshot as unclosed defects whose status is not closed (5) from the same DefectLabels source.
 * Pending-review backlog consists of status 1 or 2 and assigned records using the same criteria as expediting, and empty-data detection considers only increments because snapshots do not trigger sending.
 * Recipients are ProjectOwners, with role 2 falling back to the creator and sharing common components with /, unioned with created_by.
 * The LinkedHashSet union is ordered and deduplicated, with owner equal to creator yielding only one entry, using the same approach as LoginLockService.
 * Sending count uses the same criteria as deliver, with a pre-send existsByDedupKey precheck because idempotent create returns an existing ID.
 * New sends cannot be distinguished from resends, and create returning 0 indicates preference single-point filtering for default_off unsubscribed or opted-out.
 * Count as prefFiltered.
 *
 */
@Service
public class DailyDigestServiceImpl implements DailyDigestService {

    /** Defect closed status is DefectLabels.statusLabel(5), and unclosed means status not equal to 5. */
    private static final int DEFECT_CLOSED = 5;

    private static final Logger log = LoggerFactory.getLogger(DailyDigestServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;

    public DailyDigestServiceImpl(JdbcTemplate jdbcTemplate, NotificationService notificationService,
                                  ProjectOwners projectOwners) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
    }

    @Override
    public DailyDigestResult scan() {
        LocalDateTime from = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime to = LocalDate.now().atStartOfDay();
        String day = LocalDate.now().minusDays(1).toString(); // Summary statistics day is yesterday.

        Map<Long, DailyDigestStats> stats = collectStats(from, to);
        List<Long> activeIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE status = 1", Long.class);
        int scanned = 0;
        int skipped = 0;
        int sent = 0;
        int deduped = 0;
        int prefFiltered = 0;

        for (Map.Entry<Long, DailyDigestStats> e : stats.entrySet()) {
            long projectId = e.getKey();
            if (!activeIds.contains(projectId)) {
                continue; // Archived or soft-deleted projects do not receive reports, and project scope is active projects.
            }
            DailyDigestStats s = e.getValue();
            if (s.emptyYesterday()) {
                skipped++;
                continue;
            }
            scanned++;
            String projectName = projectName(projectId);
            String title = "每日日报：" + projectName + "（" + day + "）";
            String content = buildContent(day, s);
            int[] r = deliverToRecipients(projectId, day, title, content);
            sent += r[0];
            deduped += r[1];
            prefFiltered += r[2];
        }
        // Active projects with no aggregated rows are also skipped so that empty reports are not sent.
        skipped += Math.max(activeIds.size() - scanned - skipped, 0);
        return new DailyDigestResult(scanned, skipped, sent, deduped, prefFiltered);
    }

    /** Aggregate four elements for all projects by merging seven GROUP BY queries into a mutable Map and treating missing entries as 0. */
    private Map<Long, DailyDigestStats> collectStats(LocalDateTime from, LocalDateTime to) {
        // projectId -> [newCases, execPassed, execFailed, newDefects, openDefects, reviewBacklog, newLinks]
        Map<Long, int[]> acc = new HashMap<>();
        accumulate(acc, "SELECT project_id, count(*) FROM tp_test_case"
                        + " WHERE deleted = 0 AND created_at >= ? AND created_at < ? GROUP BY project_id",
                from, to, 0);
        accumulateExec(acc, from, to);
        accumulate(acc, "SELECT project_id, count(*) FROM tp_defect"
                        + " WHERE created_at >= ? AND created_at < ? GROUP BY project_id",
                from, to, 3);
        accumulate(acc, "SELECT project_id, count(*) FROM tp_defect WHERE status <> " + DEFECT_CLOSED
                + " GROUP BY project_id", null, null, 4);
        accumulate(acc, "SELECT project_id, count(*) FROM tp_test_case"
                        + " WHERE deleted = 0 AND status IN (1, 2) AND assignee_id IS NOT NULL"
                        + " AND assigned_at IS NOT NULL GROUP BY project_id", null, null, 5);
        accumulate(acc, "SELECT c.project_id, count(*) FROM tp_case_requirement cr"
                        + " JOIN tp_test_case c ON c.id = cr.case_id AND c.deleted = 0 AND c.status <> 9"
                        + " WHERE cr.created_at >= ? AND cr.created_at < ? GROUP BY c.project_id",
                from, to, 6);
        Map<Long, DailyDigestStats> result = new HashMap<>();
        acc.forEach((pid, v) -> result.put(pid,
                new DailyDigestStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6])));
        return result;
    }

    /** Execution result aggregation counts status 3 as success and status 4 or 5 as failure, including status IN (4,5) as failure, using a single CASE sum query. */
    private void accumulateExec(Map<Long, int[]> acc, LocalDateTime from, LocalDateTime to) {
        jdbcTemplate.query("""
                SELECT project_id,
                       SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END) AS ok_cnt,
                       SUM(CASE WHEN status IN (4, 5) THEN 1 ELSE 0 END) AS bad_cnt
                  FROM tp_exec_task
                 WHERE finished_at >= ? AND finished_at < ? AND status IN (3, 4, 5)
                 GROUP BY project_id""",
                rs -> {
                    while (rs.next()) { // As a lesson, ResultSetExtractor must advance the cursor itself.
                        int[] v = acc.computeIfAbsent(rs.getLong(1), k -> new int[7]);
                        v[1] = rs.getInt(2);
                        v[2] = rs.getInt(3);
                    }
                    return null;
                }, from, to);
    }

    /** General two-column aggregation of project_id and count accumulates into the specified slot. */
    private void accumulate(Map<Long, int[]> acc, String sql,
                            LocalDateTime from, LocalDateTime to, int slot) {
        Object[] args = from == null ? new Object[0] : new Object[] {from, to};
        jdbcTemplate.query(sql, rs -> {
            while (rs.next()) { // As a lesson, ResultSetExtractor must advance the cursor itself.
                acc.computeIfAbsent(rs.getLong(1), k -> new int[7])[slot] = rs.getInt(2);
            }
            return null;
        }, args);
    }

    private String projectName(long projectId) {
        return jdbcTemplate.query("SELECT name FROM tp_project WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : ("项目" + projectId), projectId);
    }

    /** Summary content is in plain Chinese and includes complete four elements without truncation, with one-sentence summaries separated by element. */
    static String buildContent(String day, DailyDigestStats s) {
        return "昨日（" + day + "）测试日报：新增用例 " + s.newCases()
                + " 条；执行结果：成功 " + s.execPassed() + "、失败 " + s.execFailed()
                + "；新增缺陷 " + s.newDefects() + "、未闭环缺陷 " + s.openDefects()
                + "；待评审积压 " + s.reviewBacklog()
                + "；覆盖率变化：新增覆盖关联 " + s.newLinks() + " 条。";
    }

 /** Send per recipient with keys including recipient to prevent mutual suppression, using the same approach as escalate, and return [sent, deduped, prefFiltered]. */
    private int[] deliverToRecipients(long projectId, String day, String title, String content) {
        int sent = 0;
        int deduped = 0;
        int prefFiltered = 0;
        for (Long userId : projectOwners.resolveWithCreator(projectId)) {
            String dedupKey = "daily-digest:" + projectId + ":" + day + ":" + userId;
            if (notificationService.existsByDedupKey(dedupKey)) {
                deduped++;
                continue;
            }
            long id = notificationService.create(new NotificationCreateInput(
                    userId, projectId, "DAILY_DIGEST", title, content, dedupKey));
            if (id == 0L) {
                prefFiltered++; // Preference single-point filtering applies to default_off unsubscribed or opted-out.
            } else {
                sent++;
            }
        }
        return new int[] {sent, deduped, prefFiltered};
    }
}
