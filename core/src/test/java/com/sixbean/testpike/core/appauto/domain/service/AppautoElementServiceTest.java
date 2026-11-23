package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * App automation element library CRUD segment (image element archive: the four
 * locator states IMAGE/COORD/REGION/OCR stored on tp_appauto_element).
 * <p>Covered surface: create + read-back, locator-type/confidence/name/config
 * validation, per-project unique name conflict, update, delete then missing,
 * and paginated listing with keyword + type filters (page/pageSize contract).
 * <p>Validation is archive-shape only: locator resolution semantics live on the
 * executor side (driver/element_candidates.py single source, mirrored in Java
 * is forbidden). Database is the real dev instance; no mocks on this surface.
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoElementServiceTest {

    @Autowired
    private AppautoElementService elementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "el" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "元素库测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanTables();
    }

    @AfterEach
    void tearDown() {
        cleanTables();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_appauto_element WHERE project_id = ?", projectId);
    }

    private static AppautoElementInput imageInput(String name) {
        return new AppautoElementInput(name, "IMAGE",
                Map.of("artifact_key", "elements/" + name + ".png", "threshold", 0.9),
                List.of(Map.of("resolution", "1080x2400",
                        "artifactKey", "elements/" + name + "@2x.png",
                        "ref_w", 540, "ref_h", 1200)),
                0.85);
    }

    @Test
    void createStoresProfileAndReadBackMatches() {
        long id = elementService.create(projectId, imageInput("login-button"));
        assertTrue(id > 0);
        AppautoElementView view = elementService.find(id);
        assertEquals(projectId, view.projectId());
        assertEquals("login-button", view.name());
        assertEquals("IMAGE", view.locatorType());
        assertNotNull(view.locatorConfig());
        assertTrue(view.locatorConfig().contains("login-button"));
        assertTrue(view.variants().contains("1080x2400"));
        assertEquals(0.85, view.confidenceThreshold(), 1e-9);
        assertNotNull(view.createdAt());
    }

    @Test
    void rejectsUnknownLocatorType() {
        AppautoElementInput bad = new AppautoElementInput("bad-type", "VIDEO",
                Map.of("artifact_key", "x"), List.of(), 0.8);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> elementService.create(projectId, bad));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("IMAGE"));
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        AppautoElementInput over = new AppautoElementInput("over", "OCR",
                Map.of("text", "确认"), List.of(), 1.5);
        assertThrows(ServiceException.class, () -> elementService.create(projectId, over));
        AppautoElementInput under = new AppautoElementInput("under", "OCR",
                Map.of("text", "确认"), List.of(), -0.2);
        assertThrows(ServiceException.class, () -> elementService.create(projectId, under));
    }

    @Test
    void rejectsBlankName() {
        AppautoElementInput blank = new AppautoElementInput("   ", "COORD",
                Map.of("x", 100, "y", 200), List.of(), 0.8);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> elementService.create(projectId, blank));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void rejectsDuplicateNameWithinProject() {
        elementService.create(projectId, imageInput("same-name"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> elementService.create(projectId, imageInput("same-name")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
    }

    @Test
    void rejectsMissingLocatorConfig() {
        AppautoElementInput noConfig = new AppautoElementInput("no-config", "IMAGE",
                Map.of(), List.of(), 0.8);
        assertThrows(ServiceException.class, () -> elementService.create(projectId, noConfig));
        AppautoElementInput nullConfig = new AppautoElementInput("null-config", "IMAGE",
                null, List.of(), 0.8);
        assertThrows(ServiceException.class, () -> elementService.create(projectId, nullConfig));
    }

    @Test
    void rejectsVariantItemWithoutArtifactKey() {
        AppautoElementInput broken = new AppautoElementInput("broken-variant", "IMAGE",
                Map.of("artifact_key", "a.png"),
                List.of(Map.of("resolution", "1080x2400", "ref_w", 540)), 0.8);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> elementService.create(projectId, broken));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void updateChangesProfileFields() {
        long id = elementService.create(projectId, imageInput("rename-me"));
        AppautoElementInput changed = new AppautoElementInput("renamed-ok", "OCR",
                Map.of("text", "提交"), List.of(), 0.7);
        AppautoElementView view = elementService.update(id, changed);
        assertEquals("renamed-ok", view.name());
        assertEquals("OCR", view.locatorType());
        assertEquals(0.7, view.confidenceThreshold(), 1e-9);
        assertTrue(view.variants().isEmpty() || "[]".equals(view.variants().trim()));
    }

    @Test
    void deleteRemovesRowAndMissingReadsAreHumanErrors() {
        long id = elementService.create(projectId, imageInput("to-delete"));
        elementService.delete(id);
        ServiceException missing = assertThrows(ServiceException.class,
                () -> elementService.find(id));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());
        assertThrows(ServiceException.class, () -> elementService.delete(id));
        assertThrows(ServiceException.class, () -> elementService.update(id, imageInput("ghost")));
    }

    @Test
    void pageFiltersByKeywordAndTypeAndReportsTotal() {
        elementService.create(projectId, imageInput("login-button"));
        elementService.create(projectId, new AppautoElementInput("login-input", "IMAGE",
                Map.of("artifact_key", "b.png"), List.of(), 0.8));
        elementService.create(projectId, new AppautoElementInput("submit-text", "OCR",
                Map.of("text", "提交"), List.of(), 0.9));

        AppautoElementPageView all = elementService.page(projectId, null, null, 1, 10);
        assertEquals(3, all.total());
        assertEquals(3, all.list().size());

        AppautoElementPageView byKeyword = elementService.page(projectId, "LOGIN", null, 1, 10);
        assertEquals(2, byKeyword.total());

        AppautoElementPageView byType = elementService.page(projectId, null, "OCR", 1, 10);
        assertEquals(1, byType.total());
        assertEquals("submit-text", byType.list().get(0).name());

        AppautoElementPageView firstPage = elementService.page(projectId, null, null, 1, 2);
        assertEquals(3, firstPage.total());
        assertEquals(2, firstPage.list().size());
        AppautoElementPageView secondPage = elementService.page(projectId, null, null, 2, 2);
        assertEquals(1, secondPage.list().size());
    }
}
