package com.sixbean.testpike.core.testreport.domain.service.impl;

import com.alibaba.excel.EasyExcel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.common.domain.component.PdfHtmlRenderer;
import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.testreport.api.dto.ReportDetailView;
import com.sixbean.testpike.core.testreport.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.testreport.api.dto.ReportQuery;
import com.sixbean.testpike.core.testreport.api.dto.ReportRecordInfo;
import com.sixbean.testpike.core.testreport.domain.component.TestReportRefusals;
import com.sixbean.testpike.core.testreport.domain.service.TestReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class TestReportServiceImpl implements TestReportService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TestReportServiceImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    @Audit(objectType = "test_report", action = "report_generate", objectId = "#result.id", after = "#result")
    public ReportRecordInfo generate(long operatorId, ReportGenerateInput input) {
        if (input.rangeType() != 1 && input.rangeType() != 2) {
            throw new ServiceException(TestReportRefusals.rangeTypeInvalid(input.rangeType()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.rangeType() == 1) {
            if (input.planId() == null) {
                throw new ServiceException(TestReportRefusals.planRequired(), BizErrorCode.VALIDATION_ERROR);
            }
            List<Long> owner = jdbcTemplate.queryForList(
                    "SELECT project_id FROM tp_exec_plan WHERE id = ?", Long.class, input.planId());
            if (owner.isEmpty()) {
                throw new ServiceException(TestReportRefusals.planNotFound(input.planId()), BizErrorCode.NOT_FOUND);
            }
            if (owner.get(0) != input.projectId()) {
                throw new ServiceException(TestReportRefusals.planOtherProject(input.planId(), input.projectId()),
                        BizErrorCode.VALIDATION_ERROR);
            }
        } else {
            if (input.startTime() == null || input.endTime() == null
                    || input.startTime().isBlank() || input.endTime().isBlank()) {
                throw new ServiceException(
                        TestReportRefusals.timeRangeRequired(input.startTime(), input.endTime()),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }

        ReportDetailView detail = buildAggregation(input);
        String title = input.title() == null || input.title().isBlank()
                ? defaultTitle(input, detail) : input.title().trim();
        String rangeLabel = detail.rangeLabel();
        String json;
        try {
            json = objectMapper.writeValueAsString(toReportJson(detail));
        } catch (Exception e) {
            throw new ServiceException(
                    TestReportRefusals.serializeFailed(title, String.valueOf(e.getMessage())),
                    BizErrorCode.INTERNAL_ERROR, e);
        }
        long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_report (project_id, plan_id, title, range_type, range_label,
                                            report_json, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?) RETURNING id
                """, Long.class, input.projectId(), input.rangeType() == 1 ? input.planId() : null,
                title, input.rangeType(), rangeLabel, json, operatorId);
        return toRecordInfo(id);
    }

    
    private org.springframework.jdbc.core.RowMapper<ReportRecordInfo> recordRowMapper() {
        return (rs, rowNum) -> new ReportRecordInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getObject("plan_id") == null ? null : rs.getLong("plan_id"),
                rs.getString("plan_name"), rs.getString("title"), rs.getInt("range_type"),
                rs.getString("range_label"), rs.getString("created_by_name"),
                rs.getString("created_at"));
    }

    
    private ReportRecordInfo toRecordInfo(long id) {
        List<ReportRecordInfo> rows = jdbcTemplate.query("""
                SELECT r.id, r.project_id, r.plan_id, p.name AS plan_name, r.title, r.range_type,
                       r.range_label, r.created_by, COALESCE(u.display_name, '') AS created_by_name,
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_test_report r
                LEFT JOIN tp_exec_plan p ON p.id = r.plan_id
                LEFT JOIN tp_user u ON u.id = r.created_by
                WHERE r.id = ?
                """, recordRowMapper(), id);
        if (rows.isEmpty()) {
            throw new ServiceException(TestReportRefusals.reportNotFound(id), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public PageResult<ReportRecordInfo> list(ReportQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size() == 0 ? 20 : query.size()));
        StringBuilder where = new StringBuilder(" WHERE r.project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.projectId());
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND r.title ILIKE ?");
            args.add("%" + query.keyword().trim() + "%");
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_report r" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<ReportRecordInfo> rows = jdbcTemplate.query("""
                SELECT r.id, r.project_id, r.plan_id, p.name AS plan_name, r.title, r.range_type,
                       r.range_label, r.created_by, COALESCE(u.display_name, '') AS created_by_name,
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_test_report r
                LEFT JOIN tp_exec_plan p ON p.id = r.plan_id
                LEFT JOIN tp_user u ON u.id = r.created_by
                %s%s LIMIT ? OFFSET ?
                """.formatted(where, orderClause(query.sortBy(), query.sortDir())),
                recordRowMapper(),
                args.toArray());
        return new PageResult<>(rows, total);
    }

    /**
     * Sort whitelist for the test-summary ledger .
     * The list query joins tp_exec_plan and tp_user, so bare column names would
     * be ambiguous — values carry the row qualifier where a physical column is
     * ranked and the select alias for the joined display name. plan_id and
     * plan_name stay out (a nullable foreign key is not what the reader sees);
     * report_json stays out (an aggregation blob is not a ranking dimension).
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "r.id",
            "title", "r.title",
            "range_type", "r.range_type",
            "created_by_name", "created_by_name",
            "created_at", "created_at");

    /**
     * ORDER BY builder for the project ledger window: a null/blank key keeps
     * the historical r.id DESC default; a whitelisted key orders by that column
     * with the r.id DESC tie-break pinned (reports generated in the same second
     * share created_at — deterministic paging requires the tie-break); an
     * off-list key is refused fail-closed before any read.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "总结报告");
        if (column == null) {
            return " ORDER BY r.id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", r.id DESC";
    }

    @Override
    public ReportDetailView get(long id) {
        List<ReportDetailView> rows = jdbcTemplate.query("""
                SELECT r.*, p.name AS plan_name, COALESCE(u.display_name, '') AS created_by_name,
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_test_report r
                LEFT JOIN tp_exec_plan p ON p.id = r.plan_id
                LEFT JOIN tp_user u ON u.id = r.created_by
                WHERE r.id = ?
                """, (rs, rowNum) -> fromReportJson(id, rs.getLong("project_id"),
                        rs.getObject("plan_id") == null ? null : rs.getLong("plan_id"),
                        rs.getString("plan_name"), rs.getString("title"), rs.getInt("range_type"),
                        rs.getString("range_label"), rs.getString("created_by_name"),
                        rs.getString("created_at"), rs.getString("report_json")), id);
        if (rows.isEmpty()) {
            throw new ServiceException(TestReportRefusals.reportNotFound(id), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public byte[] export(long id, String format) {
        ReportDetailView detail = get(id);
        return switch (format == null ? "" : format.toLowerCase()) {
            case "xlsx" -> buildXlsx(detail);
            case "pdf" -> buildPdf(detail);
            default -> throw new ServiceException(TestReportRefusals.exportFormatUnsupported(format),
                    BizErrorCode.VALIDATION_ERROR);
        };
    }


 
    private ReportDetailView buildAggregation(ReportGenerateInput input) {
        String planName;
        String rangeLabel;
        String targetVersion;
        int planCount;
        String planIdSql;
        List<Object> scopeArgs = new ArrayList<>();
        if (input.rangeType() == 1) {
            planName = jdbcTemplate.queryForObject(
                    "SELECT name FROM tp_exec_plan WHERE id = ?", String.class, input.planId());
            targetVersion = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(target_version, '') FROM tp_exec_plan WHERE id = ?", String.class, input.planId());
            planCount = 1;
            rangeLabel = "计划：" + planName + "（" + DAY.format(LocalDate.now()) + " 生成）";
            planIdSql = " plan_id = ? ";
            scopeArgs.add(input.planId());
        } else {
            String start = input.startTime().trim();
            String end = input.endTime().trim();
            planName = null;
            targetVersion = "";
            rangeLabel = "时间范围：" + start + " ~ " + end;
            planIdSql = " p.project_id = ? AND p.status <> 5 AND COALESCE(p.plan_start, '0001-01-01') <= ? "
                    + "AND COALESCE(p.plan_end, '9999-12-31') >= ? ";
            scopeArgs.add(input.projectId());
            scopeArgs.add(start);
            scopeArgs.add(end);
        }

        int caseTotal = input.rangeType() == 1
                ? jdbcTemplate.queryForObject("SELECT count(*) FROM tp_plan_case WHERE " + planIdSql,
                Integer.class, scopeArgs.toArray())
                : jdbcTemplate.queryForObject("SELECT count(*) FROM tp_plan_case pc "
                        + "JOIN tp_exec_plan p ON p.id = pc.plan_id WHERE " + planIdSql,
                Integer.class, scopeArgs.toArray());
        int planCnt = input.rangeType() == 1 ? 1
                : jdbcTemplate.queryForObject("SELECT count(*) FROM tp_exec_plan p WHERE " + planIdSql,
                Integer.class, scopeArgs.toArray());

        String statusSql = input.rangeType() == 1
                ? "SELECT status, count(*) FROM tp_plan_case WHERE " + planIdSql + " GROUP BY status"
                : "SELECT pc.status, count(*) FROM tp_plan_case pc JOIN tp_exec_plan p ON p.id = pc.plan_id "
                        + "WHERE " + planIdSql + " GROUP BY pc.status";
        Map<Integer, Integer> statusCount = new LinkedHashMap<>();
        jdbcTemplate.query(statusSql, (RowCallbackHandler) rs -> statusCount.put(rs.getInt(1), rs.getInt(2)),
                scopeArgs.toArray());
        int total = statusCount.values().stream().mapToInt(Integer::intValue).sum();
        int notRun = statusCount.getOrDefault(1, 0);
        int running = statusCount.getOrDefault(2, 0);
        int passed = statusCount.getOrDefault(3, 0);
        int failed = statusCount.getOrDefault(4, 0);
        int skipped = statusCount.getOrDefault(5, 0);
        String unstableSql = input.rangeType() == 1
                ? "SELECT count(*) FROM tp_plan_case pc JOIN tp_test_case c ON c.id = pc.case_id "
                        + "WHERE " + planIdSql + " AND c.flaky = 1 AND c.deleted = 0"
                : "SELECT count(*) FROM tp_plan_case pc JOIN tp_exec_plan p ON p.id = pc.plan_id "
                        + "JOIN tp_test_case c ON c.id = pc.case_id "
                        + "WHERE " + planIdSql + " AND c.flaky = 1 AND c.deleted = 0";
        int unstableSkipped = jdbcTemplate.queryForObject(unstableSql, Integer.class, scopeArgs.toArray());
        double passRate = passed + failed == 0 ? 0.0
                : BigDecimal.valueOf(passed * 100.0 / (passed + failed)).setScale(1, RoundingMode.HALF_UP).doubleValue();

        String defectWhere = input.rangeType() == 1 ? " plan_id = ? " : " project_id = ? AND created_at >= ?::timestamp "
                + "AND created_at < (?::timestamp + interval '1 day') ";
        List<Object> defectArgs = new ArrayList<>();
        if (input.rangeType() == 1) {
            defectArgs.add(input.planId());
        } else {
            defectArgs.add(input.projectId());
            defectArgs.add(input.startTime().trim());
            defectArgs.add(input.endTime().trim());
        }
        int defectTotal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_defect WHERE " + defectWhere, Integer.class, defectArgs.toArray());
        int openCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_defect WHERE " + defectWhere + " AND status IN (1, 2, 3, 4, 6)",
                Integer.class, defectArgs.toArray());
        List<ReportDetailView.SeverityCount> bySeverity = new ArrayList<>();
        Map<Integer, Integer> sevCount = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT severity, count(*) FROM tp_defect WHERE " + defectWhere + " GROUP BY severity",
                (RowCallbackHandler) rs -> sevCount.put(rs.getInt(1), rs.getInt(2)), defectArgs.toArray());
        for (int s = 1; s <= 4; s++) {
            bySeverity.add(new ReportDetailView.SeverityCount(DefectLabels.severityLabel(s), sevCount.getOrDefault(s, 0)));
        }
        List<ReportDetailView.StatusCount> byStatus = new ArrayList<>();
        Map<Integer, Integer> stCount = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT status, count(*) FROM tp_defect WHERE " + defectWhere + " GROUP BY status",
                (RowCallbackHandler) rs -> stCount.put(rs.getInt(1), rs.getInt(2)), defectArgs.toArray());
        for (int s = 1; s <= 6; s++) {
            byStatus.add(new ReportDetailView.StatusCount(DefectLabels.statusLabel(s), stCount.getOrDefault(s, 0)));
        }

        String envWhere = input.rangeType() == 1 ? " b.plan_id = ? " : " b.project_id = ? "
                + "AND b.finished_at >= ?::timestamp AND b.finished_at < (?::timestamp + interval '1 day') ";
        List<Object> envArgs = new ArrayList<>();
        if (input.rangeType() == 1) {
            envArgs.add(input.planId());
        } else {
            envArgs.add(input.projectId());
            envArgs.add(input.startTime().trim());
            envArgs.add(input.endTime().trim());
        }
        List<ReportDetailView.EnvVersion> envVersions = jdbcTemplate.query("""
                SELECT COALESCE(e.name, '未命名环境') AS env_name,
                       COALESCE(b.env_version_snapshot, '') AS version,
                       count(DISTINCT b.id) AS batch_count,
                       count(t.id) FILTER (WHERE t.status = 3) AS passed,
                       count(t.id) FILTER (WHERE t.status IN (4, 5)) AS failed
                FROM tp_exec_batch b
                LEFT JOIN tp_exec_task t ON t.batch_id = b.id
                LEFT JOIN tp_project_env e ON e.id = b.env_id
                WHERE %s
                GROUP BY e.name, b.env_version_snapshot
                ORDER BY env_name
                """.formatted(envWhere),
                (rs, rowNum) -> new ReportDetailView.EnvVersion(rs.getString("env_name"),
                        rs.getString("version"), rs.getInt("batch_count"),
                        rs.getInt("passed"), rs.getInt("failed")),
                envArgs.toArray());

        ReportDetailView.Conclusion conclusion = buildConclusion(passRate, total, failed, openCount);

        ReportDetailView.Scope scope = new ReportDetailView.Scope(planName, rangeLabel,
                targetVersion, caseTotal, planCnt);
        ReportDetailView.Results results = new ReportDetailView.Results(total, notRun, running,
                passed, failed, skipped, passRate, unstableSkipped);
        ReportDetailView.DefectDist defectDist = new ReportDetailView.DefectDist(defectTotal,
                openCount, bySeverity, byStatus);
        return new ReportDetailView(0, input.projectId(),
                input.rangeType() == 1 ? input.planId() : null, planName,
                input.title() == null || input.title().isBlank() ? "" : input.title().trim(),
                input.rangeType(), rangeLabel, scope, results, defectDist, envVersions,
                conclusion, "", "");
    }

    private ReportDetailView.Conclusion buildConclusion(double passRate, int total, int failed, int openCount) {
        if (total == 0) {
            return new ReportDetailView.Conclusion(false,
                    "范围内无执行用例，建议先执行测试（生成批次/回填结果）后再生成报告。");
        }
        StringBuilder summary = new StringBuilder();
        boolean pass = passRate >= 95.0 && openCount == 0;
        summary.append("通过率 ").append(trimRate(passRate)).append("%，")
                .append("共执行 ").append(total).append(" 条用例（失败 ").append(failed)
                .append(" 条，未关闭缺陷 ").append(openCount).append(" 个）。");
        if (pass) {
            summary.append("达到准出标准，建议：本轮测试可结束，缺陷回归通过后进入发布。");
        } else {
            List<String> reasons = new ArrayList<>();
            if (passRate < 95.0) {
                reasons.add("通过率未达 95% 目标");
            }
            if (openCount > 0) {
                reasons.add("存在未关闭缺陷");
            }
            summary.append("未达标（").append(String.join("、", reasons))
                    .append("），建议：优先修复失败用例并关闭缺陷，回归验证通过后重新生成报告。");
        }
        return new ReportDetailView.Conclusion(pass, summary.toString());
    }

    private String trimRate(double rate) {
        return BigDecimal.valueOf(rate).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String defaultTitle(ReportGenerateInput input, ReportDetailView detail) {
        String scope = input.rangeType() == 1 ? "计划" + detail.scope().planName() : "时间范围报告";
        return scope + " 总结报告";
    }


    private Map<String, Object> toReportJson(ReportDetailView d) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("scope", Map.of("planName", d.scope().planName() == null ? "" : d.scope().planName(),
                "rangeLabel", d.scope().rangeLabel(), "targetVersion", d.scope().targetVersion(),
                "caseTotal", d.scope().caseTotal(), "planCount", d.scope().planCount()));
        json.put("results", Map.of("total", d.results().total(), "notRun", d.results().notRun(),
                "running", d.results().running(), "passed", d.results().passed(),
                "failed", d.results().failed(), "skipped", d.results().skipped(),
                "passRate", d.results().passRate(), "unstableSkipped", d.results().unstableSkipped()));
        json.put("defectDist", Map.of("total", d.defectDist().total(), "openCount", d.defectDist().openCount(),
                "bySeverity", d.defectDist().bySeverity(), "byStatus", d.defectDist().byStatus()));
        json.put("envVersions", d.envVersions());
        json.put("conclusion", Map.of("pass", d.conclusion().pass(), "summary", d.conclusion().summary()));
        return json;
    }

    private ReportDetailView fromReportJson(long id, long projectId, Long planId, String planName,
                                            String title, int rangeType, String rangeLabel,
                                            String createdByName, String createdAt, String json) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {
            });
            Map<String, Object> scope = asMap(root.get("scope"));
            Map<String, Object> results = asMap(root.get("results"));
            Map<String, Object> defect = asMap(root.get("defectDist"));
            Map<String, Object> conclusion = asMap(root.get("conclusion"));
            List<ReportDetailView.EnvVersion> envs = objectMapper.convertValue(
                    root.getOrDefault("envVersions", List.of()),
                    new TypeReference<List<ReportDetailView.EnvVersion>>() {
                    });
            String pn = scope.get("planName") == null ? null : String.valueOf(scope.get("planName"));
            if (pn != null && pn.isBlank()) {
                pn = null;
            }
            return new ReportDetailView(id, projectId, planId, planName, title, rangeType, rangeLabel,
                    new ReportDetailView.Scope(pn, str(scope.get("rangeLabel")),
                            str(scope.get("targetVersion")), intOf(scope.get("caseTotal")), intOf(scope.get("planCount"))),
                    new ReportDetailView.Results(intOf(results.get("total")), intOf(results.get("notRun")),
                            intOf(results.get("running")), intOf(results.get("passed")),
                            intOf(results.get("failed")), intOf(results.get("skipped")),
                            doubleOf(results.get("passRate")), intOf(results.get("unstableSkipped"))),
                    new ReportDetailView.DefectDist(intOf(defect.get("total")), intOf(defect.get("openCount")),
                            objectMapper.convertValue(defect.getOrDefault("bySeverity", List.of()),
                                    new TypeReference<List<ReportDetailView.SeverityCount>>() {
                                    }),
                            objectMapper.convertValue(defect.getOrDefault("byStatus", List.of()),
                                    new TypeReference<List<ReportDetailView.StatusCount>>() {
                                    })),
                    envs,
                    new ReportDetailView.Conclusion(boolOf(conclusion.get("pass")), str(conclusion.get("summary"))),
                    createdByName, createdAt);
        } catch (Exception e) {
            throw new ServiceException(TestReportRefusals.dataParseFailed(String.valueOf(e.getMessage())),
                    BizErrorCode.INTERNAL_ERROR, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private double doubleOf(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private boolean boolOf(Object o) {
        return Boolean.TRUE.equals(o);
    }


    private byte[] buildXlsx(ReportDetailView d) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<List<String>> head = List.of(List.of("报告标题"), List.of("生成范围"), List.of("目标版本"),
                    List.of("用例总数"), List.of("通过"), List.of("失败"), List.of("未执行"), List.of("跳过"),
                    List.of("不稳定跳过"), List.of("通过率"), List.of("缺陷总数"), List.of("未关闭缺陷"),
                    List.of("结论"));
            List<List<Object>> rows = new ArrayList<>();
            rows.add(List.of(d.title(), d.rangeLabel(), d.scope().targetVersion(),
                    d.results().total(), d.results().passed(), d.results().failed(),
                    d.results().notRun(), d.results().skipped(), d.results().unstableSkipped(),
                    trimRate(d.results().passRate()) + "%",
                    d.defectDist().total(), d.defectDist().openCount(), d.conclusion().summary()));
            EasyExcel.write(out).head(head).sheet("报告概览").doWrite(rows);
            List<List<String>> envHead = List.of(List.of("环境"), List.of("版本"), List.of("批次"),
                    List.of("通过"), List.of("失败"));
            List<List<Object>> envRows = new ArrayList<>();
            for (ReportDetailView.EnvVersion e : d.envVersions()) {
                envRows.add(List.of(e.envName(), e.version(), e.batchCount(), e.passed(), e.failed()));
            }
            EasyExcel.write(out).head(envHead).sheet("环境版本").doWrite(envRows);
            List<List<String>> distHead = List.of(List.of("分类"), List.of("名称"), List.of("数量"));
            List<List<Object>> distRows = new ArrayList<>();
            for (ReportDetailView.SeverityCount s : d.defectDist().bySeverity()) {
                distRows.add(List.of("严重级别", "P" + s.label(), s.count()));
            }
            for (ReportDetailView.StatusCount s : d.defectDist().byStatus()) {
                distRows.add(List.of("状态", s.label(), s.count()));
            }
            EasyExcel.write(out).head(distHead).sheet("缺陷分布").doWrite(distRows);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ServiceException(TestReportRefusals.excelExportFailed(String.valueOf(e.getMessage())),
                    BizErrorCode.INTERNAL_ERROR, e);
        }
    }

    private byte[] buildPdf(ReportDetailView d) {
        String html = buildReportHtml(d);
        try {
            return PdfHtmlRenderer.render(html);
        } catch (Exception e) {
            throw new ServiceException(TestReportRefusals.pdfExportFailed(String.valueOf(e.getMessage())),
                    BizErrorCode.INTERNAL_ERROR, e);
        }
    }

    private String buildReportHtml(ReportDetailView d) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <html><head><meta charset="UTF-8"/><style>
                body { font-family: "Noto Sans SC", sans-serif; font-size: 12px; color: #1f2d3d; margin: 24px; }
                h1 { font-size: 20px; text-align: center; }
                h2 { font-size: 14px; border-left: 4px solid #3370ff; padding-left: 8px; margin-top: 20px; }
                table { border-collapse: collapse; width: 100%%; margin-top: 8px; }
                th, td { border: 1px solid #c0c4cc; padding: 6px 10px; text-align: left; }
                th { background: #eef2ff; }
                .meta { color: #606266; font-size: 11px; text-align: center; }
                .summary { background: #f5f7fa; padding: 10px; border-radius: 4px; }
                </style></head><body>
                <h1>%s</h1>
                <p class="meta">生成范围：%s ｜ 生成时间：%s ｜ 生成人：%s</p>
                <h2>一、范围</h2>
                <table><tr><th>计划</th><td>%s</td></tr>
                <tr><th>目标版本</th><td>%s</td></tr>
                <tr><th>用例总数</th><td>%s</td></tr>
                <tr><th>涉及计划数</th><td>%s</td></tr></table>
                <h2>二、执行结果</h2>
                <table><tr><th>总用例</th><th>未执行</th><th>执行中</th><th>通过</th><th>失败</th><th>跳过</th><th>不稳定跳过</th><th>通过率</th></tr>
                <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s%%</td></tr></table>
                <h2>三、缺陷分布</h2>
                """.formatted(esc(d.title()), esc(d.rangeLabel()), esc(d.createdAt()), esc(d.createdByName()),
                esc(d.scope().planName() == null || d.scope().planName().isBlank() ? "—" : d.scope().planName()),
                esc(d.scope().targetVersion() == null || d.scope().targetVersion().isBlank() ? "—" : d.scope().targetVersion()),
                d.scope().caseTotal(), d.scope().planCount(),
                d.results().total(), d.results().notRun(), d.results().running(), d.results().passed(),
                d.results().failed(), d.results().skipped(), d.results().unstableSkipped(),
                trimRate(d.results().passRate())));
        sb.append("<table><tr><th>严重级别</th>");
        for (ReportDetailView.SeverityCount s : d.defectDist().bySeverity()) {
            sb.append("<th>").append(s.label()).append("</th>");
        }
        sb.append("<th>合计</th><th>未关闭</th></tr><tr><td>数量</td>");
        for (ReportDetailView.SeverityCount s : d.defectDist().bySeverity()) {
            sb.append("<td>").append(s.count()).append("</td>");
        }
        sb.append("<td>").append(d.defectDist().total()).append("</td><td>")
                .append(d.defectDist().openCount()).append("</td></tr></table>");
        sb.append("<h2>四、环境版本</h2><table><tr><th>环境</th><th>版本</th><th>批次</th><th>通过</th><th>失败</th></tr>");
        if (d.envVersions().isEmpty()) {
            sb.append("<tr><td colspan=\"5\">—</td></tr>");
        }
        for (ReportDetailView.EnvVersion e : d.envVersions()) {
            sb.append("<tr><td>").append(esc(e.envName())).append("</td><td>").append(esc(e.version()))
                    .append("</td><td>").append(e.batchCount()).append("</td><td>").append(e.passed())
                    .append("</td><td>").append(e.failed()).append("</td></tr>");
        }
        sb.append("</table><h2>五、结论建议</h2><div class=\"summary\">").append(esc(d.conclusion().summary()))
                .append("</div></body></html>");
        return sb.toString();
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
