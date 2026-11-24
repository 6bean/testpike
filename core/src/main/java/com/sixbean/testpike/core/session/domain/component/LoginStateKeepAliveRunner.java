package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;
import com.sixbean.testpike.core.events.domain.component.ProbeTrailRecorder;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keep-alive round for the login-state ledger.
 *
 * <p>A periodic plain GET with the row's vault material keeps
 * idle-timeout (sliding expiry) sessions alive. The round only owns
 * selection, pacing and circuit bookkeeping - every judgement, header
 * assembly and verified writeback rides the existing single sources
 * (ProbeExchangeRunner.verdictOf/looksLikeLoginShell,
 * SessionStateSupport.vaultReplayHeaders/extraAuthHeaders,
 * LoginStateService.markVerified).
 *
 * <p>Scenario-matrix behaviour pinned by the design ruling:
 * <ul>
 * <li>VALID - verified stamp refreshed, streak cleared;</li>
 * <li>EXPIRED (401/403, 3xx onto a login page, or a 2xx login shell) -
 * double stop: the row is marked verified-invalid AND the per-row
 * keep-alive switch flips itself off with a named stop reason;</li>
 * <li>UNKNOWN/FAILED share the counting channel only: they never void
 * the login state (unreachable is not rejection evidence), they grow
 * the streak, back off exponentially (2x the interval, capped at 4x)
 * and park the keep-alive - not the session - once the bound is hit;</li>
 * <li>rows past their absolute expiry (expires_at) or manually
 * disabled are skipped by the selection predicate with zero outbound
 * requests - no amount of polling rescues a hard exp;</li>
 * <li>plain rounds write nothing to the event ledger; frames land
 * only on stop transitions (kind login-state-keepalive, replayable
 * per-row through the process-detail drawer).</li>
 * </ul>
 *
 * <p>Rotation write-back, automatic re-login and response-content
 * assertions are explicitly out of scope. The business bean is
 * unconditional by construction (the scheduling switch lives in the
 * shell bean only - injected beans must not carry the cron gate).
 */
@Component
public class LoginStateKeepAliveRunner {

    /** Kind token inside the shared event ledger for keep-alive frames. */
    public static final String EVENT_JOB_KIND = "login-state-keepalive";

    private static final Logger log = LoggerFactory.getLogger(LoginStateKeepAliveRunner.class);

    /** Shell of the body text inspected for login-shell markers. */

    /**
     * Due rows: enabled and verified, not absolutely expired, and past
     * (or never stamped for) their effective window =
     * interval(row override else global) * backoff multiplier
     * (1 &lt;&lt; min(streak, 2), i.e. 1x/2x/4x cap) plus a deterministic
     * id-phase jitter (&lt;60s) so a fleet of rows does not fire in one
     * lockstep burst each tick.
     */
    private static final String DUE_SQL =
            "SELECT id, keepalive_url, keepalive_fail_streak FROM tp_login_state "
                    + "WHERE keepalive_enabled = 1 AND status = 1 "
                    + "AND (expires_at IS NULL OR expires_at > now()) "
                    + "AND (last_keepalive_at IS NULL OR last_keepalive_at "
                    + "  + make_interval(mins => (COALESCE(keepalive_interval_minutes, :gi) "
                    + "      * LEAST(4, 1 << LEAST(COALESCE(keepalive_fail_streak, 0), 2)))::int) "
                    + "  + make_interval(secs => (id % 60)::int) <= now()) "
                    + "ORDER BY id LIMIT :cap";

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionGovernanceKeys governanceKeys;
    private final LoginStateService loginStateService;
    private final SessionStateSupport sessionStateSupport;
    private final ProbeExchangeRunner runner;
    private final ProbeTrailRecorder trail;

    public LoginStateKeepAliveRunner(NamedParameterJdbcTemplate jdbc,
                                     SessionGovernanceKeys governanceKeys,
                                     LoginStateService loginStateService,
                                     SessionStateSupport sessionStateSupport,
                                     ProbeExchangeRunner runner,
                                     TaskEventWriter events) {
        this.jdbc = jdbc;
        this.governanceKeys = governanceKeys;
        this.loginStateService = loginStateService;
        this.sessionStateSupport = sessionStateSupport;
        this.runner = runner;
        this.trail = new ProbeTrailRecorder(events, EVENT_JOB_KIND, runner);
    }

    /**
     * One scheduler round. Returns the number of rows probed (0 when the
     * global hot switch parks the round). Business entry shared by the
     * cron shell and the tests, same shape as the retention sweeper.
     */
    public int runRound() {
        if (!governanceKeys.keepaliveEnabled()) {
            return 0;
        }
        int globalInterval = governanceKeys.keepaliveIntervalMinutes();
        MapSqlParameterSource params = new MapSqlParameterSource("gi", globalInterval)
                .addValue("cap", governanceKeys.keepaliveMaxPerTick());
        List<Map<String, Object>> due = jdbc.queryForList(DUE_SQL, params);
        for (Map<String, Object> row : due) {
            long id = ((Number) row.get("id")).longValue();
            Object url = row.get("keepalive_url");
            int streak = ((Number) row.get("keepalive_fail_streak")).intValue();
            try {
                keepAliveOne(id, url == null ? null : String.valueOf(url), streak);
            } catch (RuntimeException broken) {
                // One bad row must not sink the round; the counting channel
                // records the miss exactly like a transport failure.
                log.warn("登录态保活单行异常已隔离（id={}）: {}", id, broken.getMessage());
                applyCounting(id, Verdict.FAILED, streak);
            }
        }
        return due.size();
    }

