package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.domain.component.CaseSourceFacet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provenance facet and evidence-grade filtering on the case list (BH batch ,
 * backend side of . The list is paginated, so both filters must be
 * server-side predicates — filtering only the current page in the browser would
 * hide matching rows. The SQL facet expression is a deliberate mirror of
 * {@link CaseSourceFacet#of(int, String)}; this suite pins the two against each
 * other on real rows so the single-source rule cannot drift.
 * Spectrum runs on the real dev database, zero mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseSourceFacetQueryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CaseService caseService;

    /** One seeded row: persisted provenance columns plus the facet the user sees. */
    private record Spec(int source, String sourceRef, int evidenceGrade, String facet) {
    }

    private long projectId;
    private long firstCaseId;
    private final List<Spec> rows = List.of(
            new Spec(1, "mcp", 2, CaseSourceFacet.OFF_AGENT),
            new Spec(1, "MCP:P-9", 3, CaseSourceFacet.OFF_AGENT),
            new Spec(1, "mcp-x", 2, CaseSourceFacet.OFF_AGENT),
            new Spec(1, "mcp_y", 0, CaseSourceFacet.OFF_AGENT),
            new Spec(1, "mcpz", 1, CaseSourceFacet.MANUAL),
            new Spec(1, "chat:123", 3, CaseSourceFacet.CHAT),
            new Spec(1, "CHAT", 0, CaseSourceFacet.CHAT),
            new Spec(2, null, 1, CaseSourceFacet.IMPORT),
            new Spec(3, "chat-7", 2, CaseSourceFacet.CHAT),
            new Spec(4, "case:12", 0, CaseSourceFacet.MANUAL),
            new Spec(1, null, 0, CaseSourceFacet.MANUAL));

    @BeforeEach
    void seedProjectAndCases() {
        projectId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 900000 FROM tp_project", Long.class);
        jdbcTemplate.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                projectId, "facet seed project", "FCTP-SEED-" + projectId);
        firstCaseId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 900000 FROM tp_test_case", Long.class);
        for (int i = 0; i < rows.size(); i++) {
            Spec spec = rows.get(i);
            jdbcTemplate.update(
                    "INSERT INTO tp_test_case (id, case_no, project_id, title, priority, status, "
                            + "deleted, source, source_ref, evidence_grade, created_at, updated_at) "
                            + "OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, 1, 1, 0, ?, ?, ?, now(), now())",
                    firstCaseId + i, "FCT-" + (firstCaseId + i), projectId,
                    "facet fixture " + i, spec.source(), spec.sourceRef(), spec.evidenceGrade());
        }
    }

    @AfterEach
    void purgeSeed() {
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private Set<Long> idSetOf(String facet, Integer grade) {
        CaseQuery query = new CaseQuery(projectId, null, null, null, null, null, null, null,
                null, null, 1, 500, null, 0L, facet, grade);
        return caseService.list(query, null).list().stream()
                .map(CaseInfo::id).collect(Collectors.toSet());
    }

    private Set<Long> expectedFacetIds(String facet) {
        Set<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).facet().equals(facet)) {
                ids.add(firstCaseId + i);
            }
        }
        return ids;
    }

    @Test
    void sqlFacetMirrorMatchesJavaMappingOnSeededRows() {
        List<Map<String, Object>> raw = jdbcTemplate.queryForList(
                "SELECT id, source, source_ref, " + CaseSourceFacet.FACET_CASE_SQL + " AS facet "
                        + "FROM tp_test_case WHERE id >= ? AND id < ? ORDER BY id",
                firstCaseId, firstCaseId + rows.size());
        assertEquals(rows.size(), raw.size(), "all fixture rows must exist");
        for (Map<String, Object> row : raw) {
            long id = ((Number) row.get("id")).longValue();
            String fromSql = (String) row.get("facet");
            String fromJava = CaseSourceFacet.of((Integer) row.get("source"), (String) row.get("source_ref"));
            assertEquals(fromJava, fromSql, "SQL mirror must agree with the Java single source for row " + id);
        }
        // And both agree with the hand-written expectations, so a shared drift
        // between the two implementations is still caught.
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(rows.get(i).facet(), (String) raw.get(i).get("facet"),
                    "row " + i + " (" + rows.get(i).source() + ", " + rows.get(i).sourceRef() + ")");
        }
    }

    @Test
    void listFilterByOffAgentFacetReturnsExactlyMarkedRows() {
        assertEquals(expectedFacetIds(CaseSourceFacet.OFF_AGENT), idSetOf(CaseSourceFacet.OFF_AGENT, null));
    }

    @Test
    void listFilterByChatAndImportAndManualFacets() {
        assertEquals(expectedFacetIds(CaseSourceFacet.CHAT), idSetOf(CaseSourceFacet.CHAT, null));
        assertEquals(expectedFacetIds(CaseSourceFacet.IMPORT), idSetOf(CaseSourceFacet.IMPORT, null));
        assertEquals(expectedFacetIds(CaseSourceFacet.MANUAL), idSetOf(CaseSourceFacet.MANUAL, null));
    }

    @Test
    void listFilterByEvidenceGradeCombinesWithFacet() {
        Set<Long> grade3 = new java.util.HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).evidenceGrade() == 3) {
                grade3.add(firstCaseId + i);
            }
        }
        assertEquals(grade3, idSetOf(null, 3));
        Set<Long> offAgentGrade2 = Set.of(firstCaseId + 0, firstCaseId + 2);
        assertEquals(offAgentGrade2, idSetOf(CaseSourceFacet.OFF_AGENT, 2));
    }

    @Test
    void unknownFacetOrGradeRejectedWithPlainLanguage() {
        ServiceException facetError = assertThrows(ServiceException.class,
                () -> idSetOf("external-proxy", null));
        String message = facetError.getMessage();
        assertTrue(message.contains(CaseSourceFacet.CHAT) && message.contains(CaseSourceFacet.OFF_AGENT)
                        && message.contains(CaseSourceFacet.IMPORT) && message.contains(CaseSourceFacet.MANUAL),
                "the refusal must name the four accepted values, got: " + message);
        assertTrue(message.contains("external-proxy"), "the refusal must echo the offending value");
        assertThrows(ServiceException.class, () -> idSetOf(null, 9));
    }
}
