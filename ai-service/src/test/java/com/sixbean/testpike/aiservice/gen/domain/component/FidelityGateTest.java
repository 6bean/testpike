package com.sixbean.testpike.aiservice.gen.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * the faithfulness checker compares the key claims of a
 * generated case (quantities, boundary words, state words, negation words)
 * against the original source blocks. A tampered figure must always be
 * detected; a faithful restatement must always pass — the two-directional
 * fixture demanded by the adjudication acceptance line.
 */
class FidelityGateTest {

    private final FidelityGate gate = new FidelityGate();

    // ---- segment: control-reference family (quoted control names vs
    // ---- the page-inspect candidate list, the existence evidence surface).

    /** Candidate segment in the exact PageInspectBridge.candidatesText shape. */
    private static final String CANDIDATES = "目标页可交互元素候选（已验证存在）：\n"
            + "- <button> 「登录」 css=#login-btn\n"
            + "- <input> 「用户名」 css=#username\n"
            + "- <a> text=首页\n";

    // ---- segment: honest marking against the NO-evidence face. When the
    // ---- candidate segment is absent (no probe) the draft may still carry
    // ---- URL / locator / expected-value literals; those must either ride the
    // ---- the SQL steps already keep for an empty retrieval). Invented
    // ---- unmarked values are disputes; honestly marked ones never misfire.

    private static final String UNEVIDENCED_DRAFT =
            "{\"steps\":[{\"description\":\"打开购物车页\","
                    + "\"url\":\"https://shop.example.com/cart\"},"
                    + "{\"description\":\"断言出现结算按钮\",\"target\":{\"text\":\"结算\"}}]}";

    @Test
    void unevidencedLocatorsWithoutHonestMarkAreDetected() {
        FidelityVerdict verdict = gate.controlRefs(UNEVIDENCED_DRAFT, "", true);

        assertFalse(verdict.passed());
        assertEquals(2, verdict.checkedClaims());
        assertEquals(0, verdict.matchedClaims());
        assertTrue(verdict.disputes().stream().allMatch(d -> d.contains("需人工精化")),
                String.join("；", verdict.disputes()));
    }

    @Test
    void unevidencedClaimsMarkedForManualRefinementPass() {
        String draft = "{\"steps\":[{\"description\":\"打开购物车页（需人工精化）\","
                + "\"url\":\"https://shop.example.com/cart\"},"
                + "{\"description\":\"断言出现结算按钮（需人工精化）\",\"target\":{\"text\":\"结算\"}}]}";

        FidelityVerdict verdict = gate.controlRefs(draft, "", true);

        assertTrue(verdict.passed(), String.join("；", verdict.disputes()));
        assertEquals(2, verdict.checkedClaims());
        assertEquals(2, verdict.matchedClaims());
    }

    @Test
    void honestMarkNeverWhitelistsGhostNamesAgainstPresentCandidates() {
        // Candidate segment in place = the old backed/unbacked rule decides;
        // the honest mark is only the no-evidence channel, not a second pass.
        String draft = "{\"steps\":[{\"description\":\"点击「立即登录」（需人工精化）\"}]}";

        FidelityVerdict verdict = gate.controlRefs(draft, CANDIDATES, true);

        assertFalse(verdict.passed());
        assertTrue(verdict.disputes().get(0).contains("控件引用无候选依据"));
    }

    @Test
    void legacyOverloadKeepsBlankSegmentAuditFreeByteForByte() {
        // Switch off = the historic shape: blank segment audits nothing and the
        // same draft passes silently (existing zero-detection rows stay green).
        assertTrue(gate.controlRefs(UNEVIDENCED_DRAFT, "").passed());
        assertTrue(gate.controlRefs(UNEVIDENCED_DRAFT, "", false).passed());
    }

    @Test
    void ghostControlNameAgainstCandidatesIsDetected() {
        String draft = "{\"steps\":[{\"description\":\"点击「立即登录」按钮\"}]}";

        FidelityVerdict verdict = gate.controlRefs(draft, CANDIDATES);

        assertFalse(verdict.passed());
        assertEquals(1, verdict.disputes().size());
        assertTrue(verdict.disputes().get(0).contains("立即登录"));
        assertEquals(1, verdict.checkedClaims());
        assertEquals(0.0, verdict.fidelityRatio(), 0.0001);
    }

    @Test
    void candidateBackedControlNamesPassBothQuoteAndTextLocatorForms() {
        String draft = "{\"steps\":[{\"description\":\"点击「登录」\"},"
                + "{\"description\":\"断言出现首页\",\"target\":{\"text\":\"首页\"}}]}";

        FidelityVerdict verdict = gate.controlRefs(draft, CANDIDATES);

        assertTrue(verdict.passed(), String.join("；", verdict.disputes()));
        assertEquals(2, verdict.checkedClaims());
        assertEquals(2, verdict.matchedClaims());
        assertEquals(1.0, verdict.fidelityRatio(), 0.0001);
    }

    @Test
    void absentCandidateSegmentAuditsNothingAndNeverBlocks() {
        // No probe / gate off / fail-soft empty segment = no existence evidence
        // surface: the same wording can never be judged a ghost reference.
        String draft = "点击「立即登录」按钮";

        FidelityVerdict blank = gate.controlRefs(draft, "");
        assertTrue(blank.passed());
        assertEquals(0, blank.checkedClaims());

        FidelityVerdict nullSegment = gate.controlRefs(draft, null);
        assertTrue(nullSegment.passed());
        assertEquals(0, nullSegment.checkedClaims());
    }

