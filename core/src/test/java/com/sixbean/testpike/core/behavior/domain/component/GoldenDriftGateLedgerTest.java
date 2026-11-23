package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPair;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftVerdict;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-set drift ledger + promotion gate : a
 * recorded replay verdict is the admission ticket for promoting a gray
 * template version to full release. Fail-closed on purpose — "no verdict yet"
 * blocks just like "verdict failed" does, and the refusal names what is
 * missing plus the hot-reload key. Bidirectional contract: a degraded version
 * is blocked, a benign version passes (the L3 twin of this pair runs the real
 * replay). The factory seed row for the threshold key is pinned here so the
 * shipped default cannot silently drift. Runs on the real slot DB (strict
 * testing: no mocks); rows tagged ut-drift-domain and removed per case (R37:
 * no TRUNCATE of factory tables).
 */
@SpringBootTest(classes = CoreApplication.class)
class GoldenDriftGateLedgerTest {

    private static final String DOMAIN = "ut-drift-domain";
    private static final String TAG = "ut-drift-";

    @Autowired
    private GoldenDriftGate gate;

    @Autowired
    private PromptTemplateService templates;

    @Autowired
    private ConfigCacheHolder configCache;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tp_ai_golden_drift_verdict WHERE template_domain = ?", DOMAIN);
        jdbc.update("DELETE FROM tp_prompt_template WHERE domain = ?", DOMAIN);
        setThreshold(String.valueOf(GoldenDriftGate.SHIPPED_DEFAULT_PP));
    }

    /** Write-through the config row AND the startup cache (RetentionServiceTest precedent). */
    private void setThreshold(String value) {
        jdbc.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?",
                value, GoldenDriftGate.KEY_THRESHOLD);
        configCache.put(GoldenDriftGate.KEY_THRESHOLD, value);
    }

    /** Family with two versions: control saved first, candidate second. */
    private PromptTemplateItem[] family(String name) {
        PromptTemplateItem control = templates.save(
                new PromptTemplateSaveInput(name, DOMAIN, "control content", 1L));
        PromptTemplateItem candidate = templates.save(
                new PromptTemplateSaveInput(name, DOMAIN, "candidate content", 1L));
        return new PromptTemplateItem[]{control, candidate};
    }

    private static List<GoldenDriftPair> pairs(int total, int drifted) {
        java.util.ArrayList<GoldenDriftPair> list = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            String control = "digest-" + i;
            String candidate = i < drifted ? "flipped-" + i : control;
            list.add(new GoldenDriftPair(TAG + "sample-" + i, control, candidate));
        }
        return list;
    }

    @Test
    void recordedVerdictReadsBackWithDerivedRateAndFailFlagAboveTheBar() {
        PromptTemplateItem[] fam = family(TAG + "fam-read");
        boolean recorded = gate.recordComparison(DOMAIN, TAG + "fam-read", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 1), "same model, replayed once");
        assertTrue(recorded, "a fresh comparison must land in the ledger");
        GoldenDriftVerdict latest = gate.latestVerdict(DOMAIN, fam[1].id());
        assertNotNull(latest, "the recorded verdict must read back");
        assertEquals(4, latest.sampleTotal());
        assertEquals(1, latest.driftedCount());
        assertEquals(0, latest.driftPercentPp().compareTo(new java.math.BigDecimal("25.00")));
        assertEquals(gate.currentThresholdPp(), latest.thresholdPp());
        assertFalse(latest.passed(), "25pp drift exceeds the shipped 15pp bar and must fail");
    }

    @Test
    void degradedReplayIsBlockedAndBenignReplayPasses() {
        PromptTemplateItem[] fam = family(TAG + "fam-pass");
        gate.recordComparison(DOMAIN, TAG + "fam-pass", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "benign");
        GoldenDriftVerdict benign = gate.latestVerdict(DOMAIN, fam[1].id());
        assertNotNull(benign);
        assertTrue(benign.passed(), "zero drift passes the gate");

        PromptTemplateItem[] fam2 = family(TAG + "fam-block");
        gate.recordComparison(DOMAIN, TAG + "fam-block", fam2[1].id(), fam2[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 1), "one flipped sample");
        GoldenDriftVerdict degraded = gate.latestVerdict(DOMAIN, fam2[1].id());
        assertNotNull(degraded);
        assertFalse(degraded.passed(), "25pp > shipped 15pp must fail the gate");
    }

    @Test
    void promotionWithoutAnyVerdictIsRefusedAndNamesThePath() {
        PromptTemplateItem[] fam = family(TAG + "fam-none");
        ValidationException blocked = assertThrows(ValidationException.class,
                () -> templates.promote(fam[1].id()),
                "a gray version with no golden replay must not reach full release");
        assertTrue(blocked.getMessage().contains(GoldenDriftGate.KEY_THRESHOLD),
                "refusal must name the hot-reload key: " + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("黄金回放"),
                "refusal must tell the operator what is missing: " + blocked.getMessage());
    }

    @Test
    void promotionAfterFailedVerdictStaysBlocked() {
        PromptTemplateItem[] fam = family(TAG + "fam-fail");
        gate.recordComparison(DOMAIN, TAG + "fam-fail", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 1), "degraded");
        ValidationException blocked = assertThrows(ValidationException.class,
                () -> templates.promote(fam[1].id()));
        assertTrue(blocked.getMessage().contains("15") && blocked.getMessage().contains("25"),
                "the refusal must quote the numbers the operator can act on: " + blocked.getMessage());
    }

    @Test
    void promotionProceedsWhenVerdictPasses() {
        PromptTemplateItem[] fam = family(TAG + "fam-ok");
        gate.recordComparison(DOMAIN, TAG + "fam-ok", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "benign");
        PromptTemplateItem promoted = templates.promote(fam[1].id());
        assertEquals(2, promoted.status(), "a passing verdict admits the promotion");
    }

    @Test
    void reRecordingTheSameCandidateKeepsHistoryAndTheNewestGoverns() {
        PromptTemplateItem[] fam = family(TAG + "fam-replay");
        gate.recordComparison(DOMAIN, TAG + "fam-replay", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 1), "degraded first");
        gate.recordComparison(DOMAIN, TAG + "fam-replay", fam[1].id(), fam[0].id(),
                TAG + "pack", 2, "e".repeat(64), pairs(4, 0), "fixed pack, benign");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_golden_drift_verdict WHERE template_domain = ? AND candidate_version_id = ?",
                Integer.class, DOMAIN, fam[1].id());
        assertEquals(2, rows, "history stays auditable — re-recording never overwrites the old row");
        GoldenDriftVerdict latest = gate.latestVerdict(DOMAIN, fam[1].id());
        assertNotNull(latest);
        assertEquals(2, latest.packVersion());
        assertTrue(latest.passed(), "the newest verdict governs the gate");
    }

    @Test
    void thresholdIsHotReloadablePerCall() {
        PromptTemplateItem[] fam = family(TAG + "fam-k8");
        setThreshold("30");
        gate.recordComparison(DOMAIN, TAG + "fam-k8", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 1), "25pp");
        GoldenDriftVerdict v = gate.latestVerdict(DOMAIN, fam[1].id());
        assertNotNull(v);
        assertTrue(v.passed(), "with the bar at 30pp a 25pp drift passes");
        setThreshold("10");
        assertEquals(10, gate.currentThresholdPp(), "threshold must be re-read on every call");
    }

    @Test
    void outOfRangeStoredThresholdFallsBackToShippedDefault() {
        setThreshold("999");
        assertEquals(GoldenDriftGate.SHIPPED_DEFAULT_PP, gate.currentThresholdPp(),
                "garbage in the config row must not disable the gate");
    }

    @Test
    void factorySeedPinsTheShippedThreshold() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                GoldenDriftGate.KEY_THRESHOLD);
        assertEquals("15", String.valueOf(row.get("config_value")),
                "the migration seed must ship the adjudicated default (15pp)");
    }

    @Test
    void malformedComparisonsAreRejectedBeforeHittingTheLedger() {
        PromptTemplateItem[] fam = family(TAG + "fam-bad");
        assertThrows(IllegalArgumentException.class, () -> gate.recordComparison(
                DOMAIN, TAG + "fam-bad", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), List.of(), "zero samples"));
        assertThrows(IllegalArgumentException.class, () -> gate.recordComparison(
                DOMAIN, TAG + "fam-bad", fam[1].id(), fam[1].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "control equals candidate"));
        assertNull(gate.latestVerdict(DOMAIN, 9_000_299L), "unknown candidate reads as no verdict");
    }

    // ---- comparison axes : route/head verdicts for model and
    // assembler-head gear changes land on the same append-only ledger ----

    @Test
    void routeAxisComparisonLandsWithRefsAndDerivedRate() {
        assertTrue(gate.recordComparison(DOMAIN, TAG + "axis-route",
                GoldenDriftGate.KIND_ROUTE, "model-row-123", "model-row-456",
                TAG + "pack", 1, "d".repeat(64), pairs(4, 1), "route switch"),
                "a route-axis replay must land in the ledger");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT comparison_kind, control_ref, candidate_ref, candidate_version_id,"
                        + " control_version_id, sample_total, drifted_count"
                        + " FROM tp_ai_golden_drift_verdict"
                        + " WHERE template_domain = ? AND template_name = ? ORDER BY id DESC LIMIT 1",
                DOMAIN, TAG + "axis-route");
        assertEquals("route", row.get("comparison_kind"));
        assertEquals("model-row-123", row.get("control_ref"));
        assertEquals("model-row-456", row.get("candidate_ref"));
        assertEquals(0L, ((Number) row.get("candidate_version_id")).longValue(),
                "non-template axes have no template rows — ids record 0 instead of an FK lie");
        assertEquals(0L, ((Number) row.get("control_version_id")).longValue());
        assertEquals(4, ((Number) row.get("sample_total")).intValue());
        assertEquals(1, ((Number) row.get("drifted_count")).intValue());
        GoldenDriftVerdict latest = gate.latestVerdictAxis(DOMAIN, TAG + "axis-route",
                GoldenDriftGate.KIND_ROUTE, "model-row-123", "model-row-456");
        assertNotNull(latest, "the route-axis verdict must read back on its own axis key");
        assertEquals(0, latest.driftPercentPp().compareTo(new java.math.BigDecimal("25.00")));
        assertFalse(latest.passed(), "25pp drift fails the shipped 15pp bar on any axis");
    }

    @Test
    void axisComparisonsValidateShapeAndRejectSameSideOrUnknownKind() {
        assertThrows(IllegalArgumentException.class, () -> gate.recordComparison(
                DOMAIN, TAG + "axis-bad", GoldenDriftGate.KIND_ROUTE, "same-route", "same-route",
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "both sides identical"),
                "a route switch that never switched is not a comparison");
        assertThrows(IllegalArgumentException.class, () -> gate.recordComparison(
                DOMAIN, TAG + "axis-bad", GoldenDriftGate.KIND_HEAD, "  ", "new-head",
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "blank control"),
                "the control side of a head comparison is mandatory");
        assertThrows(IllegalArgumentException.class, () -> gate.recordComparison(
                DOMAIN, TAG + "axis-bad", "teleport", "a", "b",
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "not an axis"),
                "only template/route/head axes exist");
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM tp_ai_golden_drift_verdict WHERE template_domain = ? AND template_name = ?",
                Integer.class, DOMAIN, TAG + "axis-bad");
        assertEquals(0, rows, "rejected comparisons must never touch the ledger");
    }

    @Test
    void templateAxisSemanticsStayZeroDriftAcrossTheExtension() {
        // The gate reads the template axis exactly as before; a route row with
        // the same candidate id must not leak into template admission.
        PromptTemplateItem[] fam = family(TAG + "fam-axis-reg");
        gate.recordComparison(DOMAIN, TAG + "fam-axis-reg", fam[1].id(), fam[0].id(),
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "benign template replay");
        GoldenDriftVerdict t = gate.latestVerdict(DOMAIN, fam[1].id());
        assertNotNull(t);
        assertEquals("template", t.comparisonKind(), "legacy writes pin the template axis");
        assertNull(t.controlRef(), "template axis keeps refs empty — subjects live in the id columns");
        assertTrue(t.passed());
        templates.promote(fam[1].id());
        gate.recordComparison(DOMAIN, TAG + "fam-axis-reg", GoldenDriftGate.KIND_ROUTE,
                "route-old", "route-new", TAG + "pack", 1, "d".repeat(64),
                pairs(4, 4), "route switch fully drifted");
        GoldenDriftVerdict afterRoute = gate.latestVerdict(DOMAIN, fam[1].id());
        assertNotNull(afterRoute);
        assertTrue(afterRoute.passed(), "a failed route-axis verdict must not poison template admission");
        assertThrows(IllegalArgumentException.class, () -> gate.recordComparison(
                DOMAIN, TAG + "fam-axis-reg", "route", null, "x",
                TAG + "pack", 1, "d".repeat(64), pairs(4, 0), "case probe"),
                "axis names are exact — mixed case is not an axis");
    }
}
