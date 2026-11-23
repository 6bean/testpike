package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementVersionInfo;
import com.sixbean.testpike.core.support.RequirementFixtureCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Requirement version chain read surface :
 * GET /internal/requirements/{id}/versions data source. Runs against the real dev
 * database - no mocks; fixtures self-create rows and clean up by collected ids.
 * <p>Covers: chain returned newest-first with operator display name and per-version
 * changed-field count (baseline semantics of the case version list), soft-deleted and
 * unknown requirements refused with 40401 reusing the detail 404 wording source.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementVersionChainReadTest {

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RequirementFixtureCleaner cleaner;

    /** Self-created operator row; version chains resolve operatorName through tp_user. */
    private long operatorId;

    @BeforeEach
    void initCleaner() {
        // built after Spring field injection, otherwise the injected template is still null here
        cleaner = new RequirementFixtureCleaner(jdbcTemplate);
        // operatorName resolves through tp_user: the ambient id 1 does not exist on a
        // freshly provisioned chain slot, so the fixture creates its own account and
        // reclaims it by id in @AfterEach (R37: self-created rows, no factory-table trust).
        operatorId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, "rvcr-op-" + (System.nanoTime() % 1_000_000_000L), "rvcr-operator");
    }

    @AfterEach
    void cleanupCreatedRows() {
        cleaner.cleanup();
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", operatorId);
    }

    private String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long createProject() {
        String code = "alr" + tag();
        long pid = projectService.create(operatorId,
                new ProjectCreateInput("版本史读面项目" + code, code, "zh-CN", "P1", null));
        cleaner.trackProject(pid);
        return pid;
    }

    private RequirementInfo createRequirement(long projectId, String title) {
        RequirementInfo info = requirementService.create(operatorId, new RequirementCreateInput(
                projectId, 3, "R-" + title, title, "验收标准：" + title, "3.1"));
        cleaner.trackRequirement(info.id());
        return info;
    }

    // ── chain shape: newest first, metadata attached ─────────────────────────

    @Test
    void listVersions_returnsChainNewestFirstWithOperatorAndChangeTypes() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "登录功能");
        requirementService.update(operatorId, created.id(),
                new RequirementUpdateInput("登录功能改版", null, null, null, null, null, null));
        requirementService.update(operatorId, created.id(),
                new RequirementUpdateInput(null, null, null, null, null, 2, null));

        List<RequirementVersionInfo> chain = requirementService.listVersions(created.id());

        assertEquals(3, chain.size());
        assertEquals(List.of(3, 2, 1), chain.stream().map(RequirementVersionInfo::version).toList());
        // 3=latest STATUS change, 2=EDIT, 1=initial snapshot (change_type vocabulary V329)
        assertEquals(List.of(2, 1, 1), chain.stream().map(RequirementVersionInfo::changeType).toList());
        for (RequirementVersionInfo v : chain) {
            assertEquals(created.id(), v.requirementId());
            assertNotNull(v.createdAt());
            assertNotNull(v.operatorName());
        }
        // latest version moved only the status key; the middle one only the title
        assertEquals(List.of(1, 1, 0), chain.stream().map(RequirementVersionInfo::changedFieldCount).toList());
    }

    // ── changed-field count: adjacent-snapshot diff baseline semantics ───────

    @Test
    void listVersions_countsChangedFieldsAgainstPreviousVersion() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "登录功能");
        requirementService.update(operatorId, created.id(),
                new RequirementUpdateInput("新标题", null, null, "新验收", null, null, null));

        List<RequirementVersionInfo> chain = requirementService.listVersions(created.id());

        assertEquals(2, chain.size());
        assertEquals(0, chain.get(1).changedFieldCount()); // initial version: no baseline
        assertEquals(2, chain.get(0).changedFieldCount()); // title + acceptanceCriteria moved
    }

    // ── visibility doors reuse the detail 404 wording source ─────────────────

    @Test
    void listVersions_ofSoftDeletedRequirement_refusedAsNotFound() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "登录功能");
        requirementService.delete(operatorId, created.id());

        ServiceException gone = assertThrows(ServiceException.class,
                () -> requirementService.listVersions(created.id()));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
    }

    @Test
    void listVersions_ofUnknownRequirement_refusedAsNotFound() {
        ServiceException gone = assertThrows(ServiceException.class,
                () -> requirementService.listVersions(999_999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, gone.getCode());
    }

    // ── soft delete still keeps the chain rows for auditors, but the reader
    // door closes together with the detail view ─

    @Test
    void softDelete_keepsVersionRowsInTableWhileReaderStaysClosed() {
        long pid = createProject();
        RequirementInfo created = createRequirement(pid, "登录功能");
        requirementService.delete(operatorId, created.id());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_requirement_version WHERE requirement_id = ?",
                Integer.class, created.id());
        assertNotNull(rows);
        assertFalse(rows < 2); // v1 snapshot + DELETE(3) snapshot preserved
        assertThrows(ServiceException.class, () -> requirementService.listVersions(created.id()));
    }
}