    @Test
    void repeatedQuotedControlNameIsAuditedOnce() {
        String draft = "点击「登录」，再次点击「登录」确认。";

        FidelityVerdict verdict = gate.controlRefs(draft, CANDIDATES);

        assertTrue(verdict.passed());
        assertEquals(1, verdict.checkedClaims());
    }

    @Test
    void tamperedQuantityAgainstSourceIsDetected() {
        List<String> source = List.of("用户连续输入错误密码 3 次后，账户锁定 30 分钟。");
        String generated = "预期结果：账户锁定 45 分钟。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertFalse(verdict.passed());
        assertEquals(1, verdict.disputes().size());
        assertTrue(verdict.disputes().get(0).contains("45"));
    }

    @Test
    void faithfulQuantityPassesWithoutDispute() {
        List<String> source = List.of("用户连续输入错误密码 3 次后，账户锁定 30 分钟。");
        String generated = "预期结果：账户锁定 30 分钟。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertTrue(verdict.passed());
        assertEquals(2, verdict.checkedClaims());
        assertEquals(1.0, verdict.fidelityRatio(), 0.0001);
    }

    @Test
    void figureOnlyEmbeddedInsideLongerNumberIsNotBacked() {
        // substring matching would silently pass a tampered figure.
        List<String> source = List.of("每 15 分钟同步一次数据。");
        String generated = "预期结果：重试间隔 5 秒。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertFalse(verdict.passed());
        assertEquals(1, verdict.disputes().size());
    }

    @Test
    void boundaryWordAbsentFromSourceIsDetected() {
        List<String> source = List.of("表单提交后保存记录。");
        String generated = "步骤 1：输入不超过 200 字符的备注。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertFalse(verdict.passed());
        assertTrue(verdict.disputes().stream().anyMatch(d -> d.contains("不超过")));
    }

    @Test
    void boundaryWordSupportedBySourceFamilyPasses() {
        List<String> source = List.of("备注最大长度 200 字符，超出时拒绝提交。");
        String generated = "步骤 1：输入不超过 200 字符的备注。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertTrue(verdict.passed());
    }

    @Test
    void negationWordAbsentFromSourceIsDetected() {
        List<String> source = List.of("管理员可以导出全部报表。");
        String generated = "预期结果：普通成员禁止导出报表。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertFalse(verdict.passed());
        assertTrue(verdict.disputes().stream().anyMatch(d -> d.contains("禁止")));
    }

    @Test
    void negationWordSupportedBySourcePasses() {
        List<String> source = List.of("普通成员不允许导出报表。");
        String generated = "预期结果：普通成员禁止导出报表。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertTrue(verdict.passed());
    }

    @Test
    void blankSourceBlocksMeanNothingToCompareIsReportedNotChecked() {
        String generated = "预期结果：账户锁定 45 分钟。";

        FidelityVerdict verdict = gate.verdict(generated, List.of("", "  "));

        assertTrue(verdict.passed());
        assertEquals(0, verdict.checkedClaims());
        assertEquals(1.0, verdict.fidelityRatio(), 0.0001);
    }

    @Test
    void ordinalAndYearExemptionsAreInheritedFromTheSharedQuantityRule() {
        // Step labels and years are positional/date shapes, not measurements;
        // the exemption family lives in the shared NumberProvenance piece and
        // must not be re-derived here (single-source rule R40).
        List<String> source = List.of("2026 年发布版本 3。");
        String generated = "第 3 步：确认提示 5 条记录；系统于 2026 年升级。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertEquals(1, verdict.checkedClaims());
        assertFalse(verdict.passed());
    }

    /**
 * honestMarkHits : one hit per step chunk that
 * carries at least one evidenceless claim AND the honest mark — a stray
 * mention outside a claim step is not a signal, an unmarked invented
 * value is the dispute channel, not the confidence channel.
 */
    @Test
    void honestMarkHitsCountsMarkedEvidencelessStepsOnly() {
        String draft = """
                {"title":"登录","steps":[
                 {"stepNo":1,"description":"打开 http://shop.test/login（需人工精化）"},
                 {"stepNo":2,"description":"点击「登录按钮」"},
                 {"stepNo":3,"description":"查看首页（需人工精化）"},
                 {"stepNo":4,"description":"断言提示","expected":"支付成功（需人工精化）"}]}
                """;

        // step1 URL claim + mark, step4 expected-literal claim + mark; step2
        // claim is unmarked (dispute side), step3 marked without any claim.
        assertEquals(2, FidelityGate.honestMarkHits(draft));
    }

    @Test
    void honestMarkHitsIsZeroForBlankOrClaimFreeDraft() {
        assertEquals(0, FidelityGate.honestMarkHits(null));
        assertEquals(0, FidelityGate.honestMarkHits("  "));
        assertEquals(0, FidelityGate.honestMarkHits(
                "{\"title\":\"登录\",\"steps\":[{\"description\":\"输入账号密码\"}]}"));
    }

    @Test
    void mixedClaimsYieldPartialFidelityRatio() {
        List<String> source = List.of("锁定 30 分钟后自动解锁。");
        String generated = "预期结果：锁定 30 分钟；重试上限 5 次。";

        FidelityVerdict verdict = gate.verdict(generated, source);

        assertEquals(3, verdict.checkedClaims());
        assertEquals(2, verdict.matchedClaims());
        assertEquals(2.0 / 3.0, verdict.fidelityRatio(), 0.0001);
    }
}
