package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubDraftAdvice;
import com.sixbean.testpike.core.mock.api.dto.SpecImportVerdict;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Advice-state band of the contract-bulk import preview (MJ P3 refinement
 * band): the
 * deterministic floor answers at once, the AI refinement round is awaited
 * only within the hot deadline window, and every row always carries an
 * honest state — pending while backfill may still arrive, degraded once the
 * deadline passed without one, refined after an AI row lands through the
 * single intake port. The default selection of the wizard mirrors the advice
 * verdicts, and intake never invents rows for endpoints the floor did not
 * judge (no twins, no bypass of the human confirm gate).
 * <p>Real slot database, zero mocks — same posture as
 * {@code SpecImportVerdictServiceTest}.
 */
@SpringBootTest(classes = CoreApplication.class)
class SpecImportAdviceStateTest {

    private static final String DEADLINE_KEY = "mock.spec-import.verdict-deadline-seconds";
    private static final String EXPANDED_BODY =
            "{\"status\":200,\"bodyMode\":\"json\",\"body\":{\"orderId\":\"a-1\",\"total\":12,"
                    + "\"items\":[{\"sku\":\"x-1\",\"qty\":2}]}}";

    @Autowired
    private SpecImportVerdictService verdictService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> usedSpecIds = new ArrayList<>();
    private boolean deadlineKeyTouched;

    @AfterEach
    void cleanTrackedRows() {
        for (Long specId : usedSpecIds) {
            jdbcTemplate.update("DELETE FROM tp_mock_spec_import_verdict WHERE spec_id = ?", specId);
        }
        if (deadlineKeyTouched) {
            jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", DEADLINE_KEY);
        }
    }

    private long newProject() {
        String code = "mjw" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId) {
        String key = "mjw-" + UUID.randomUUID().toString().substring(0, 8);
        return spaceService.create(projectId, new MockSpaceInput(
                "MJW " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    /** Preview rounds key rows by spec id; a sequence value stands in for one. */
    private long freshSpecId() {
        long specId = jdbcTemplate.queryForObject("SELECT nextval('tp_api_spec_id_seq')", Long.class);
        usedSpecIds.add(specId);
        return specId;
    }

    private MockStubDraft draft(String method, String path, String name) {
        return new MockStubDraft(method + " " + path, name, method,
                path.contains("{") ? "template" : "exact", path,
                "[]", EXPANDED_BODY, "openapi", "spec:0");
    }

    private Map<String, MockStubDraftAdvice> byKey(List<MockStubDraftAdvice> rows) {
        return rows.stream().collect(Collectors.toMap(
                MockStubDraftAdvice::endpointKey, Function.identity()));
    }

    /**
     * The floor answers immediately and is honest about waiting for the AI
     * round: fresh endpoints are recommended and pre-checked, and the state
     * inside the deadline window is pending — never a blank row.
     */
    @Test
    void freshDraftIsRecommendedCheckedAndPendingInsideDeadline() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        MockStubDraft d = draft("GET", "/orders/{id}", "订单查询");

        List<MockStubDraftAdvice> advice = verdictService.advise(
                specId, spaceId, List.of(d));

        assertEquals(1, advice.size());
        MockStubDraftAdvice row = byKey(advice).get("GET /orders/{id}");
        assertEquals("GET /orders/{id}", row.endpointKey());
        assertEquals(SpecImportVerdict.VERDICT_RECOMMEND, row.verdict());
        assertEquals(MockStubDraftAdvice.STATE_PENDING, row.adviceState());
        assertTrue(row.defaultChecked(), "the wizard pre-selects recommended rows");
        assertEquals("订单查询", row.name());
        assertNotNull(row.reason());
    }

    /**
     * Once the hot deadline passes without a refinement the row degrades with
     * an honest label — the rule advice stays usable, no fake AI claim.
     */
    @Test
    void expiredRoundDegradesToRuleDefault() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        MockStubDraft d = draft("POST", "/orders", "创建订单");

        verdictService.advise(specId, spaceId, List.of(d));
        // age the stored round beyond the shipped 20s window
        jdbcTemplate.update("""
                UPDATE tp_mock_spec_import_verdict
                   SET created_at = now() - make_interval(secs => 120)
                 WHERE spec_id = ?
                """, specId);

        MockStubDraftAdvice row = byKey(
                verdictService.advise(specId, spaceId, List.of(d))).get("POST /orders");

        assertEquals(MockStubDraftAdvice.STATE_DEGRADED, row.adviceState());
        assertEquals(SpecImportVerdict.VERDICT_RECOMMEND, row.verdict());
        assertTrue(row.defaultChecked());
    }

    /**
     * The AI intake port is the single writer of refined advice: it flips the
     * stored row, the state reads refined, a changed verdict re-drives the
     * default selection, and neither junk verdicts nor keys the floor never
     * judged create or twin any row.
     */
    @Test
    void aiRefinementFlipsStateAndRejectsUnknownKeys() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        MockStubDraft kept = draft("GET", "/orders/{id}", "订单查询");
        MockStubDraft dropped = draft("POST", "/orders", "创建订单");
        verdictService.advise(specId, spaceId, List.of(kept, dropped));

        verdictService.applyAiRefinement(specId, spaceId, List.of(
                new SpecImportVerdict("POST /orders", SpecImportVerdict.VERDICT_AVOID,
                        "该写接口带副作用，批量启用前建议人工核对示例数据。",
                        null, null, null, 80, 88, SpecImportVerdict.ORIGIN_AI, 0),
                new SpecImportVerdict("PUT /ghost", "nonsense",
                        "非法判定不应落库", null, null, null, 50, 50,
                        SpecImportVerdict.ORIGIN_AI, 0)));

        Map<String, MockStubDraftAdvice> rows = byKey(
                verdictService.advise(specId, spaceId, List.of(kept, dropped)));

        assertEquals(MockStubDraftAdvice.STATE_REFINED, rows.get("POST /orders").adviceState());
        assertEquals(SpecImportVerdict.VERDICT_AVOID, rows.get("POST /orders").verdict());
        assertFalse(rows.get("POST /orders").defaultChecked());
        assertEquals(MockStubDraftAdvice.STATE_PENDING, rows.get("GET /orders/{id}").adviceState());

        Integer stored = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mock_spec_import_verdict WHERE spec_id = ?",
                Integer.class, specId);
        assertEquals(2, stored, "intake updates in place and never invents twin rows");
    }

    /**
     * A garbage hot key falls back to the shipped 20s window instead of
     * breaking the preview band.
     */
    @Test
    void garbageDeadlineKeyFallsBackToShippedWindow() {
        long projectId = newProject();
        long spaceId = newSpace(projectId);
        long specId = freshSpecId();
        deadlineKeyTouched = true;
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type,
                                           encrypt_flag, description)
                VALUES (?, 'not-a-number', '接口仿真', 'int', 0, 'advice-state test hot key')
                ON CONFLICT (config_key) DO UPDATE SET config_value = 'not-a-number'
                """, DEADLINE_KEY);

        MockStubDraftAdvice row = byKey(verdictService.advise(
                specId, spaceId, List.of(draft("GET", "/fallback-check", "回退校验"))))
                .get("GET /fallback-check");

        assertEquals(MockStubDraftAdvice.STATE_PENDING, row.adviceState(),
                "an unreadable deadline must degrade to the shipped window, not to instant degraded");
    }
}
