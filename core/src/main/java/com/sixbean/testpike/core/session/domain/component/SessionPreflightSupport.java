package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.executor.domain.component.SessionStateDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Single decision point for the login-state preflight (AV3 trigger ①):
 * before an API or UI batch starts, every declared login state is checked
 * against its four-state light so one expired state refuses the whole batch
 * instead of hanging a hundred cases on a login page (U1 wording).
 *
 * <p>The classification consumes only the upstream-computed
 * {@link ExpiryState} (produced via {@code SessionStateSupport.expiryState}
 * with the {@code session.renew-lead-minutes} window) — no expiry parsing or
 * light re-deriving happens here (red line 6, second implementation forbidden).
 *
 * <p>Honest boundaries: expired and unresolved declarations block; near-expiry
 * and validity-unknown states only warn (execution stays allowed, a 401/403
 * during the run flips the light invalid immediately). Validity is judged
 * against the protected pages of the system under test, never the identity
 * provider's login pages, so an SSO redirect chain is not misread as a dead
 * session.
 *
 * <p>Static pure decision component, mirroring the batch precheck precedent:
 * callers feed config values in, this class owns no storage.
 */
public final class SessionPreflightSupport {

    /**
     * Rendering bound for the refusal listing (worst-scale S-group): the
     * outcome keeps every blocked line for the ledger count, the toast only
     * shows the first N plus a summary. Mirrors the TraceChain precedent of
     * capping a displayed listing while carrying the true total.
     */
    static final int MAX_ENUMERATED_BLOCKED_LINES = 50;

    private SessionPreflightSupport() {
    }

    /**
     * Sweep the batch rows into blocked/warning listings. Rows without a
     * sessionState declaration (legacy cases) are ignored so existing batches
     * keep starting unchanged.
     */
    public static SessionPreflightOutcome classify(List<SessionPreflightRow> rows,
                                                   int renewLeadMinutes) {
        List<String> blocked = new ArrayList<>();
        // LinkedHashSet: the same state shared by many cases warns once, not
        // once per case — the notice rides the start message and must stay
        // bounded at batch scale.
        LinkedHashSet<String> warningSet = new LinkedHashSet<>();
        if (rows == null) {
            return new SessionPreflightOutcome(blocked, new ArrayList<>(warningSet));
        }
        for (SessionPreflightRow row : rows) {
            if (row == null || row.declaration() == null) {
                continue;   // no declaration = not in scope of this preflight
            }
            if (row.resolvedStateName() == null || row.expiryState() == null) {
                blocked.add(row.caseNo() + "「" + row.title() + "」声明的登录态（"
                        + selectorText(row.declaration()) + "）没有可解析的有效条目");
                continue;
            }
            switch (row.expiryState()) {
                case EXPIRED -> blocked.add(row.caseNo() + "「" + row.title()
                        + "」引用的登录态「" + row.resolvedStateName() + "」已过期");
                case EXPIRING -> warningSet.add("登录态「" + row.resolvedStateName()
                        + "」即将到期（提醒提前量当前 " + Math.max(0, renewLeadMinutes) + " 分钟）");
                case UNKNOWN -> warningSet.add("登录态「" + row.resolvedStateName()
                        + "」的有效期无法识别，按未知处理，执行中命中 401/403 会即时标记失效");
                case VALID -> { /* comfortable — no listing */ }
            }
        }
        return new SessionPreflightOutcome(blocked, new ArrayList<>(warningSet));
    }

    /**
     * Plain-language refusal for the batch start gate: names each expired or
     * unresolved login state with the case that asked for it, points to the
     * ledger page for re-capture/renewal and to the config page plus the key
     * name for the renew-lead window, and states the verification target so
     * an SSO redirect chain is not mistaken for a dead session (D11).
     *
     * @return null when the outcome is not a refusal (callers only surface it
     *         on the blocked branch)
     */
    public static String refusalMessage(SessionPreflightOutcome outcome, int renewLeadMinutes) {
        if (outcome == null || !outcome.refused()) {
            return null;
        }
        StringBuilder text = new StringBuilder("以下用例声明的登录态已失效或无法解析，批次不会启动：");
        List<String> blocked = outcome.blockedLines();
        int shown = Math.min(blocked.size(), MAX_ENUMERATED_BLOCKED_LINES);
        for (int i = 0; i < shown; i++) {
            text.append('\n').append("· ").append(blocked.get(i));
        }
        if (blocked.size() > shown) {
            text.append('\n').append("· …… 其余 ").append(blocked.size() - shown)
                    .append(" 条同类问题，共 ").append(blocked.size())
                    .append(" 条，批次均不会启动");
        }
        text.append("\n请前往「登录态账本」页面为对应账号重新采集或续期后重试；"
                + "即将到期的提醒提前量可在「系统管理 → 系统配置」按参数 "
                + SessionGovernanceKeys.KEY_RENEW_LEAD_MINUTES
                + "（当前 " + Math.max(0, renewLeadMinutes) + " 分钟）调整，保存后即时生效。"
                + "有效性判断针对被测系统的受保护页面地址，不会把统一身份认证页面的正常响应误判为会话失效。");
        return text.toString();
    }

    /**
     * Non-blocking notice phrase for the batch start result: near-expiry and
     * validity-unknown warnings surface as one plain-language clause instead of
     * being silently swallowed. Returns an empty string when there is nothing
     * to notice — warnings never intercept the run.
     */
    public static String warningPhrase(SessionPreflightOutcome outcome) {
        if (outcome == null || outcome.warningLines() == null || outcome.warningLines().isEmpty()) {
            return "";
        }
        return "；登录态提醒：" + String.join("；", outcome.warningLines());
    }

    private static String selectorText(SessionStateDeclaration declaration) {
        if (declaration.name() != null && !declaration.name().isBlank()) {
            return "名称 " + declaration.name();
        }
        return "角色 " + declaration.roleScope();
    }
}
