package com.sixbean.testpike.core.mock.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.component.ExportDialectResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialect export service : one space row +
 * its rule rows rendered through the selected dialect. Against the real dev
 * database (SpringBootTest, zero mocks — same posture as
 * MockListPagingServiceTest).
 * <p>Verified spectrum: (1) mockoon export produces a loadable document whose
 * endpoint paths already carry the space routing root, so an exported file
 * answers on the same URL as the live mock; (2) wiremock export emits the
 * {@code mappings} array; (3) export is a management-plane read — a DRAFT
 * (never published) space exports its full rule set (unlike the traffic-plane
 * snapshot, which stays publication-gated); (4) an unknown format is refused
 * in plain words naming the supported dialects; (5) a ghost space id is
 * refused instead of exporting an empty document.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockDialectExportServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private MockDialectExportService exportService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t363p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpaceId() {
        String key = "ex-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(newProject(), new MockSpaceInput(
                "Export Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject("SELECT space_key FROM tp_mock_space WHERE id = ?",
                String.class, spaceId);
    }

    private MockStubInput plainStub(String name, int priority, String pathType, String path) {
        return new MockStubInput(name, priority, "GET", pathType, path,
                "[]", "{\"status\":200,\"bodyMode\":\"static\",\"body\":\"ok\"}",
                null, null, null, "manual", null, true);
    }

    @Test
    void mockoonExportCarriesRouteRootUnderCallablePaths() throws Exception {
        long spaceId = newSpaceId();
        stubService.create(spaceId, plainStub("orders", 100, "exact", "/orders"));
        stubService.create(spaceId, plainStub("users prefix", 200, "prefix", "/users"));

        ExportDialectResult result = exportService.export(spaceId, "mockoon");

        assertEquals("mockoon", result.format());
        JsonNode endpoints = MAPPER.readTree(result.content()).path("endpoints");
        assertEquals(2, endpoints.size());
        String root = "/mocks/" + spaceKeyOf(spaceId);
        assertEquals(root + "/orders", endpoints.get(0).path("path").asText(),
                "exported path must be callable as-is under the space route");
        assertEquals(root + "/users*", endpoints.get(1).path("path").asText());
        assertNotNull(result.notes());
    }

    @Test
    void wiremockExportEmitsMappingsArray() throws Exception {
        long spaceId = newSpaceId();
        stubService.create(spaceId, plainStub("one", 100, "exact", "/one"));

        ExportDialectResult result = exportService.export(spaceId, "wiremock");

        assertEquals("wiremock", result.format());
        JsonNode mappings = MAPPER.readTree(result.content()).path("mappings");
        assertTrue(mappings.isArray());
        assertEquals(1, mappings.size());
        assertTrue(mappings.get(0).path("request").path("url").asText().startsWith("/mocks/"));
    }

    @Test
    void draftSpaceExportsFullRuleSet() {
        long spaceId = newSpaceId();
        stubService.create(spaceId, plainStub("draft rule", 100, "exact", "/draft"));
        // The space was never published: the snapshot door would hand an empty
        // stub list, the export door (management plane) must still export.
        ExportDialectResult result = exportService.export(spaceId, "mockoon");
        assertEquals("mockoon", result.format());
        assertTrue(result.content().contains("/draft"),
                "management-plane export is not publication-gated");
    }

    @Test
    void unknownFormatRefusedNamingBothDialects() {
        long spaceId = newSpaceId();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> exportService.export(spaceId, "postman"));
        assertTrue(ex.getMessage().contains("mockoon") && ex.getMessage().contains("wiremock"),
                "refusal must name the supported dialects: " + ex.getMessage());
    }

    @Test
    void ghostSpaceRefusedNotEmptyDocument() {
        assertThrows(ServiceException.class, () -> exportService.export(999_999_999L, "mockoon"));
    }
}
