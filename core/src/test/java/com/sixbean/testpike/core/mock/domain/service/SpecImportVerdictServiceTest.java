package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubImportResult;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.SpecImportVerdict;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import com.sixbean.testpike.core.mock.domain.component.SpecImportVerdictRetentionCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic pre-adjudication floor of the contract-bulk import wizard
 * (MJ P3): fresh endpoints are recommended with a business one-liner, an
 * endpoint the space already serves is advised away with a skip action that
 * names the conflicting rule, the impact reading carries real 7-day hits from
 * the hit ledger, and re-running a preview updates the stored advice in place
 * (never twins). The retention cleaner deletes only aged rows and falls back
 * to the shipped window on a garbage hot key.
 * <p>All against the real slot database (SpringBootTest, zero mocks — the
 * same posture as {@code MockStubImportServiceTest}).
 */
@SpringBootTest(classes = CoreApplication.class)
class SpecImportVerdictServiceTest {

    private static final String EXPANDED_BODY =
            "{\"status\":200,\"bodyMode\":\"json\",\"body\":{\"orderId\":\"a-1\",\"total\":12,"
                    + "\"items\":[{\"sku\":\"x-1\",\"qty\":2}]}}";
    private static final String STATIC_ECHO_BODY =
            "{\"status\":200,\"bodyMode\":\"json\",\"body\":{\"code\":0,\"message\":\"ok\"}}";

    @Autowired
    private SpecImportVerdictService verdictService;

    @Autowired
    private MockStubImportService importService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private SpecImportVerdictRetentionCleaner retentionCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> usedSpecIds = new ArrayList<>();
    private final List<Long> usedHitLogIds = new ArrayList<>();

    @AfterEach
    void cleanTrackedRows() {
        for (Long specId : usedSpecIds) {
            jdbcTemplate.update("DELETE FROM tp_mock_spec_import_verdict WHERE spec_id = ?", specId);
        }
        for (Long hitId : usedHitLogIds) {
            jdbcTemplate.update("DELETE FROM tp_mock_hit_log WHERE id = ?", hitId);
        }
    }

