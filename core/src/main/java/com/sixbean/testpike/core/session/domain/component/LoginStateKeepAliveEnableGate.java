package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;
import com.sixbean.testpike.core.events.domain.component.ProbeTrailRecorder;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Enable door for per-row keep-alive.
 *
 * <p>Flipping a row's keep-alive on (or on again after the address changed)
 * runs a synchronous double GET pre-check against the target so a public,
 * login-exempt address can never be enrolled: the first request carries the
 * row's vault material, the second carries nothing at all. A target that
 * answers both rounds with plain 2xx content does not check the session -
 * polling it would "keep alive" a login the target never verifies, and every
 * verdict of the round would be a false green. The verdict family, header
 * assembly and the verified writeback all ride the existing single sources
 * (design ruling sections 5 and 3): nothing here re-implements them.
 *
 * <p>Refusals never half-write: the switch only flips when the pre-check
 * allows it. The one refusal that DOES write is rejected material - it is
 * EXPIRED evidence of the same family the manual "test now" door already
 * feeds into the single {@code markVerified} path, so the light and the five
 * consumption gates stay honest. An unreachable target never voids the row
 * (self-adjudication D1: unreachable is not rejection evidence).
 *
 * <p>Allowed rounds stamp the row with the pre-check verdict so the first
 * scheduled round waits a full interval instead of firing twice in quick
 * succession, and write one event frame: enable and disable are transitions
 * (ruling D8), plain refusals are not.
 */
@Component
public class LoginStateKeepAliveEnableGate {

    /** Row override window, same bounds the global cadence key enforces. */
    private static final int INTERVAL_FLOOR_MINUTES = 5;
    private static final int INTERVAL_CEILING_MINUTES = 1440;

    /** Shell of the body text inspected for login-shell markers. */

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionGovernanceKeys governanceKeys;
    private final LoginStateService loginStateService;
    private final LoginStateKeepAliveRunner keepAliveRunner;
    private final ProbeExchangeRunner runner;
    private final ProbeTrailRecorder trail;

    public LoginStateKeepAliveEnableGate(NamedParameterJdbcTemplate jdbc,
                                         SessionGovernanceKeys governanceKeys,
                                         LoginStateService loginStateService,
                                         LoginStateKeepAliveRunner keepAliveRunner,
                                         ProbeExchangeRunner runner,
                                         TaskEventWriter events) {
        this.jdbc = jdbc;
        this.governanceKeys = governanceKeys;
        this.loginStateService = loginStateService;
        this.keepAliveRunner = keepAliveRunner;
        this.runner = runner;
        this.trail = new ProbeTrailRecorder(events,
                LoginStateKeepAliveRunner.EVENT_JOB_KIND, runner);
    }

