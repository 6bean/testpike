package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementSourceAnchor;
import com.sixbean.testpike.core.support.RequirementDedupFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Landing tests for the evidence anchor column on tp_requirement (BA batch
 * design section 3.2: an adopted extraction item must keep the verbatim
 * evidence and the source chunk ids on the requirement row itself, so the
 * generation chain can later pull the original text block without re-parsing
 * document previews). Covers: a create carrying an anchor round-trips
 * documentId / chunkIds / evidence through JSONB unchanged; a create without
 * an anchor leaves the column NULL (zero drift for manual and legacy call
 * sites); and the six-arity legacy constructor keeps compiling and never
 * writes an anchor.
 * <p>Runs against the real dev database - no mocks. Seeding and id-based
 * cleanup reuse the single-source {@link RequirementDedupFixture}; factory
 * tables are never TRUNCATED.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementSourceAnchorLandingTest {

    @Autowired
    private RequirementService requirementService;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    private RequirementDedupFixture fixture;
    private long projectId;
    private final List<Long> createdIds = new java.util.ArrayList<>();

    @BeforeEach
    void seedProject() {
        fixture = RequirementDedupFixture.fromContext(context, "rsa", "证据锚入库项目");
        projectId = fixture.projectA();
    }

    @AfterEach
    void cleanupRows() {
        if (!createdIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", createdIds));
        }
        fixture.cleanup();
    }

    private RequirementInfo create(long projectId, String title, RequirementSourceAnchor anchor) {
        RequirementInfo info = requirementService.create(2L, new RequirementCreateInput(
                projectId, 2, "doc:" + projectId, title, "验收标准", "章一", null, anchor));
        createdIds.add(info.id());
        return info;
    }

    @Test
    void anchoredCreatePersistsEvidenceAndChunkIdsAsJsonb() {
        RequirementInfo info = create(projectId, "锚定需求点标题",
                new RequirementSourceAnchor(88L, List.of(41L, 42L), "逐字原文摘录"));
        Map<String, Object> anchor = jdbcTemplate.queryForObject(
                "SELECT source_anchor::text AS raw FROM tp_requirement WHERE id=:id",
                new MapSqlParameterSource("id", info.id()),
                (rs, n) -> readAnchor(rs.getString("raw")));
        assertNotNull(anchor);
        assertEquals(88L, ((Number) anchor.get("documentId")).longValue());
        assertEquals("逐字原文摘录", anchor.get("evidence"));
        assertEquals(List.of(41L, 42L), chunkIds(anchor));
    }

    @Test
    void anchorlessCreateLeavesColumnNullForZeroDrift() {
        RequirementInfo info = create(projectId, "无锚需求点标题", null);
        Object raw = jdbcTemplate.queryForObject(
                "SELECT source_anchor FROM tp_requirement WHERE id=:id",
                new MapSqlParameterSource("id", info.id()), (rs, n) -> rs.getString(1));
        assertNull(raw);
    }

    @Test
    void legacySixAritySiteWritesNoAnchor() {
        RequirementInfo info = requirementService.create(2L, new RequirementCreateInput(
                projectId, 3, null, "手工需求点标题", "验收标准", "章一"));
        createdIds.add(info.id());
        Object raw = jdbcTemplate.queryForObject(
                "SELECT source_anchor FROM tp_requirement WHERE id=:id",
                new MapSqlParameterSource("id", info.id()), (rs, n) -> rs.getString(1));
        assertNull(raw);
    }

    private static Map<String, Object> readAnchor(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("anchor column is not valid JSON: " + json, e);
        }
    }

    private static List<Long> chunkIds(Map<String, Object> anchor) {
        Object raw = anchor.get("chunkIds");
        return raw instanceof List<?> list
                ? list.stream().map(v -> ((Number) v).longValue()).toList()
                : List.of();
    }
}
