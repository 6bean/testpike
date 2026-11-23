package com.sixbean.testpike.core.report.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.report.CaliberService;
import com.sixbean.testpike.common.report.PassRateSql;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.report.api.dto.AiQualityView;
import com.sixbean.testpike.core.report.api.dto.ChapterCoverage;
import com.sixbean.testpike.core.report.api.dto.CompareCoverage;
import com.sixbean.testpike.core.report.api.dto.CompareExecStat;
import com.sixbean.testpike.core.report.api.dto.CompareItemView;
import com.sixbean.testpike.core.report.api.dto.CoverageView;
import com.sixbean.testpike.core.report.api.dto.ExecTrendPoint;
import com.sixbean.testpike.core.report.api.dto.ExecutionStatView;
import com.sixbean.testpike.core.report.api.dto.ExportDownloadView;
import com.sixbean.testpike.core.report.api.dto.ExportResultView;
import com.sixbean.testpike.core.report.api.dto.ExportTaskView;
import com.sixbean.testpike.core.report.api.dto.FailDistItem;
import com.sixbean.testpike.core.report.api.dto.PersonalReviewView;
import com.sixbean.testpike.core.report.api.dto.PhaseCount;
import com.sixbean.testpike.core.report.api.dto.StatusCount;
import com.sixbean.testpike.core.report.api.dto.TeamMemberStatView;
import com.sixbean.testpike.core.report.domain.component.ExecStatSqlSupport;
import com.sixbean.testpike.core.report.domain.component.ExportArtifact;
import com.sixbean.testpike.core.report.domain.component.ExportTaskSupport;
import com.sixbean.testpike.core.report.domain.service.ReportService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


@Service
public class ReportServiceImpl implements ReportService {

 
    private static final int ASYNC_THRESHOLD = 5000;


    private static final Set<String> REPORT_TYPES = Set.of(
            "personal_review", "team_board", "ai_quality", "coverage", "execution_stat");

    private static final Map<Integer, String> FAIL_LABELS = Map.of(
            1, "产品缺陷", 2, "定位错误", 3, "环境问题", 4, "依赖失败");

    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{11}");

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final ArtifactStorage artifactStorage;
    private final ExportTaskSupport exportTaskSupport;
    private final ExecStatSqlSupport execStatSqlSupport;

    public ReportServiceImpl(JdbcTemplate jdbcTemplate,
                             NamedParameterJdbcTemplate namedJdbcTemplate,
                             ArtifactStorage artifactStorage,
                             ExportTaskSupport exportTaskSupport, ExecStatSqlSupport execStatSqlSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.artifactStorage = artifactStorage;
        this.exportTaskSupport = exportTaskSupport;
        this.execStatSqlSupport = execStatSqlSupport;
    }

