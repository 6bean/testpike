package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.common.domain.component.ExportScopeValidator;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.imports.domain.component.CaseExcelParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The batch export assembly component for test cases is a shared component within a batch, and defect export reuses the same path.
 * Byte generation is handled by {@link com.sixbean.testpike.core.common.domain.component.ExcelExportSupport}.
 * This component focuses on row assembly in the test case domain.
 * <p>Responsibility: transform visible test cases produced by CaseService queries into export row views by batch loading steps from tp_case_step, tags from tp_case_tag to tp_tag_dict, assignee display names with username fallback using the same convention as review history, and Chinese status labels from the single CaseStatus enum source, while aggregating step columns using the import template convention of multiple operation => expected entries separated by semicolons and using batch IN queries to prevent N+1.
 * Tags are loaded from tp_case_tag to tp_tag_dict, and assignee display names fall back from display_name to username using the same convention as review history.
 * The same convention is followed, Chinese status labels come from the single CaseStatus enum source, and step column aggregation follows the import template convention.
 * Multiple steps are separated by semicolons in the operation => expected format to maintain round-trip consistency, and batch IN queries prevent N+1.
 *
 */
@Component
public class CaseExportSupport {

 /** Export headers place the seven template columns first for round-trip consistency and append case number, status, tags, and assignee while asserting column order. */
    public static final List<String> HEADERS = List.of(
            "测试目标", "前置条件", "预期结果", "优先级", "置信度", "终端类型", "步骤",
            "用例编号", "状态", "标签", "分配人");

 /** The synchronous export limit constant only forwards to ExportScopeValidator as the single entry point, and exports over 2000 use the asynchronous export queue. */
    public static final int ASYNC_THRESHOLD = ExportScopeValidator.ASYNC_THRESHOLD;

    private final JdbcTemplate jdbcTemplate;

    public CaseExportSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Assemble export rows from visible test cases already filtered by data scope, then batch assemble step, tag, and assignee columns.
     *
     * @param visible visible test cases in query sort order.
     * @return export row views.
     */
    public List<CaseExportRow> assembleRows(List<CaseInfo> visible) {
        if (visible.isEmpty()) {
            return List.of();
        }
        List<Long> ids = visible.stream().map(CaseInfo::id).toList();
        Map<Long, String> stepsByCase = loadSteps(ids);
        Map<Long, String> tagsByCase = loadTagNames(ids);
        Map<Long, String> userNames = loadDisplayNames(visible);
        List<CaseExportRow> rows = new ArrayList<>(visible.size());
        for (CaseInfo c : visible) {
            CaseStatus status = CaseStatus.of(c.status());
            rows.add(new CaseExportRow(
                    c.title(), c.precondition(), c.expectedResult(),
                    c.priority(), c.confidence(), c.endType(),
                    stepsByCase.getOrDefault(c.id(), ""),
                    c.caseNo(),
                    status == null ? String.valueOf(c.status()) : status.label(),
                    tagsByCase.getOrDefault(c.id(), ""),
                    c.assigneeId() == null ? "" : userNames.getOrDefault(c.assigneeId(), "")));
        }
        return rows;
    }

    /** Batch step aggregate text uses semicolon-separated operation => expected entries, with assertion steps containing arrows and operation steps as plain text according to the template convention. */
    private Map<Long, String> loadSteps(List<Long> ids) {
        Map<Long, List<String>> byCase = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT case_id, description, is_assert FROM tp_case_step WHERE case_id IN ("
                        + SqlPlaceholders.in(ids.size()) + ") ORDER BY case_id, step_no",
                rs -> {
                    long caseId = rs.getLong(1);
                    String desc = rs.getString(2);
                    byCase.computeIfAbsent(caseId, k -> new ArrayList<>()).add(desc);
                }, ids.toArray());
        Map<Long, String> result = new HashMap<>();
        byCase.forEach((caseId, descs) -> result.put(caseId, String.join("; ", descs)));
        return result;
    }

    /** Batch tag names are comma-separated with stable dictionary-name ordering. */
    private Map<Long, String> loadTagNames(List<Long> ids) {
        Map<Long, StringJoiner> byCase = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT ct.case_id, td.name FROM tp_case_tag ct"
                        + " JOIN tp_tag_dict td ON ct.tag_id = td.id"
                        + " WHERE ct.case_id IN (" + SqlPlaceholders.in(ids.size()) + ") ORDER BY td.name",
                rs -> {
                    long caseId = rs.getLong(1);
                    byCase.computeIfAbsent(caseId, k -> new StringJoiner(",")).add(rs.getString(2));
                }, ids.toArray());
        Map<Long, String> result = new HashMap<>();
        byCase.forEach((caseId, joiner) -> result.put(caseId, joiner.toString()));
        return result;
    }

    /** Batch assignee display names fall back to username when display_name is empty, using the same convention as review history columns. */
    private Map<Long, String> loadDisplayNames(List<CaseInfo> visible) {
        List<Long> userIds = visible.stream()
                .map(CaseInfo::assigneeId).filter(java.util.Objects::nonNull).distinct().toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, COALESCE(NULLIF(display_name, ''), username) FROM tp_user WHERE id IN ("
                        + SqlPlaceholders.in(userIds.size()) + ")",
                rs -> {
                    names.put(rs.getLong(1), rs.getString(2));
                }, userIds.toArray());
        return names;
    }

    /** The file name and template column descriptions are synchronized, with the sheet name set to test case export. */
    public static String sheetName() {
        return "用例导出";
    }

    /** Template column references prevent drift by requiring the first seven HEADERS columns to match the import template columns. */
    static {
        if (!HEADERS.subList(0, CaseExcelParser.HEADERS.size()).equals(CaseExcelParser.HEADERS)) {
            throw new IllegalStateException("导出列头前 7 列必须与导入模板一致（往返一致铁律）");
        }
    }
}
