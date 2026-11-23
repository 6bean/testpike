package com.sixbean.testpike.core.apitest.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * collection-level default headers/auth on the save and read faces.
 * <p>Covers: create persists headersJson/authJson and both read faces (get + tree
 * list) carry them back; absent input normalizes to empty defaults ('[]' / '{}')
 * so legacy behavior is byte-identical; malformed shapes fail closed at the save
 * gate using the request-level single-source vocabulary (array for headers,
 * AuthFormContract for auth); rename with null keeps the stored value unchanged
 * and with a value updates it. Real dev database — no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiCollectionDefaultsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Structural equality: stored-JSON text form follows PG jsonb normalization
     * (create returns the Jackson-compact form, read-back the jsonb::text form);
     * the contract is the parsed structure the assembler actually consumes. */
    private static void assertJsonEquals(String expectedRaw, String actualRaw) throws Exception {
        JsonNode expected = MAPPER.readTree(expectedRaw);
        JsonNode actual = MAPPER.readTree(actualRaw);
        assertEquals(expected, actual, "expected <" + expectedRaw + "> but was <" + actualRaw + ">");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @BeforeEach
    @AfterEach
    void dropCollectionFixtureRows() {
        // Row-scoped teardown (R37②): never truncate shipped tables.
        ApiCollectionFixtureSupport.cleanFixtureRows(jdbcTemplate);
    }

    private long createProject(String code) {
        return projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    @Test
    void create_persists_defaults_and_read_faces_carry_them_back() throws Exception {
        long pid = createProject("T412D1");
        ApiCollectionInfo created = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "订单域", null,
                        "[{\"name\":\"X-Tenant\",\"value\":\"acme\"}]",
                        "{\"type\":\"bearer\",\"token\":\"{{CRED:TOK}}\"}"));

        assertJsonEquals("[{\"name\":\"X-Tenant\",\"value\":\"acme\"}]", created.headersJson());
        assertJsonEquals("{\"type\":\"bearer\",\"token\":\"{{CRED:TOK}}\"}", created.authJson());

        ApiCollectionInfo single = apiCollectionService.get(created.id());
        assertJsonEquals(created.headersJson(), single.headersJson());
        assertJsonEquals(created.authJson(), single.authJson());

        List<ApiCollectionInfo> tree = apiCollectionService.list(pid);
        ApiCollectionInfo inTree = tree.stream()
                .filter(c -> c.id() == created.id()).findFirst().orElseThrow();
        assertJsonEquals(single.headersJson(), inTree.headersJson());
        assertJsonEquals(single.authJson(), inTree.authJson());
    }

    @Test
    void absent_defaults_normalize_to_empty_columns() {
        long pid = createProject("T412D2");
        ApiCollectionInfo created = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "无默认域", null));

        // Normalized posture: null input lands as the column defaults, not null,
        // so every consumer sees one shape and the merge point stays trivial.
        assertEquals("[]", created.headersJson());
        assertEquals("{}", created.authJson());
        assertEquals("[]", apiCollectionService.get(created.id()).headersJson());
        assertEquals("{}", apiCollectionService.get(created.id()).authJson());
    }

    @Test
    void malformed_headers_are_refused_at_the_save_gate() {
        long pid = createProject("T412D3");
        ServiceException err = assertThrows(ServiceException.class, () ->
                apiCollectionService.create(1L, new CollectionSaveInput(pid, 0L,
                        "坏头域", null, "{\"name\":\"X\"}", null)));
        assertTrue(err.getMessage().contains("数组"), err.getMessage());
    }

    @Test
    void malformed_auth_is_refused_with_the_single_source_vocabulary() {
        long pid = createProject("T412D4");
        // Unknown auth type must fail with the AuthFormContract verdict — the
        // collection gate must not grow its own dialect (red-line: single source).
        ServiceException err = assertThrows(ServiceException.class, () ->
                apiCollectionService.create(1L, new CollectionSaveInput(pid, 0L,
                        "坏认证域", null, null, "{\"type\":\"oauth-magic\"}")));
        assertTrue(err.getMessage().contains("未支持"), err.getMessage());

        // Explicit none is a legal stored form on the collection face too.
        assertNotNull(apiCollectionService.create(1L, new CollectionSaveInput(pid, 0L,
                "none域", null, null, "{\"type\":\"none\"}")));
    }

    @Test
    void rename_updates_provided_fields_and_keeps_omitted_ones() throws Exception {
        long pid = createProject("T412D5");
        ApiCollectionInfo created = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "结算域", null,
                        "[{\"name\":\"A\",\"value\":\"1\"}]", "{}"));

        // headers provided, auth null → auth stays as stored
        ApiCollectionInfo renamed = apiCollectionService.rename(1L, created.id(),
                null, "交易", "[{\"name\":\"B\",\"value\":\"2\"}]", null);
        assertEquals("结算域", renamed.name());
        assertEquals("交易", renamed.tags());
        assertJsonEquals("[{\"name\":\"B\",\"value\":\"2\"}]", renamed.headersJson());
        assertJsonEquals("{}", renamed.authJson());

        // legacy four-argument rename keeps the extension (defaults untouched)
        ApiCollectionInfo legacy = apiCollectionService.rename(1L, created.id(), "结算二", null);
        assertEquals("结算二", legacy.name());
        assertJsonEquals("[{\"name\":\"B\",\"value\":\"2\"}]", legacy.headersJson());

        // refused shapes cannot slip through the rename face either
        assertThrows(ServiceException.class, () ->
                apiCollectionService.rename(1L, created.id(), null, null, null, "[1]"));
    }
}