    private void keepAliveOne(long id, String rawUrl, int streak) {
        String url = ProbeExchangeRunner.normalizedHttpUrl(rawUrl);
        if (url == null) {
            // Unusable address: no outbound request, rides the counting
            // channel (D1 - an address problem is not rejection evidence).
            applyCounting(id, Verdict.FAILED, streak);
            return;
        }
        LoginStateInfo info = loginStateService.get(id);
        Map<String, String> headers;
        try {
            headers = replayHeaders(info);
        } catch (RuntimeException unusable) {
            // Material assembly failed locally: nothing was sent, so this
            // is the same counting channel as a transport failure.
            applyCounting(id, Verdict.FAILED, streak);
            return;
        }
        Verdict verdict;
        try {
            ProbeExchangeRunner.ExchangeResult result = runner.run(
                    "GET", url, headers, null, governanceKeys.validateTimeoutMs());
            verdict = ProbeExchangeRunner.verdictOf(result.statusCode(), result.firstHeader("Location"));
            if (verdict == Verdict.VALID && ProbeExchangeRunner.looksLikeLoginShell(LoginStateBodyShellWindow.text(result))) {
                // 2xx that is really a sign-in page: false green - the
                // same EXPIRED evidence family as 401/403 (ruling section 3).
                verdict = Verdict.EXPIRED;
            }
        } catch (IOException transport) {
            verdict = Verdict.FAILED;
        }
        if (verdict == Verdict.VALID) {
            loginStateService.markVerified(id, true);
            jdbc.update("UPDATE tp_login_state SET last_keepalive_at = now(),"
                            + " last_keepalive_verdict = 'VALID', keepalive_fail_streak = 0,"
                            + " keepalive_stop_reason = NULL, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id));
            return;
        }
        if (verdict == Verdict.EXPIRED) {
            // Double stop: the shared verified door flips the row invalid
            // (all five consumption gates follow), the per-row switch
            // parks itself so polling a dead session stays pointless.
            loginStateService.markVerified(id, false);
            String reason = "保活探测被目标拒绝（" + Verdict.EXPIRED.human()
                    + "），该行已标记为登录失效，保活已自动暂停；请到「登录态账本」该行"
                    + "点「重新采集」或在「编辑」中更新会话材料，核验通过后重新开启保活。";
            jdbc.update("UPDATE tp_login_state SET last_keepalive_at = now(),"
                            + " last_keepalive_verdict = 'EXPIRED', keepalive_enabled = 0,"
                            + " keepalive_stop_reason = :reason, updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id).addValue("reason", reason));
            trail.step(trail.newRunToken(), String.valueOf(id),
                    "保活熔断：目标拒绝会话材料，已置失效并停止保活", reason);
            return;
        }
        applyCounting(id, verdict, streak);
    }

    /**
     * UNKNOWN/FAILED counting channel: streak +1, exponential backoff via
     * the due-window multiplier (capped at 4x), and once the bound is hit
     * the keep-alive parks itself WITHOUT touching the verified status
     * (self-adjudication D1: unreachable is never treated as rejected).
     */
    private void applyCounting(long id, Verdict verdict, int streak) {
        int nextStreak = streak + 1;
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("v", verdict.name())
                .addValue("next", nextStreak);
        if (nextStreak >= governanceKeys.keepaliveMaxFailStreak()) {
            String reason = "目标连续 " + nextStreak + " 轮不可达或返回异常状态，保活已自动暂停"
                    + "（登录状态本身未受影响，仍保持有效）；请确认目标系统可用后，"
                    + "到「登录态账本」该行重新开启保活。";
            params.addValue("reason", reason);
            jdbc.update("UPDATE tp_login_state SET last_keepalive_at = now(),"
                            + " last_keepalive_verdict = :v, keepalive_fail_streak = :next,"
                            + " keepalive_enabled = 0, keepalive_stop_reason = :reason,"
                            + " updated_at = now() WHERE id = :id", params);
            trail.step(trail.newRunToken(), String.valueOf(id),
                    "保活熔断：连续异常达到上限，仅停止保活", reason);
            return;
        }
        jdbc.update("UPDATE tp_login_state SET last_keepalive_at = now(),"
                        + " last_keepalive_verdict = :v, keepalive_fail_streak = :next,"
                        + " updated_at = now() WHERE id = :id", params);
    }

    /**
     * Replay headers for one ledger row, riding the vault single sources
     * (kind grammar + combined-session refs). Keep-alive requires vault
     * material: the legacy bucket-only rows have no periodic-traffic
     * story here and fail into the counting channel with the remedy
     * wording, matching the enable-time pre-check promise of the ruling.
     */
    public Map<String, String> replayHeaders(LoginStateInfo info) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (info.credentialId() != null) {
            String revealed = sessionStateSupport.revealStateValue(info.projectId(), info.stateName());
            if (revealed != null && !revealed.isBlank()) {
                headers.putAll(sessionStateSupport.vaultReplayHeaders(
                        info.kind(), info.stateName(), revealed));
            }
        }
        if (headers.isEmpty()) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    "登录态「" + info.stateName() + "」的保险库里没有可用的会话材料，无法保活；"
                            + "请到「登录态账本」该行重新采集或编辑补录材料后再开启。",
                    com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        }
        if (info.extraAuthRefs() != null && !info.extraAuthRefs().isEmpty()) {
            headers.putAll(sessionStateSupport.extraAuthHeaders(
                    info.projectId(), info.kind(), info.extraAuthRefs()));
        }
        return headers;
    }
}
