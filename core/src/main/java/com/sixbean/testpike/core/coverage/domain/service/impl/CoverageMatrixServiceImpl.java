package com.sixbean.testpike.core.coverage.domain.service.impl;

import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.core.coverage.api.dto.MatrixCaseColumn;
import com.sixbean.testpike.core.coverage.api.dto.MatrixChapter;
import com.sixbean.testpike.core.coverage.api.dto.MatrixRequirementRow;
import com.sixbean.testpike.core.coverage.domain.service.CoverageMatrixService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class CoverageMatrixServiceImpl implements CoverageMatrixService {

    private final JdbcTemplate jdbcTemplate;

    public CoverageMatrixServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CoverageMatrixView matrix(long projectId, String chapter, Integer priority, String endType) {
        List<Object[]> reqRows = jdbcTemplate.query("""
                SELECT id, title, COALESCE(chapter, '未分组') AS ch
                FROM tp_requirement
                WHERE project_id = ? AND status <> 3 AND deleted = 0
                  AND (?::varchar IS NULL OR chapter = ?)
                ORDER BY ch, id
                """, (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)},
                projectId, chapter, chapter);

        List<Object[]> caseRows = jdbcTemplate.query("""
                SELECT id, case_no, title, priority, end_type
                FROM tp_test_case
                WHERE project_id = ? AND deleted = 0 AND status <> 9
                  AND (?::int IS NULL OR priority = ?)
                  AND (?::varchar IS NULL OR end_type = ?)
                ORDER BY id
                """, (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getString(5)},
                projectId, priority, priority, endType, endType);

        Map<Long, List<Long>> links = new HashMap<>();
        List<Long> reqIds = reqRows.stream().map(r -> (Long) r[0]).toList();
        if (!reqIds.isEmpty()) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < reqIds.size(); i++) {
                if (i > 0) {
                    in.append(',');
                }
                in.append('?');
            }
            jdbcTemplate.query("""
                    SELECT cr.requirement_id, cr.case_id
                    FROM tp_case_requirement cr
                    JOIN tp_test_case c ON c.id = cr.case_id AND c.deleted = 0 AND c.status <> 9
                    WHERE cr.requirement_id IN (%s)
                    """.formatted(in), rs -> {
                long rid = rs.getLong(1);
                links.computeIfAbsent(rid, k -> new ArrayList<>()).add(rs.getLong(2));
            }, reqIds.toArray());
        }

        Map<String, List<MatrixRequirementRow>> byChapter = new LinkedHashMap<>();
        long covered = 0;
        List<Long> uncoveredIds = new ArrayList<>();
        for (Object[] r : reqRows) {
            long rid = (Long) r[0];
            List<Long> cell = links.getOrDefault(rid, List.of());
            boolean isCovered = !cell.isEmpty();
            if (isCovered) {
                covered++;
            } else {
                uncoveredIds.add(rid);
            }
            byChapter.computeIfAbsent((String) r[2], k -> new ArrayList<>())
                    .add(new MatrixRequirementRow(rid, (String) r[1], isCovered, cell));
        }
        List<MatrixChapter> chapters = byChapter.entrySet().stream()
                .map(e -> new MatrixChapter(e.getKey(), e.getValue())).toList();
        List<MatrixCaseColumn> cases = caseRows.stream()
                .map(c -> new MatrixCaseColumn((Long) c[0], (String) c[1], (String) c[2],
                        (Integer) c[3], (String) c[4])).toList();
        return new CoverageMatrixView(chapters, cases, reqRows.size(), covered, uncoveredIds);
    }
}
