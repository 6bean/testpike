package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.executor.domain.component.SessionStateDeclaration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 for the login-state batch preflight (AV3 trigger ①): blocking vs warning
 * classification, legacy no-declaration zero drift, and the refusal wording
 * contract (which state, which case, where to renew, which key — R32 pair —
 * plus the protected-page verification target, D11).
 */
class SessionPreflightSupportTest {

    private static final int LEAD = 15;

    private static SessionPreflightRow row(String caseNo, String title,
                                           SessionStateDeclaration declaration,
                                           String resolved, ExpiryState state) {
        return new SessionPreflightRow(caseNo, title, declaration, resolved, state);
    }

    @Test
    void validRowsDoNotRefuse() {
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(List.of(
                row("TC-101", "下单流程", new SessionStateDeclaration("buyer_a", null), "buyer_a", ExpiryState.VALID),
                row("TC-102", "查询库存", null, null, null)), LEAD);
        assertFalse(outcome.refused());
        assertTrue(outcome.blockedLines().isEmpty());
        assertTrue(outcome.warningLines().isEmpty());
        assertNull(SessionPreflightSupport.refusalMessage(outcome, LEAD));
    }

    @Test
    void expiredRowRefusesAndNamesCaseAndState() {
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(List.of(
                row("TC-201", "订单详情", new SessionStateDeclaration("buyer_a", null), "buyer_a", ExpiryState.EXPIRED)), LEAD);
        assertTrue(outcome.refused());
        assertEquals(1, outcome.blockedLines().size());
        String message = SessionPreflightSupport.refusalMessage(outcome, LEAD);
        assertTrue(message.contains("TC-201"));
        assertTrue(message.contains("订单详情"));
        assertTrue(message.contains("buyer_a"));
        assertTrue(message.contains("已过期"));
        assertTrue(message.contains("登录态账本"));
        assertTrue(message.contains(SessionGovernanceKeys.KEY_RENEW_LEAD_MINUTES));
        assertTrue(message.contains("系统配置"));
        assertTrue(message.contains("统一身份认证页面"));
    }

    @Test
    void unresolvedDeclarationRefusesWithSelectorListing() {
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(List.of(
                row("TC-301", "后台审核", new SessionStateDeclaration(null, "admin"), null, null)), LEAD);
        assertTrue(outcome.refused());
        String message = SessionPreflightSupport.refusalMessage(outcome, LEAD);
        assertTrue(message.contains("角色 admin"));
        assertTrue(message.contains("没有可解析的有效条目"));
    }

    @Test
    void expiringAndUnknownWarnWithoutBlocking() {
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(List.of(
                row("TC-401", "购物车", new SessionStateDeclaration("buyer_b", null), "buyer_b", ExpiryState.EXPIRING),
                row("TC-402", "收藏页", new SessionStateDeclaration("buyer_c", null), "buyer_c", ExpiryState.UNKNOWN)), LEAD);
        assertFalse(outcome.refused());
        assertEquals(2, outcome.warningLines().size());
        assertTrue(outcome.warningLines().get(0).contains("即将到期"));
        assertTrue(outcome.warningLines().get(0).contains("15 分钟"));
        assertTrue(outcome.warningLines().get(1).contains("无法识别"));
        assertTrue(outcome.warningLines().get(1).contains("401/403"));
    }

    @Test
    void legacyRowsAndNullInputKeepBatchAllowed() {
        assertFalse(SessionPreflightSupport.classify(null, LEAD).refused());
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(
                List.of(row("TC-501", "旧用例", null, null, null)), LEAD);
        assertFalse(outcome.refused());
        assertTrue(outcome.warningLines().isEmpty());
    }

    /**
     * Worst-scale spec (S-group): a 200-case batch where 150 rows block must
     * keep the exact blocked count in the outcome while the refusal renders a
     * bounded listing — the full ledger never travels inside one toast.
     */
    @Test
    void worstCaseScaleRefusalStaysBoundedAndCountsExactly() {
        List<SessionPreflightRow> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(row("TC-E" + i, "受保护页面用例" + i,
                    new SessionStateDeclaration("expired_state_" + (i % 10), null),
                    "expired_state_" + (i % 10), ExpiryState.EXPIRED));
        }
        for (int i = 0; i < 50; i++) {
            rows.add(row("TC-U" + i, "缺失条目用例" + i,
                    new SessionStateDeclaration(null, "role_" + (i % 5)), null, null));
        }
        for (int i = 0; i < 50; i++) {
            rows.add(row("TC-V" + i, "健康用例" + i,
                    new SessionStateDeclaration("healthy", null), "healthy", ExpiryState.VALID));
        }
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(rows, LEAD);
        assertTrue(outcome.refused());
        assertEquals(150, outcome.blockedLines().size(), "全量账目不因渲染截断丢失");
        String message = SessionPreflightSupport.refusalMessage(outcome, LEAD);
        long enumerated = message.lines().filter(l -> l.startsWith("· ")).count();
        assertTrue(enumerated <= 51, "枚举行有上限（50+汇总行），实测 " + enumerated);
        assertTrue(message.contains("共 150 条"), message);
        assertTrue(message.length() < 20_000, "长度有界，实测 " + message.length());
        // Below the cap nothing summarises away: a small refusal still lists every line.
        SessionPreflightOutcome small = SessionPreflightSupport.classify(
                List.of(row("TC-901", "单条", new SessionStateDeclaration("s", null), "s", ExpiryState.EXPIRED)), LEAD);
        assertFalse(SessionPreflightSupport.refusalMessage(small, LEAD).contains("共 1 条"));
    }

    /**
     * The same near-expiry or unknown state shared by many cases surfaces one
     * warning per state, not one per case (warningPhrase rides the start
     * message and must not grow with batch size).
     */
    @Test
    void duplicateWarningsSurfaceOncePerState() {
        List<SessionPreflightRow> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(row("TC-W" + i, "近效期用例" + i,
                    new SessionStateDeclaration("buyer_b", null), "buyer_b", ExpiryState.EXPIRING));
        }
        for (int i = 0; i < 20; i++) {
            rows.add(row("TC-K" + i, "未知效期用例" + i,
                    new SessionStateDeclaration("buyer_c", null), "buyer_c", ExpiryState.UNKNOWN));
        }
        SessionPreflightOutcome outcome = SessionPreflightSupport.classify(rows, LEAD);
        assertFalse(outcome.refused());
        assertEquals(2, outcome.warningLines().size(), "同一登录态只提醒一次不随用例膨胀");
        String phrase = SessionPreflightSupport.warningPhrase(outcome);
        assertTrue(phrase.contains("buyer_b") && phrase.contains("buyer_c"));
    }
}