    @Override
    public PersonalReviewView personalReview(long projectId, Long userId) {
        Long uid = userId == null ? null : userId;
        if (uid == null) {
            throw new ServiceException("评审人不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        long reviewed = countAction(projectId, uid, null);
        long pass = countAction(projectId, uid, "通过");
        long needChange = countAction(projectId, uid, "需修改");
        long reject = countAction(projectId, uid, "拒绝");
        Double avg = jdbcTemplate.queryForObject("""
                WITH per AS (
                    SELECT r.case_id, min(r.created_at) AS first_t, max(r.created_at) AS last_t
                    FROM tp_review_log r
                    JOIN tp_test_case c ON c.id = r.case_id AND c.project_id = ? AND c.deleted = 0
                    WHERE r.user_id = ? GROUP BY r.case_id
                )
                SELECT COALESCE(avg(EXTRACT(EPOCH FROM (last_t - first_t))), 0) FROM per
                """, Double.class, projectId, uid);
        return new PersonalReviewView(reviewed, pass, needChange, reject,
                avg == null ? 0.0 : Math.round(avg * 10) / 10.0);
    }

    private long countAction(long projectId, long userId, String action) {
        String sql = """
                SELECT count(DISTINCT r.case_id) FROM tp_review_log r
                JOIN tp_test_case c ON c.id = r.case_id AND c.project_id = ? AND c.deleted = 0
                WHERE r.user_id = ? %s
                """.formatted(action == null ? "" : "AND r.action = ?");
        Long n = action == null
                ? jdbcTemplate.queryForObject(sql, Long.class, projectId, userId)
                : jdbcTemplate.queryForObject(sql, Long.class, projectId, userId, action);
        return n == null ? 0 : n;
    }

    @Override
    public List<TeamMemberStatView> teamBoard(long projectId) {
        List<TeamMemberStatView> rows = jdbcTemplate.query("""
                SELECT r.user_id,
                       count(DISTINCT r.case_id),
                       count(DISTINCT CASE WHEN r.action = '通过' THEN r.case_id END),
                       count(DISTINCT CASE WHEN r.action = '需修改' THEN r.case_id END),
                       count(DISTINCT CASE WHEN r.action = '拒绝' THEN r.case_id END)
                FROM tp_review_log r
                JOIN tp_test_case c ON c.id = r.case_id AND c.project_id = ? AND c.deleted = 0
                GROUP BY r.user_id ORDER BY r.user_id
                """, (rs, i) -> {
            long uid = rs.getLong(1);
            return new TeamMemberStatView(uid, usernameOf(uid), null,
                    rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), 0);
        }, projectId);
        Map<Long, Long> backlog = new HashMap<>();
        jdbcTemplate.query("""
                SELECT assignee_id, count(*) FROM tp_test_case
                WHERE project_id = ? AND deleted = 0 AND assignee_id IS NOT NULL
                  AND status NOT IN (3, 5, 9) GROUP BY assignee_id
                """, rs -> {
            backlog.put(rs.getLong(1), rs.getLong(2));
        }, projectId);
        for (TeamMemberStatView row : rows) {
            Long b = backlog.get(row.userId());
            if (b != null) {
                rows.set(rows.indexOf(row),
                        new TeamMemberStatView(row.userId(), row.username(), row.nickName(),
                                row.reviewedCount(), row.passCount(), row.needChangeCount(),
                                row.rejectCount(), b));
            }
        }
        for (Map.Entry<Long, Long> e : backlog.entrySet()) {
            if (rows.stream().noneMatch(r -> r.userId() == e.getKey())) {
                rows.add(new TeamMemberStatView(e.getKey(), usernameOf(e.getKey()), null,
                        0, 0, 0, 0, e.getValue()));
            }
        }
        return rows;
    }

    private String usernameOf(long userId) {
        List<String> names = jdbcTemplate.query(
                "SELECT username FROM tp_user WHERE id = ?",
                (rs, i) -> rs.getString(1), userId);
        return names.isEmpty() ? null : names.get(0);
    }

    @Override
    public AiQualityView aiQuality(long projectId) {
        String aiCases = """
                SELECT id FROM tp_test_case WHERE project_id = ? AND deleted = 0 AND source = 1
                """;
        Long total = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT r.case_id) FROM tp_review_log r
                JOIN tp_test_case c ON c.id = r.case_id AND c.project_id = ? AND c.deleted = 0 AND c.source = 1
                """, Long.class, projectId);
        Long firstPass = jdbcTemplate.queryForObject("""
                WITH first_action AS (
                    SELECT DISTINCT ON (case_id) case_id, action
                    FROM tp_review_log WHERE case_id IN (%s)
                    ORDER BY case_id, created_at
                ) SELECT count(*) FROM first_action WHERE action = '通过'
                """.formatted(aiCases), Long.class, projectId);
        Long modify = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT r.case_id) FROM tp_review_log r
                JOIN tp_test_case c ON c.id = r.case_id AND c.project_id = ? AND c.deleted = 0 AND c.source = 1
                WHERE r.action = '需修改'
                """, Long.class, projectId);
        Long reject = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_test_case WHERE project_id = ? AND deleted = 0 AND source = 1 AND status = 5
                """, Long.class, projectId);
        long totalL = total == null ? 0 : total;
        long firstPassL = firstPass == null ? 0 : firstPass;
        long modifyL = modify == null ? 0 : modify;
        long rejectL = reject == null ? 0 : reject;
        List<StatusCount> dist = jdbcTemplate.query("""
                SELECT status, count(*) FROM tp_test_case
                WHERE project_id = ? AND deleted = 0 AND source = 1 GROUP BY status ORDER BY status
                """, (rs, i) -> new StatusCount(rs.getInt(1), statusLabel(rs.getInt(1)), rs.getLong(2)),
                projectId);
        Double acceptRate = queryCaliberScalar("ai-accept-rate", projectId);
        double firstPassRateL = acceptRate == null ? 0.0 : Math.round(acceptRate * 1000) / 1000.0;
        return new AiQualityView(totalL, firstPassL, firstPassRateL,
                modifyL, rate(modifyL, totalL), rejectL, rate(rejectL, totalL), dist);
    }

    
    private Double queryCaliberScalar(String code, long projectId) {
        var def = CaliberService.require(code);
        Map<String, Object> params = new HashMap<>();
        for (String p : def.namedParams()) {
            params.put(p, "projectId".equals(p) ? projectId : null);
        }
        var query = CaliberService.resolve(code, params);
        return namedJdbcTemplate.query(query.sql(), query.params(),
                rs -> rs.next() ? rs.getDouble("value") : null);
    }

    private String statusLabel(int status) {
        return switch (status) {
            case 1 -> "待评审";
            case 2 -> "评审中";
            case 3 -> "已通过";
            case 4 -> "需修改";
            case 5 -> "已拒绝";
            case 6 -> "执行中";
            case 7 -> "执行失败";
            case 8 -> "回归失败";
            case 9 -> "已归档";
            default -> "未知";
        };
    }

 
    @Override
    public List<PhaseCount> phaseDist(long projectId) {
        return jdbcTemplate.query("""
                SELECT COALESCE(phase, 0) AS p, count(*) FROM tp_test_case
                WHERE project_id = ? AND deleted = 0 GROUP BY p ORDER BY p
                """, (rs, i) -> new PhaseCount(rs.getInt(1), phaseLabel(rs.getInt(1)), rs.getLong(2)),
                projectId);
    }

    private String phaseLabel(int phase) {
        return switch (phase) {
            case 1 -> "冒烟";
            case 2 -> "功能";
            case 3 -> "回归";
            case 4 -> "发布验证";
            default -> "未归属";
        };
    }

    private double rate(long part, long total) {
        return total == 0 ? 0.0 : Math.round(part * 1000.0 / total) / 1000.0;
    }

    
    private double rate0(java.math.BigDecimal value) {
        return value == null ? 0.0 : Math.round(value.doubleValue() * 1000) / 1000.0;
    }

    
    @Override
    public CoverageView coverage(long projectId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement WHERE project_id = ? AND status <> 3 AND deleted = 0",
                Long.class, projectId);
        Long covered = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT cr.requirement_id) FROM tp_case_requirement cr
                JOIN tp_requirement r ON r.id = cr.requirement_id AND r.project_id = ? AND r.status <> 3 AND r.deleted = 0
                JOIN tp_test_case c ON c.id = cr.case_id AND c.deleted = 0 AND c.status <> 9
                """, Long.class, projectId);
        long totalL = total == null ? 0 : total;
        long coveredL = covered == null ? 0 : covered;
        List<ChapterCoverage> byChapter = jdbcTemplate.query("""
                SELECT COALESCE(r.chapter, '未分组') AS chapter, count(*) AS total,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM tp_case_requirement cr
                           JOIN tp_test_case c ON c.id = cr.case_id
                           WHERE cr.requirement_id = r.id AND c.deleted = 0 AND c.status <> 9)) AS covered
                FROM tp_requirement r WHERE r.project_id = ? AND r.status <> 3 AND r.deleted = 0
                GROUP BY chapter ORDER BY chapter
                """, (rs, i) -> new ChapterCoverage(rs.getString(1), rs.getLong(2),
                rs.getLong(3), rate(rs.getLong(3), rs.getLong(2))), projectId);
        return new CoverageView(totalL, coveredL, rate(coveredL, totalL), byChapter);
    }

    @Override
    public ExecutionStatView executionStat(long projectId) {
        return executionStat(projectId, null);
    }

    @Override
    public ExecutionStatView executionStat(long projectId, Long planId) {
        String planJoin = planId != null ? "JOIN tp_exec_batch b ON t.batch_id = b.id AND b.plan_id = ?" : "";
        String where = " WHERE t.project_id = ? AND " + execStatSqlSupport.excludeTempBatch();
        Object[] params = planId != null ? new Object[]{planId, projectId} : new Object[]{projectId};
        List<ExecTrendPoint> trend = jdbcTemplate.query("""
                SELECT to_char(t.created_at, 'YYYY-MM-DD') AS d,
                       %1$s AS pass,
                       %2$s AS fail,
                       %3$s AS timeout,
                       %4$s AS rate
                FROM tp_exec_task t
                """.formatted(PassRateSql.PASS_COUNT, PassRateSql.FAIL_COUNT,
                        PassRateSql.TIMEOUT_COUNT, PassRateSql.RATE_EXPR)
                + planJoin + where + """
                GROUP BY d ORDER BY d
                """, (rs, i) -> new ExecTrendPoint(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rate0(rs.getBigDecimal(5))), params);
        List<FailDistItem> failDist = jdbcTemplate.query("""
                SELECT COALESCE(t.error_type, 0) AS et, count(*) FROM tp_exec_task t
                """ + planJoin + where + """
                 AND t.status = 4 GROUP BY et ORDER BY et
                """, (rs, i) -> new FailDistItem(rs.getInt(1),
                FAIL_LABELS.getOrDefault(rs.getInt(1), "未分类"), rs.getLong(2)), params);
        return new ExecutionStatView(trend, failDist);
    }

    @Override
    public List<CompareItemView> compare(List<Long> projectIds) {
        com.sixbean.testpike.common.util.CompareIdsValidator.validate(projectIds);
        List<CompareItemView> items = new ArrayList<>();
        for (Long pid : projectIds) {
            items.add(compareOne(pid));
        }
        return items;
    }

    private CompareItemView compareOne(long projectId) {
        if (projectId <= 0) {
            throw new ServiceException("项目 ID 非法: " + projectId, BizErrorCode.VALIDATION_ERROR);
        }
        List<Object[]> rows = jdbcTemplate.query("""
                SELECT name, status = 2 FROM tp_project WHERE id = ?
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getBoolean(2)}, projectId);
        if (rows.isEmpty()) {
            throw new ServiceException("项目不存在: " + projectId, BizErrorCode.NOT_FOUND);
        }
        String name = (String) rows.get(0)[0];
        boolean archived = (Boolean) rows.get(0)[1];
        CoverageView cov = coverage(projectId);
        AiQualityView ai = aiQuality(projectId);
        CompareExecStat exec = execAgg(projectId);
        Long flaky = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_test_case
                WHERE project_id = ? AND deleted = 0 AND flaky = 1
                """, Long.class, projectId);
        return new CompareItemView(projectId, name, archived,
                new CompareCoverage(cov.coverageRate(), cov.coveredRequirements(), cov.totalRequirements()),
                ai, exec, flaky == null ? 0 : flaky);
    }

 
    private CompareExecStat execAgg(long projectId) {
        List<long[]> counts = jdbcTemplate.query("""
                SELECT count(*) FILTER (WHERE t.status = 3),
                       count(*) FILTER (WHERE t.status = 4),
                       count(*) FILTER (WHERE t.status = 6)
                FROM tp_exec_task t
                WHERE t.project_id = ? AND """ + execStatSqlSupport.excludeTempBatch(),
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, projectId);
        long[] c = counts.isEmpty() ? new long[]{0, 0, 0} : counts.get(0);
        return new CompareExecStat(c[0], c[1], c[2]);
    }

    @Override
    @Audit(objectType = "report", action = "export", objectId = "#projectId", after = "#result")
    public ExportResultView export(long operatorId, long projectId, String reportType, boolean masked) {
        String type = normalizeType(reportType);
        ensureProject(projectId);
        List<List<String>> rows = buildRows(operatorId, projectId, type, masked);
        int dataRows = rows.size() - 1;
        if (dataRows > ASYNC_THRESHOLD) {
            long taskId = createExportTask(operatorId, projectId, type, masked, dataRows);
            doAsyncExport(taskId, operatorId, projectId, type, masked);
            return new ExportResultView(true, taskId, null, null);
        }
        return new ExportResultView(false, 0, fileName(type), toExcel(type, rows));
    }

    @Override
    public List<ExportTaskView> listExportTasks(long projectId) {
        return jdbcTemplate.query("""
                SELECT id, report_type, masked, status, file_key, row_count, error_detail,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'),
                       to_char(finished_at, 'YYYY-MM-DD HH24:MI:SS')
                FROM tp_export_task WHERE project_id = ? ORDER BY id DESC
                """, (rs, i) -> new ExportTaskView(rs.getLong(1), rs.getString(2), rs.getInt(3),
                rs.getInt(4), rs.getString(5), rs.getInt(6) == 0 && rs.wasNull() ? null : rs.getInt(6),
                rs.getString(7), rs.getString(8), rs.getString(9)), projectId);
    }

    @Override
    public ExportDownloadView exportTaskDownload(long projectId, long taskId) {
        List<Object[]> rows = jdbcTemplate.query("""
                SELECT id, report_type, status, file_key FROM tp_export_task
                WHERE id = ? AND project_id = ?
                """, (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4)},
                taskId, projectId);
        if (rows.isEmpty()) {
            throw new ServiceException("导出任务不存在", BizErrorCode.NOT_FOUND);
        }
        Object[] row = rows.get(0);
        int status = (Integer) row[2];
        if (status != 2) {
            throw new ServiceException(status == 3 ? "导出任务失败，无法下载"
                    : "导出任务尚未完成，请稍后下载", BizErrorCode.VALIDATION_ERROR);
        }
        String fileKey = (String) row[3];
        String fileName = fileKey.substring(fileKey.lastIndexOf('/') + 1);
        return new ExportDownloadView((Long) row[0], fileName,
                artifactStorage.grantViewUrl(fileKey), status);
    }


    private String normalizeType(String reportType) {
        String t = reportType == null ? "" : reportType.trim();
        if (!REPORT_TYPES.contains(t)) {
            throw new ServiceException(
                    "报表类型仅支持 personal_review/team_board/ai_quality/coverage/execution_stat，实际: " + t,
                    BizErrorCode.VALIDATION_ERROR);
        }
        return t;
    }

    private void ensureProject(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE id = ?", Integer.class, projectId);
        if (n == null || n == 0) {
            throw new ServiceException("项目不存在", BizErrorCode.NOT_FOUND);
        }
    }

    
    private List<List<String>> buildRows(long operatorId, long projectId, String reportType, boolean masked) {
        return switch (reportType) {
            case "personal_review" -> {
                PersonalReviewView v = personalReview(projectId, operatorId);
                yield List.of(List.of("指标", "数值"),
                        List.of("已评审用例数", String.valueOf(v.reviewedCount())),
                        List.of("通过数", String.valueOf(v.passCount())),
                        List.of("需修改数", String.valueOf(v.needChangeCount())),
                        List.of("拒绝数", String.valueOf(v.rejectCount())),
                        List.of("平均评审时长（秒）", String.valueOf(v.avgReviewSeconds())));
            }
            case "team_board" -> {
                List<List<String>> rows = new ArrayList<>(List.of(
                        List.of("用户ID", "账号", "昵称", "已评审", "通过", "需修改", "拒绝", "积压")));
                for (TeamMemberStatView m : teamBoard(projectId)) {
                    rows.add(List.of(String.valueOf(m.userId()),
                            masked ? DesensitizeUtils.maskAccount(m.username() == null ? "" : m.username()) : nvl(m.username()),
                            masked ? DesensitizeUtils.maskAccount(nvl(m.nickName())) : nvl(m.nickName()),
                            String.valueOf(m.reviewedCount()), String.valueOf(m.passCount()),
                            String.valueOf(m.needChangeCount()), String.valueOf(m.rejectCount()),
                            String.valueOf(m.backlogCount())));
                }
                yield rows;
            }
            case "ai_quality" -> {
                AiQualityView v = aiQuality(projectId);
                List<List<String>> rows = new ArrayList<>(List.of(List.of("指标", "数值"),
                        List.of("评审用例总数", String.valueOf(v.totalReviewed())),
                        List.of("一次通过数", String.valueOf(v.firstPassCount())),
                        List.of("一次通过率", String.valueOf(v.firstPassRate())),
                        List.of("修改数", String.valueOf(v.modifyCount())),
                        List.of("修改率", String.valueOf(v.modifyRate())),
                        List.of("拒绝数", String.valueOf(v.rejectCount())),
                        List.of("拒绝率", String.valueOf(v.rejectRate()))));
                for (StatusCount s : v.statusDist()) {
                    rows.add(List.of("状态-" + s.label(), String.valueOf(s.count())));
                }
                yield rows;
            }
            case "coverage" -> {
                List<List<String>> rows = new ArrayList<>(List.of(List.of("章节", "需求点总数", "已覆盖", "覆盖率")));
                for (ChapterCoverage c : coverage(projectId).byChapter()) {
                    rows.add(List.of(c.chapter(), String.valueOf(c.total()), String.valueOf(c.covered()),
                            String.valueOf(c.rate())));
                }
                yield rows;
            }
            case "execution_stat" -> {
                List<List<String>> rows = new ArrayList<>(List.of(
                        List.of("任务ID", "用例ID", "日期", "结果", "失败分类", "错误详情")));
                jdbcTemplate.query("""
                        SELECT id, case_id, to_char(created_at, 'YYYY-MM-DD'),
                               CASE status WHEN 3 THEN '通过' WHEN 4 THEN '失败'
                                           WHEN 5 THEN '超时' WHEN 6 THEN '跳过' ELSE '执行中' END,
                               COALESCE(error_type, 0), error_detail
                        FROM tp_exec_task WHERE project_id = ? ORDER BY id
                        """, rs -> {
                    int et = rs.getInt(5);
                    String detail = rs.getString(6);
                    rows.add(List.of(String.valueOf(rs.getLong(1)), String.valueOf(rs.getLong(2)),
                            rs.getString(3), rs.getString(4), FAIL_LABELS.getOrDefault(et, "未分类"),
                            masked ? maskText(nvl(detail)) : nvl(detail)));
                }, projectId);
                yield rows;
            }
            default -> throw new IllegalStateException("unreachable: " + reportType);
        };
    }

    
    private String maskText(String text) {
        return PHONE_PATTERN.matcher(text).replaceAll(m -> DesensitizeUtils.maskPhone(m.group()));
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private byte[] toExcel(String reportType, List<List<String>> rows) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            com.alibaba.excel.EasyExcel.write(out).sheet(reportType).doWrite(rows);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ServiceException("Excel 生成失败: " + e.getMessage(), BizErrorCode.INTERNAL_ERROR);
        }
    }

    private String fileName(String reportType) {
        return reportType + "_" + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date()) + ".xlsx";
    }

    long createExportTask(long operatorId, long projectId, String reportType, boolean masked, int rowCount) {
        return exportTaskSupport.createTask(operatorId, projectId, reportType, masked, rowCount);
    }

 
    private void doAsyncExport(long taskId, long operatorId, long projectId, String reportType, boolean masked) {
        exportTaskSupport.startAsync(taskId, operatorId, projectId, reportType,
                "报表导出完成",
                "报表 " + reportType + " 导出完成（%d 条），请到统计报表页「导出任务」下载。",
                () -> {
                    List<List<String>> rows = buildRows(operatorId, projectId, reportType, masked);
                    return new ExportArtifact(toExcel(reportType, rows), rows.size() - 1);
                });
    }
}
