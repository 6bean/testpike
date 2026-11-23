package com.sixbean.testpike.core.reportcenter.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.CaliberService;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.program.domain.component.ProgramLensScope;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportExportFile;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportGenerateInput;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportListItem;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportQuery;
import com.sixbean.testpike.core.reportcenter.api.dto.ReportView;
import com.sixbean.testpike.core.reportcenter.domain.component.CaliberBlockRunner;
import com.sixbean.testpike.core.reportcenter.domain.component.ReportCsvWriter;
import com.sixbean.testpike.core.reportcenter.domain.component.ReportExportSupport;
import com.sixbean.testpike.core.reportcenter.domain.service.ReportCenterService;
import com.sixbean.testpike.core.reportcenter.infrastructure.dataobject.ReportRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class ReportCenterServiceImpl implements ReportCenterService {

    
    private static final List<String> SNAPSHOT_CODES = List.of(
            "pass-rate", "defect-density", "req-coverage", "ai-accept-rate",
            "exec-p95", "token-cost-window", "feedback-reject-type", "fix-adopt-rate");

    
    private static final List<String> DISTRIBUTION_CODES = List.of("feedback-reject-type");

    
    public static final long SYSTEM_OPERATOR_ID = 0L;

    
    static final String KEY_EXPORT_MAX_ROWS = "report.export-max-rows";

    
    private static final List<String> EXPORT_FORMATS = List.of("csv", "pdf");

    
    private static final List<String> EXPORT_THEMES = List.of("light", "dark");

    private static final RowMapper<ReportRow> REPORT_ROW = new BeanPropertyRowMapper<>(ReportRow.class);

    private final NamedParameterJdbcTemplate named;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final SystemConfigReader systemConfigReader;
    private final AuditLogService auditLogService;
    private final ProgramLensScope programLensScope;
    private final ProgramService programService;
    private final CaliberBlockRunner caliberBlockRunner;

    public ReportCenterServiceImpl(NamedParameterJdbcTemplate named,
                                   ProjectService projectService,
                                   ObjectMapper objectMapper,
                                   SystemConfigReader systemConfigReader,
                                   AuditLogService auditLogService,
                                   ProgramLensScope programLensScope,
                                   ProgramService programService,
                                   CaliberBlockRunner caliberBlockRunner) {
        this.named = named;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.systemConfigReader = systemConfigReader;
        this.auditLogService = auditLogService;
        this.programLensScope = programLensScope;
        this.programService = programService;
        this.caliberBlockRunner = caliberBlockRunner;
    }

    @Override
    @Audit(objectType = "report", action = "generate", objectId = "#result.id()", after = "#result")
    public ReportView generate(Long operatorId, ReportGenerateInput input) {
        long reportType = requireType(input.reportType(), "报表类型", 1, 2);
        long scopeType = requireType(input.scopeType(), "范围类型", 1, 3);
        long scopeId = normalizeScope(scopeType, input.scopeId());
        LocalDateTime start = parsePeriod(input.periodStart(), "期间起");
        LocalDateTime end = parsePeriod(input.periodEnd(), "期间止");
        validatePeriodShape(reportType, start, end);

        String payloadJson = buildPayload(reportType, scopeType, scopeId, start, end);
        Long operator = operatorId == null ? SYSTEM_OPERATOR_ID : operatorId;
        ReportRow row = named.queryForObject("""
                INSERT INTO tp_report (scope_type, scope_id, report_type, period_start,
                                       period_end, payload, generated_at, created_by)
                VALUES (:scopeType, :scopeId, :reportType, :start, :end,
                        :payload::jsonb, now(), :operator)
                ON CONFLICT (scope_type, scope_id, report_type, period_start)
                DO UPDATE SET payload = EXCLUDED.payload,
                              period_end = EXCLUDED.period_end,
                              generated_at = now(),
                              created_by = EXCLUDED.created_by,
                              updated_at = now()
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("scopeType", scopeType)
                .addValue("scopeId", scopeId)
                .addValue("reportType", reportType)
                .addValue("start", start)
                .addValue("end", end)
                .addValue("payload", payloadJson)
                .addValue("operator", operator), REPORT_ROW);
        return toView(row);
    }


    @Override
    public PageResult<ReportListItem> list(ReportQuery query, Long operatorId) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        // Program lens : optional narrowing resolved by the ProgramLensScope
        // single source — never re-implemented here. null = no lens (legacy view
        // untouched); empty set = honest empty page; non-empty = lens projects ∩
        // caller visibility. Global rows (scope_id=0) fall outside any lens set,
        // so one IN clause covers both segment rules without a scope_type predicate.
        java.util.Set<Long> lensProjects = query.programId() > 0
                ? programLensScope.visibleProjectIds(query.programId(), operatorId)
                : null;
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (lensProjects != null) {
            if (lensProjects.isEmpty()) {
                where.append(" AND 1 = 0");
            } else {
                where.append(" AND scope_id IN (:lensProjects)");
                params.addValue("lensProjects", lensProjects);
            }
        }
        if (query.reportType() != null) {
            requireType(query.reportType(), "报表类型", 1, 2);
            where.append(" AND report_type = :reportType");
            params.addValue("reportType", query.reportType());
        }
        if (query.scopeType() != null) {
            requireType(query.scopeType(), "范围类型", 1, 3);
            where.append(" AND scope_type = :scopeType");
            params.addValue("scopeType", query.scopeType());
        }
        if (query.scopeId() != null) {
            where.append(" AND scope_id = :scopeId");
            params.addValue("scopeId", query.scopeId());
        }
        if (query.periodFrom() != null && !query.periodFrom().isBlank()) {
            where.append(" AND period_start >= :periodFrom");
            params.addValue("periodFrom", parsePeriod(query.periodFrom(), "筛选期间起"));
        }
        if (query.periodTo() != null && !query.periodTo().isBlank()) {
            where.append(" AND period_start < :periodTo");
            params.addValue("periodTo", parsePeriod(query.periodTo(), "筛选期间止"));
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_report" + where, params, Long.class);
        params.addValue("size", safeSize)
                .addValue("offset", (long) (safePage - 1) * safeSize);
        List<ReportListItem> items = named.query("""
                SELECT id, scope_type, scope_id, report_type, period_start, period_end,
                       generated_at, created_by
                FROM tp_report""" + where + "\n" + orderClause(query.sortBy(), query.sortDir()) + """
 LIMIT :size OFFSET :offset
""", params, REPORT_ROW).stream().map(ReportCenterServiceImpl::toListItem).toList();
        return new PageResult<>(items, total != null ? total : 0L);
    }

    /**
 * Sort whitelist for the report snapshot ledger .
 * Values are the physical columns of tp_report — the list query is a
 * single-table read, so no qualifiers are needed. The set mirrors the
 * columns the page renders and ranks meaningfully; payload_json stays out
 * (a snapshot blob is not a ranking dimension) and created_by stays out
 * (0=system rows would cluster meaninglessly).
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "report_type", "report_type",
            "scope_type", "scope_type",
            "scope_id", "scope_id",
            "period_start", "period_start",
            "period_end", "period_end",
            "generated_at", "generated_at");

    /**
     * ORDER BY builder for the snapshot window: a null/blank key keeps the
     * historical period_start DESC, id DESC default; a whitelisted key orders
     * by that column with the id DESC tie-break pinned (equal period starts
     * are the normal case for weekly+monthly pairs — deterministic paging
     * requires the tie-break); an off-list key is refused fail-closed.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "报表快照");
        if (column == null) {
            return " ORDER BY period_start DESC, id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public ReportView detail(long id) {
        return toView(requireRow(id));
    }


    @Override
    public ReportExportFile export(Long operatorId, long id, String format, String theme) {
        String fmt = format == null ? "" : format.trim().toLowerCase();
        if (!EXPORT_FORMATS.contains(fmt)) {
            throw new ServiceException("导出格式仅支持 csv/pdf（当前: " + format + "）；请在「报表中心」点击「导出」时选择 csv 或 pdf",
                    BizErrorCode.VALIDATION_ERROR);
        }
        boolean dark = false;
        if ("pdf".equals(fmt)) {
            String t = theme == null || theme.isBlank() ? "light" : theme.trim().toLowerCase();
            if (!EXPORT_THEMES.contains(t)) {
                throw new ServiceException("PDF 主题仅支持 light/dark（当前: " + theme + "）；请改成 light 或 dark（「报表中心」导出面板的主题选项）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            dark = "dark".equals(t);
        }
        ReportRow row = requireRow(id);
        JsonNode payload = readPayload(row);
        List<List<Object>> scalars = scalarRows(payload);
        List<List<Object>> distRows = distributionRows(payload);
        long maxRows = systemConfigReader.getLong(KEY_EXPORT_MAX_ROWS, 100_000L);
        long rowCount = 3L + scalars.size() + (distRows.isEmpty() ? 0 : 3L + distRows.size());
        if (rowCount > maxRows) {
            throw new ServiceException("报表导出 " + rowCount + " 行超过上限 " + maxRows
                    + "（请在「报表中心」收窄「期间起」「期间止」或范围后重新导出；调高上限请管理员在「系统配置」修改参数 report.export-max-rows）",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String base = fileNameBase(row);
        ReportExportFile file;
        if ("csv".equals(fmt)) {
            List<Object> meta = metaFlat(row, payload);
            byte[] bytes = ReportCsvWriter.toCsv(
                    ReportExportSupport.csvRows(meta, scalars, distRows));
            file = new ReportExportFile(ReportExportSupport.csvFileName(base),
                    "text/csv;charset=UTF-8", bytes);
        } else {
            byte[] bytes = ReportExportSupport.toPdf(title(row, payload), metaMap(row, payload),
                    scalars, distributionsByCaliber(payload), dark);
            file = new ReportExportFile(ReportExportSupport.pdfFileName(base), "application/pdf", bytes);
        }
        recordExportAudit(operatorId, row, fmt, dark, file.fileName());
        return file;
    }


    private List<List<Object>> scalarRows(JsonNode payload) {
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode entry : caliberNodes(payload)) {
            if (entry.has("value")) {
                rows.add(List.of(entry.path("code").asText(), entry.path("display_name").asText(),
                        entry.path("version").asText(),
                        entry.get("value").isNull() ? "—" : entry.get("value").asText()));
            }
        }
        return rows;
    }

    private List<List<Object>> distributionRows(JsonNode payload) {
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode entry : caliberNodes(payload)) {
            for (JsonNode item : entry.path("rows")) {
                rows.add(List.of(entry.path("code").asText(), entry.path("display_name").asText(),
                        entry.path("version").asText(),
                        item.path("value").asText(), item.path("extra").asText()));
            }
        }
        return rows;
    }

    
    private Map<String, List<List<Object>>> distributionsByCaliber(JsonNode payload) {
        Map<String, List<List<Object>>> grouped = new LinkedHashMap<>();
        for (JsonNode entry : caliberNodes(payload)) {
            if (!entry.has("rows")) {
                continue;
            }
            List<List<Object>> rows = new ArrayList<>();
            for (JsonNode item : entry.path("rows")) {
                rows.add(List.of(item.path("value").asText(), item.path("extra").asText()));
            }
            if (!rows.isEmpty()) {
                grouped.put(entry.path("display_name").asText(), rows);
            }
        }
        return grouped;
    }

    
    private List<JsonNode> caliberNodes(JsonNode payload) {
        List<JsonNode> list = new ArrayList<>();
        JsonNode calibers = payload.path("calibers");
        calibers.fields().forEachRemaining(e -> {
            if (e.getValue() instanceof ObjectNode on) {
                on.put("code", e.getKey());
                list.add(on);
            }
        });
        return list;
    }

    private List<Object> metaFlat(ReportRow row, JsonNode payload) {
        return List.of(
                "报表类型", typeName(row.getReportType()),
                "范围", scopeLabel(row),
                "期间", row.getPeriodStart() + " ~ " + row.getPeriodEnd() + "（含起不含止）",
                "生成时间", String.valueOf(row.getGeneratedAt()),
                "口径注册表大小", String.valueOf(payload.path("caliber_registry_size").asText()));
    }

    private Map<String, String> metaMap(ReportRow row, JsonNode payload) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("报表类型", typeName(row.getReportType()));
        m.put("范围", scopeLabel(row));
        m.put("期间", row.getPeriodStart() + " ~ " + row.getPeriodEnd() + "（含起不含止）");
        m.put("生成时间", String.valueOf(row.getGeneratedAt()));
        m.put("口径注册表大小", payload.path("caliber_registry_size").asText());
        return m;
    }

    private String title(ReportRow row, JsonNode payload) {
        return typeName(row.getReportType()) + "（" + scopeLabel(row) + "）";
    }

    private static String typeName(Integer reportType) {
        return reportType != null && reportType == 2 ? "月报" : "周报";
    }

    private static String scopeLabel(ReportRow row) {
        return row.getScopeType() != null && row.getScopeType() == 2
                ? "全局" : "项目#" + row.getScopeId();
    }

    private String fileNameBase(ReportRow row) {
        return typeName(row.getReportType()) + "_" + row.getPeriodStart().toLocalDate()
                + "_" + row.getPeriodEnd().toLocalDate();
    }

    
    private void recordExportAudit(Long operatorId, ReportRow row, String fmt, boolean dark,
                                   String fileName) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("format", fmt);
        if ("pdf".equals(fmt)) {
            after.put("theme", dark ? "dark" : "light");
        }
        after.put("reportId", row.getId());
        after.put("scopeType", row.getScopeType());
        after.put("scopeId", row.getScopeId());
        after.put("fileName", fileName);
        auditLogService.record(new AuditLogInput(operatorId,
                row.getScopeType() != null && row.getScopeType() == 1 ? row.getScopeId() : null,
                "report", String.valueOf(row.getId()), "export", null, after));
    }


    
    private ReportRow requireRow(long id) {
        ReportRow row = named.query("""
                SELECT id, scope_type, scope_id, report_type, period_start, period_end,
                       payload::text AS payload, generated_at, created_by
                FROM tp_report WHERE id = :id
                """, new MapSqlParameterSource("id", id),
                rs -> rs.next() ? REPORT_ROW.mapRow(rs, 0) : null);
        if (row == null) {
            throw new ServiceException("报表快照不存在: " + id + "；请在「报表中心」刷新列表后选择有效快照（周报/月报由系统自动生成，过旧快照可能已清理）", BizErrorCode.NOT_FOUND);
        }
        return row;
    }

    private JsonNode readPayload(ReportRow row) {
        try {
            return objectMapper.readTree(row.getPayload());
        } catch (Exception e) {
            throw new ServiceException("报表 payload 解析失败（快照数据损坏？id=" + row.getId() + "）；请在「报表中心」对该期间重新「手动补生成」覆盖损坏快照",
                    BizErrorCode.INTERNAL_ERROR, e);
        }
    }

    
    private String buildPayload(long reportType, long scopeType, long scopeId,
                                LocalDateTime start, LocalDateTime end) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("report_type", reportType == 1 ? "weekly" : "monthly");
        root.putObject("scope")
                .put("type", switch ((int) scopeType) {
                    case 1 -> "project";
                    case 3 -> "program";
                    default -> "global";
                })
                .put("id", scopeId);
        root.putObject("period").put("start", start.toString()).put("end", end.toString());
        ObjectNode calibers = root.putObject("calibers");
        // Program dimension : one set fetch from ProgramService (single source),
        // handed to every scoped caliber as the conjunctive :projectIds parameter.
        String projectSetParam = scopeType == 3
                ? CaliberService.projectSetParam(programService.listProjectIds(scopeId))
                : null;
        for (String code : SNAPSHOT_CODES) {
            calibers.set(code, caliberBlock(code, scopeType, scopeId, projectSetParam, start, end));
        }
        root.put("caliber_registry_size", CaliberService.listAll().size());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ServiceException("报表 payload 序列化失败；请稍后在「报表中心」重新「手动补生成」，仍失败请联系管理员在「系统配置」核查口径注册表", BizErrorCode.INTERNAL_ERROR, e);
        }
    }

    
    private ObjectNode caliberBlock(String code, long scopeType, long scopeId,
                                    String projectSetParam,
                                    LocalDateTime start, LocalDateTime end) {
        Map<String, Object> params = new HashMap<>();
        for (String p : CaliberService.require(code).namedParams()) {
            params.put(p, switch (p) {
                // Program dimension: the single-project key stays NULL; the set key
                // carries the program's project ids (bigint[] literal, R40 helper).
                case "projectId" -> scopeType == 3 || scopeId == 0 ? null : scopeId;
                case "projectIds" -> projectSetParam;
                case "fromTime" -> start;
                case "toTime" -> end;
                default -> throw new ServiceException(
                        "报表快照不支持口径 " + code + " 的参数: " + p + "（注册表新增口径需同步快照装配）；请联系管理员在「系统配置」核查口径注册表后重新生成",
                        BizErrorCode.INTERNAL_ERROR);
            });
        }
        // Run + block assembly live in CaliberBlockRunner (single source shared with
        // the program overview endpoint — this pipeline must never exist twice).
        return DISTRIBUTION_CODES.contains(code)
                ? caliberBlockRunner.distributionBlock(code, params)
                : caliberBlockRunner.scalarBlock(code, params);
    }

    
    private static long requireType(Integer value, String label, long min, long max) {
        if (value == null || value < min || value > max) {
            throw new ServiceException(label + "非法: " + value + "（合法值 " + min + "~" + max + "）；请改成该范围内的取值后在「报表中心」重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return value;
    }

    
    private long normalizeScope(long scopeType, Long scopeId) {
        if (scopeType == 2) {
            return 0L;
        }
        if (scopeId == null) {
            throw new ServiceException(scopeType == 3
                    ? "项目集维报表必须提供 scopeId（项目集 id）；请在「报表中心」选择具体项目集，全局口径请改选「全局维」"
                    : "项目维报表必须提供 scopeId（项目 id）；请在「报表中心」选择具体项目，全局口径请改选「全局维」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (scopeType == 3) {
            // Program existence comes from ProgramService.get (single source, R40) —
            // unknown id surfaces as its 40401; no second existence query here.
            programService.get(scopeId);
            return scopeId;
        }
        if (!projectService.exists(scopeId)) {
            throw new ServiceException("项目不存在: " + scopeId + "；请在「报表中心」重新选择项目（项目可能已删除或当前账号无该项目权限）", BizErrorCode.NOT_FOUND);
        }
        return scopeId;
    }

    
    private static LocalDateTime parsePeriod(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("期间" + label + "缺失（格式：ISO 日期时间，示例 2026-09-07T00:00）；请在「报表中心」选择「期间起」「期间止」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("期间" + label + "格式非法: " + raw
                    + "（正确写法：2026-09-07T00:00，格式：ISO 日期时间）；请在「报表中心」用日期选择器重新选择该期间边界", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private void validatePeriodShape(long reportType, LocalDateTime start, LocalDateTime end) {
        if (!start.isBefore(end)) {
            throw new ServiceException("期间边界倒序: 起(" + start + ") 应早于 止(" + end
                    + ")（含起不含止）；请改成「期间起」早于「期间止」的范围后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        if (reportType == 1) {
            if (start.getDayOfWeek() != DayOfWeek.MONDAY || !end.equals(start.plusDays(7))) {
                throw new ServiceException("周报期间必须以周一起算的 7 天整窗（起=" + start
                        + "，止=" + end + "；应为周一 00:00 起、止=次周一 00:00）；请在「报表中心」重新选择满足该形状的期间",
                        BizErrorCode.VALIDATION_ERROR);
            }
        } else {
            boolean naturalMonth = YearMonth.from(start).atDay(1).atStartOfDay().equals(start)
                    && YearMonth.from(start).plusMonths(1).atDay(1).atStartOfDay().equals(end);
            if (!naturalMonth) {
                throw new ServiceException("月报期间必须为自然月（起=1 日 00:00，止=次月 1 日 00:00；"
                        + "当前 起=" + start + "，止=" + end + "）；请改成自然月窗口后在「报表中心」重新提交", BizErrorCode.VALIDATION_ERROR);
            }
        }
        Timestamp dbNow = named.getJdbcTemplate()
                .queryForObject("SELECT now()", Timestamp.class);
        if (start.isAfter(dbNow.toLocalDateTime())) {
            throw new ServiceException("期间起(" + start + ")晚于当前时间，未来期不可生成（数据未产生）；请改成不晚于当前时间的「期间起」",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static ReportView toView(ReportRow row) {
        return new ReportView(row.getId(), row.getScopeType(), row.getScopeId(), row.getReportType(),
                row.getPeriodStart(), row.getPeriodEnd(), row.getPayload(),
                row.getGeneratedAt(), row.getCreatedBy());
    }

    private static ReportListItem toListItem(ReportRow row) {
        return new ReportListItem(row.getId(), row.getScopeType(), row.getScopeId(),
                row.getReportType(), row.getPeriodStart(), row.getPeriodEnd(),
                row.getGeneratedAt(), row.getCreatedBy());
    }
}
