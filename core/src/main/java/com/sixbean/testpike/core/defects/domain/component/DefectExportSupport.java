package com.sixbean.testpike.core.defects.domain.component;

import com.sixbean.testpike.common.defect.DefectLabels;
import com.sixbean.testpike.core.common.domain.component.SqlPlaceholders;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>The label map itself lives in the common module because the dialog
 * grounding side (ai-service) is a second consumer of the same labels.
 *
 */
@Component
public class DefectExportSupport {

 
    public static final List<String> HEADERS = List.of(
            "缺陷编号", "标题", "严重程度", "状态", "关联用例编号",
            "修复人", "报告人", "外部Key", "描述", "创建时间");

    private final JdbcTemplate jdbcTemplate;

    public DefectExportSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public List<DefectExportRow> assembleRows(List<DefectLibraryInfo> visible) {
        if (visible.isEmpty()) {
            return List.of();
        }
        Map<Long, String> caseNos = loadCaseNos(visible);
        List<DefectExportRow> rows = new ArrayList<>(visible.size());
        for (DefectLibraryInfo d : visible) {
            rows.add(new DefectExportRow(
                    String.valueOf(d.id()), d.title(),
                    DefectLabels.severityLabel(d.severity()), DefectLabels.statusLabel(d.status()),
                    d.caseId() == null ? "" : caseNos.getOrDefault(d.caseId(), ""),
                    d.assigneeName(), d.reporterName(), d.externalKey(), d.description(),
                    d.createdAt()));
        }
        return rows;
    }

    
    private Map<Long, String> loadCaseNos(List<DefectLibraryInfo> visible) {
        List<Long> caseIds = visible.stream()
                .map(DefectLibraryInfo::caseId).filter(Objects::nonNull).distinct().toList();
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT id, case_no FROM tp_test_case WHERE id IN (" + SqlPlaceholders.in(caseIds.size()) + ")",
                rs -> {
                    result.put(rs.getLong(1), rs.getString(2));
                }, caseIds.toArray());
        return result;
    }

    
    public static String sheetName() {
        return "缺陷导出";
    }
}