    /**
     * Apply the per-row keep-alive switch. Throws ServiceException with the
     * named remedy when the pre-check refuses the enable; silently lands the
     * row change when it passes or when the caller flips the switch off.
     */
    public void apply(long id, boolean enabled, String url, Integer intervalMinutes) {
        if (intervalMinutes != null
                && (intervalMinutes < INTERVAL_FLOOR_MINUTES
                    || intervalMinutes > INTERVAL_CEILING_MINUTES)) {
            throw new ServiceException("保活间隔必须留空（随全局设置）或介于 "
                    + INTERVAL_FLOOR_MINUTES + "~" + INTERVAL_CEILING_MINUTES
                    + " 分钟之间，当前值 " + intervalMinutes
                    + " 超出区间；请在该行「编辑」的保活设置中修改后重新保存。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        LoginStateInfo info = loginStateService.get(id);
        if (!enabled) {
            disable(id);
            return;
        }
        String target = ProbeExchangeRunner.normalizedHttpUrl(url);
        if (target == null) {
            target = ProbeExchangeRunner.normalizedHttpUrl(
                    jdbc.queryForObject("SELECT keepalive_url FROM tp_login_state WHERE id = :id",
                            new MapSqlParameterSource("id", id), String.class));
        }
        if (target == null) {
            throw new ServiceException("该行缺少保活地址，无法开启保活；请先在该行「编辑」的保活设置"
                    + "中填写以 http:// 或 https:// 开头的目标资源地址并保存，再重新开启。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Header assembly rides the round's single source (missing material
        // refuses here with the re-capture remedy already named there).
        Map<String, String> headers = keepAliveRunner.replayHeaders(info);
        Verdict verdict;
        try {
            ProbeExchangeRunner.ExchangeResult withMaterial = runner.run(
                    "GET", target, headers, null, governanceKeys.validateTimeoutMs());
            verdict = ProbeExchangeRunner.verdictOf(
                    withMaterial.statusCode(), withMaterial.firstHeader("Location"));
            if (verdict == Verdict.VALID
                    && ProbeExchangeRunner.looksLikeLoginShell(LoginStateBodyShellWindow.text(withMaterial))) {
                verdict = Verdict.EXPIRED;
            }
        } catch (IOException transport) {
            verdict = Verdict.FAILED;
        }
        if (verdict == Verdict.FAILED) {
            throw new ServiceException("保活预检没有够到目标（超时或连接失败），已拒绝开启；"
                    + "该行登录状态未受影响，请确认目标系统可达后重新开启保活，"
                    + "地址有误可在该行「编辑」的保活设置中修改。", BizErrorCode.VALIDATION_ERROR);
        }
        if (verdict == Verdict.EXPIRED) {
            loginStateService.markVerified(id, false,
                    "保活开启预检被拒：目标拒绝了会话材料，已标记登录失效");
            throw new ServiceException("保活开启预检被拒：会话材料被目标拒绝（未授权或返回登录页），"
                    + "已拒绝开启并将该行标记为登录失效；请到「登录态账本」该行点击「重新采集」"
                    + "或在「编辑」中更新会话材料，核验通过后重新开启保活。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (verdict == Verdict.UNKNOWN) {
            // Neither rejection nor a usable green: enroll with the counting
            // rhythm already in place; the material-free round proves nothing
            // about protection status for an odd answer.
            allow(id, target, intervalMinutes, "UNKNOWN",
                    "预检未能定论（目标返回异常状态或非常规跳转），保活已开启并按失败重试节奏运行；"
                            + "连续异常达到上限会自动暂停保活（不影响登录状态本身）。");
            return;
        }
        // Second round: the same address without any session material.
        if (answersPublicly(target)) {
            throw new ServiceException("该保活地址不校验登录态：携带会话材料与完全不带材料的两次请求"
                    + "都返回正常内容，说明资源是公开的，不能用于保活判定；请改为目标上需要登录才可"
                    + "访问的资源地址（不带材料时应返回未授权或跳转登录页），在该行「编辑」的保活设置"
                    + "中保存后再开启。", BizErrorCode.VALIDATION_ERROR);
        }
        allow(id, target, intervalMinutes, "VALID",
                "预检通过：目标校验会话材料，保活已开启。");
    }

    /**
     * Material-free GET. True only on the hard exemption evidence named by
     * the ruling - a plain 2xx that is not a login shell. Anything else
     * (401/403, login bounce, login shell, odd answer, transport miss)
     * either proves the resource checks the session or proves nothing,
     * and never blocks the enable on its own.
     */
    private boolean answersPublicly(String target) {
        ProbeExchangeRunner.ExchangeResult anonymous;
        try {
            anonymous = runner.run("GET", target, Map.of(), null,
                    governanceKeys.validateTimeoutMs());
        } catch (IOException transport) {
            return false;
        }
        Verdict anonymousVerdict = ProbeExchangeRunner.verdictOf(
                anonymous.statusCode(), anonymous.firstHeader("Location"));
        return anonymousVerdict == Verdict.VALID
                && !ProbeExchangeRunner.looksLikeLoginShell(LoginStateBodyShellWindow.text(anonymous));
    }

    private void allow(long id, String target, Integer intervalMinutes, String stamp, String note) {
        jdbc.update("UPDATE tp_login_state SET keepalive_enabled = 1, keepalive_url = :url,"
                        + " keepalive_interval_minutes = :iv, keepalive_fail_streak = 0,"
                        + " keepalive_stop_reason = NULL, last_keepalive_at = now(),"
                        + " last_keepalive_verdict = :stamp, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id)
                        .addValue("url", target)
                        .addValue("iv", intervalMinutes)
                        .addValue("stamp", stamp));
        trail.step(trail.newRunToken(), String.valueOf(id),
                "保活已开启（预检完成）", note);
    }

    /** Manual flip-off: no traffic, the switch and any old stop reason clear. */
    private void disable(long id) {
        Integer current = jdbc.queryForObject(
                "SELECT keepalive_enabled FROM tp_login_state WHERE id = :id",
                new MapSqlParameterSource("id", id), Integer.class);
        jdbc.update("UPDATE tp_login_state SET keepalive_enabled = 0,"
                        + " keepalive_stop_reason = NULL, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (current != null && current == 1) {
            trail.step(trail.newRunToken(), String.valueOf(id),
                    "保活已人工关闭",
                    "用户手动关闭该行保活，调度器不再对其外发请求；此前的自动停止原因已清除，"
                            + "重新开启时将重新执行预检。");
        }
    }
}