    private long newProject() {
        String code = "mjv" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        // through the real save chain (no fixture shortcut past the gates)
        String key = "mjv-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new MockSpaceInput(
                "MJV " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    /** Preview rounds key rows by spec id; a sequence value stands in for one. */
    private long freshSpecId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('tp_api_spec','id'))", Long.class);
        usedSpecIds.add(id);
        return id;
    }

    private MockStubDraft draft(String endpointKey, String name, String method, String path,
                                String responseJson) {
        return new MockStubDraft(endpointKey, name, method, "exact", path, "[]",
                responseJson, MockStubDraft.SOURCE_OPENAPI, "spec:probe");
    }

    @Test
    void freshEndpointsAreRecommendedAndStored() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();

        List<SpecImportVerdict> verdicts = verdictService.judge(specId, spaceId, List.of(
                draft("GET /v/ping", "Ping 探活", "GET", "/v/ping", EXPANDED_BODY),
                draft("POST /v/echo", "Echo 回显", "POST", "/v/echo", STATIC_ECHO_BODY)));

        assertEquals(2, verdicts.size());
        SpecImportVerdict ping = verdicts.get(0);
        assertEquals(SpecImportVerdict.VERDICT_RECOMMEND, ping.verdict());
        assertNull(ping.conflictAction());
        assertTrue(ping.reason().contains("Ping 探活"));
        assertEquals(80, ping.realism(), "an expanded schema body is real initialized data");
        assertEquals(75, ping.confidence());
        assertEquals(SpecImportVerdict.ORIGIN_RULE, ping.origin());
        SpecImportVerdict echo = verdicts.get(1);
        assertEquals(20, echo.realism(), "the static {code,message} placeholder proves nothing");
        assertEquals(55, echo.confidence());

        List<SpecImportVerdict> stored = verdictService.listVerdicts(specId, spaceId);
        assertEquals(2, stored.size());
        assertEquals("GET /v/ping", stored.get(0).endpointKey());
    }

    @Test
    void conflictingEndpointIsAdvisedAwayWithRealImpactReading() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        MockStubDraft same = draft("GET /v/ping", "Ping 探活", "GET", "/v/ping", EXPANDED_BODY);
        MockStubImportResult filed = importService.importDrafts(spaceId, List.of(same));
        Long stubId = filed.createdStubIds().get(0);
        // one real hit row: the impact reading must count it, not invent it
        jdbcTemplate.update(
                "INSERT INTO tp_mock_hit_log (space_id, stub_id, matched) VALUES (?, ?, TRUE)",
                spaceId, stubId);
        Long hitLogId = jdbcTemplate.queryForObject(
                "SELECT max(id) FROM tp_mock_hit_log WHERE stub_id = ?", Long.class, stubId);
        usedHitLogIds.add(hitLogId);

        List<SpecImportVerdict> verdicts = verdictService.judge(specId, spaceId, List.of(same));

        assertEquals(1, verdicts.size());
        SpecImportVerdict conflict = verdicts.get(0);
        assertEquals(SpecImportVerdict.VERDICT_AVOID, conflict.verdict());
        assertEquals(SpecImportVerdict.ACTION_SKIP, conflict.conflictAction(),
                "adjudication D9: same method+path advises skip, never a silent overwrite");
        assertEquals(stubId, conflict.conflictStubId());
        assertTrue(conflict.reason().contains("Ping 探活"));
        assertEquals(90, conflict.confidence());
        assertTrue(conflict.impactJson().contains("\"hits7d\":1"),
                "hits must come from the ledger: " + conflict.impactJson());

        List<MockStubInfo> after = stubService.listBySpace(spaceId);
        assertEquals(1, after.size(), "advising is not writing — the advice never files a rule");
    }

    @Test
    void rejudgeUpdatesRowsInPlaceNotTwins() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        List<MockStubDraft> drafts = List.of(
                draft("GET /v/ping", "Ping 探活", "GET", "/v/ping", EXPANDED_BODY));

        verdictService.judge(specId, spaceId, drafts);
        verdictService.judge(specId, spaceId, drafts);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mock_spec_import_verdict WHERE spec_id = ?",
                Integer.class, specId);
        assertEquals(1, rows, "a second preview refreshes the row, it never doubles it");
    }

    @Test
    void retentionCleanerRemovesAgedRowsOnlyAndSurvivesGarbageKeys() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        verdictService.judge(specId, spaceId, List.of(
                draft("GET /v/old", "Old", "GET", "/v/old", EXPANDED_BODY),
                draft("GET /v/new", "New", "GET", "/v/new", EXPANDED_BODY)));
        jdbcTemplate.update("UPDATE tp_mock_spec_import_verdict SET created_at = now() - interval '100 days'"
                + " WHERE spec_id = ? AND endpoint_key = 'GET /v/old'", specId);

        int removed = retentionCleaner.trimExpired();

        assertTrue(removed >= 1);
        List<SpecImportVerdict> left = verdictService.listVerdicts(specId, spaceId);
        assertEquals(1, left.size());
        assertEquals("GET /v/new", left.get(0).endpointKey());

        // garbage hot key must fall back to the shipped window, never delete-all
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'not-a-number'"
                + " WHERE config_key = ?", SpecImportVerdictRetentionCleaner.KEY_RETENTION_DAYS);
        try {
            assertEquals(SpecImportVerdictRetentionCleaner.DEFAULT_RETENTION_DAYS,
                    retentionCleaner.resolveRetentionDays());
        } finally {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = '90'"
                    + " WHERE config_key = ?", SpecImportVerdictRetentionCleaner.KEY_RETENTION_DAYS);
        }
    }
}
