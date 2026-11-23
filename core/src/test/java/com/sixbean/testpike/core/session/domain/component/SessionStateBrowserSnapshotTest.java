package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser-context materialisation from the login-state ledger ( face 3,
 * AV4-3 "UI case context injection", single source SessionStateSupport per
 * re-derives it).
 * <p>Capture-lineage rows store the checked entries as an array
 * ([{kind,name,value,origin,expiresAtEpochSecond}]); the browser needs the
 * Playwright storage_state shape ({cookies:[{name,value,domain,path,secure,
 * expires}], origins:[{origin,localStorage:[{name,value}]}]}). This suite pins
 * that translation: cookie entries become cookies with the domain taken from
 * the recorded origin (scheme carries secure), local_storage entries group
 * under origins, session cookies report expires=-1, a manual Playwright
 * grammar snapshot passes through untouched.
 * <p>Honest refusals are part of the contract: a token-kind row cannot be
 * injected into a browser context (API face consumes it instead) and an
 * ambiguous role selector lists the candidates rather than guessing —
 * wording must name the remedy, not just fail.
 * <p>Real slot6 database, zero mocks; fixture-owned rows cleaned in tearDown.
 */
@SpringBootTest(classes = CoreApplication.class)
class SessionStateBrowserSnapshotTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private SessionStateSupport support;

    @Autowired
    private LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "sbs" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "session-browser-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void register(String stateName, String roleScope, String kind, String secret) {
        // Vault keys accept ASCII [A-Za-z_][A-Za-z0-9_]* only (single source in
        // CredentialService), and hashCode() both can go negative and can
        // collide across names — the state name itself, stripped to ASCII,
        // stays unique among the fixtures below and cannot clash.
        String credKey = "sbs" + stateName.replaceAll("[^A-Za-z0-9_]", "");
        loginStateService.register(projectId, new LoginStateInput(
                stateName, roleScope, null, kind, credKey,
                secret, null, List.of("https://shop.example", "https://app.example"),
                "browser snapshot fixture"));
    }

    @Test
    void captureEntriesSnapshotBecomesPlaywrightStorageState() throws Exception {
        long future = System.currentTimeMillis() / 1000L + 3600;
        register("sbs-mixed", "买家", "storage_state", "["
                + "{\"kind\":\"cookie\",\"name\":\"SESSIONID\",\"value\":\"v1\","
                + "\"origin\":\"https://shop.example\",\"expiresAtEpochSecond\":" + future + "},"
                + "{\"kind\":\"local_storage\",\"name\":\"theme\",\"value\":\"dark\","
                + "\"origin\":\"https://app.example\",\"expiresAtEpochSecond\":null}]");

        JsonNode state = JSON.readTree(support.browserStorageStateJson(projectId, "sbs-mixed", null));
        JsonNode cookie = state.path("cookies").get(0);
        assertEquals("SESSIONID", cookie.path("name").asText());
        assertEquals("v1", cookie.path("value").asText());
        assertEquals("shop.example", cookie.path("domain").asText());
        assertEquals("/", cookie.path("path").asText());
        assertTrue(cookie.path("secure").asBoolean(), "https origin records a secure cookie");
        assertEquals(future, cookie.path("expires").asLong());

        JsonNode origin = state.path("origins").get(0);
        assertEquals("https://app.example", origin.path("origin").asText());
        assertEquals("theme", origin.path("localStorage").get(0).path("name").asText());
        assertEquals("dark", origin.path("localStorage").get(0).path("value").asText());
    }

    @Test
    void sessionCookieRecordsInfiniteExpiry() throws Exception {
        register("sbs-session", null, "storage_state", "["
                + "{\"kind\":\"cookie\",\"name\":\"S\",\"value\":\"x\","
                + "\"origin\":\"http://shop.example\",\"expiresAtEpochSecond\":null}]");
        JsonNode state = JSON.readTree(support.browserStorageStateJson(projectId, "sbs-session", null));
        JsonNode cookie = state.path("cookies").get(0);
        assertEquals(-1, cookie.path("expires").asLong(), "no expiry seen = session cookie");
        assertEquals(false, cookie.path("secure").asBoolean(), "http origin records an insecure cookie");
    }

    @Test
    void cookieSetFromCaptureBecomesCookiesOnlyState() throws Exception {
        register("sbs-cookies", null, "cookie_set", "["
                + "{\"kind\":\"cookie\",\"name\":\"A\",\"value\":\"1\",\"origin\":\"https://shop.example\","
                + "\"expiresAtEpochSecond\":1780000000},"
                + "{\"kind\":\"cookie\",\"name\":\"B\",\"value\":\"2\",\"origin\":\"https://shop.example\","
                + "\"expiresAtEpochSecond\":null}]");
        JsonNode state = JSON.readTree(support.browserStorageStateJson(projectId, "sbs-cookies", null));
        assertEquals(2, state.path("cookies").size());
        assertTrue(state.path("origins").isArray());
        assertEquals(0, state.path("origins").size());
    }

    @Test
    void manualPlaywrightGrammarSnapshotPassesThrough() throws Exception {
        // One replayable cookie rides along: the save-door gate refuses a
        // document that could never produce a Cookie header for replay.
        register("sbs-manual", null, "storage_state",
                "{\"cookies\":[{\"name\":\"M\",\"value\":\"m1\",\"domain\":\"shop.example\","
                        + "\"path\":\"/\",\"secure\":true,\"expires\":-1}],"
                        + "\"origins\":[{\"origin\":\"https://shop.example\",\"localStorage\":[] }]}");
        String out = support.browserStorageStateJson(projectId, "sbs-manual", null);
        assertNotNull(out);
        JsonNode state = JSON.readTree(out);
        assertEquals("https://shop.example", state.path("origins").get(0).path("origin").asText());
    }

    @Test
    void tokenKindRefusesBrowserInjectionNamingTheRemedy() {
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        register("sbs-token", null, "token", "plain-token-value");
        ServiceException refused = assertThrows(ServiceException.class,
                () -> support.browserStorageStateJson(projectId, "sbs-token", null));
        assertTrue(refused.getMessage().contains("token"), "the refusal names the offending kind");
        assertTrue(refused.getMessage().contains("storage_state"),
                "the refusal points at the re-capture form that can be injected");
        assertNotNull(header);
    }

    @Test
    void ambiguousRoleListsCandidatesInsteadOfGuessing() {
        // Material must clear the save-door gate; the ambiguity refusal happens
        // earlier, at resolve, and never reads the vault value.
        String entry = "[{\"kind\":\"cookie\",\"name\":\"R\",\"value\":\"r\","
                + "\"origin\":\"https://shop.example\"}]";
        register("sbs-role-a", "审批人", "storage_state", entry);
        register("sbs-role-b", "审批人", "storage_state", entry);
        ServiceException refused = assertThrows(ServiceException.class,
                () -> support.browserStorageStateJson(projectId, null, "审批人"));
        assertTrue(refused.getMessage().contains("sbs-role-a"));
        assertTrue(refused.getMessage().contains("sbs-role-b"));
    }

    @Test
    void unknownStateNameFailsClosed() {
        assertThrows(ServiceException.class,
                () -> support.browserStorageStateJson(projectId, "sbs-absent" + UUID.randomUUID(), null));
    }
}
