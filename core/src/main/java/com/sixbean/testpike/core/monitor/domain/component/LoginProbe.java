package com.sixbean.testpike.core.monitor.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.executor.domain.component.EnvVarsDecryptSupport;
import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import com.sixbean.testpike.core.project.domain.component.TlsAwareHttpSupport;
import com.sixbean.testpike.core.project.domain.component.TlsHttpResult;
import com.sixbean.testpike.core.session.api.dto.SessionAuthHeader;
import com.sixbean.testpike.core.session.domain.component.SessionStateSupport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>Two transport shapes (design doc M1, single component on purpose — a
 * second probe class would fork the failure grammar): the default replays a
 * login POST with the vault-account pair plus any endpoint-declared extra
 * fields; {@code mode=state-read} carries a live login-state ledger row
 * (token or cookie) against a target URL and judges the bare status code,
 * which is how monitored targets whose login is captcha/SSO-gated stay
 * honestly checkable without any captcha handling on our side.
 */
@Component
public class LoginProbe implements MonitorProbe {

    private static final String CRED_PREFIX = "@cred/";
    private static final String ENV_REF_PREFIX = "@env/";
    /**
     * Direct-read mode literal (design doc M1 form two). Public single source:
     * the save gate and this probe must never hold two copies of the same token
     * — a drift between them would make a legal probe shape unsavable (measured:
     * the gate demanded {@code credential} for every LOGIN target, so the
     * state-read form could never be stored).
     */
    public static final String STATE_READ_MODE = "state-read";

    /** probe_config key names, one source for the probe and the save gate. */
    public static final String KEY_MODE = "mode";
    public static final String KEY_URL = "url";
    public static final String KEY_CREDENTIAL = "credential";
    public static final String KEY_STATE_NAME = "stateName";
    public static final String KEY_CHECK_URL = "checkUrl";
    public static final String KEY_EXTRA_FIELDS = "extraFields";

    /** Mode verdict shared by the probe and the save gate (no second comparison). */
    public static boolean isStateReadMode(String mode) {
        return STATE_READ_MODE.equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    private final MonitorProbeSupport support;
    private final TlsAwareHttpSupport tlsHttp;
    private final EnvTlsSupport envTlsSupport;
    private final EnvVarsDecryptSupport envVarsDecryptSupport;
    private final SessionStateSupport sessionStateSupport;

    public LoginProbe(MonitorProbeSupport support, TlsAwareHttpSupport tlsHttp,
                      EnvTlsSupport envTlsSupport, EnvVarsDecryptSupport envVarsDecryptSupport,
                      SessionStateSupport sessionStateSupport) {
        this.support = support;
        this.tlsHttp = tlsHttp;
        this.envTlsSupport = envTlsSupport;
        this.envVarsDecryptSupport = envVarsDecryptSupport;
        this.sessionStateSupport = sessionStateSupport;
    }

    @Override
    public String supportedType() {
        return "LOGIN";
    }

    @Override
    public MonitorProbeResult probe(MonitorTarget target) {
        JsonNode config = support.parseProbeConfig(target.getProbeConfig());
        if (isStateReadMode(config.path(KEY_MODE).asText(""))) {
            return stateRead(target, config);
        }
        return loginSmoke(target, config);
    }

    /** Default shape: replay the login POST (username/password + M1 extra fields). */
    private MonitorProbeResult loginSmoke(MonitorTarget target, JsonNode config) {
        String url = config.path(KEY_URL).asText("").trim();
        String credential = config.path(KEY_CREDENTIAL).asText("").trim();
        if (url.isEmpty()) {
            return MonitorProbeResult.fail(null, "探针配置缺少 url");
        }
        if (!credential.startsWith(CRED_PREFIX)) {
            return MonitorProbeResult.fail(null,
                    "LOGIN 探测凭证必须为 @cred/ 凭据引用，收到「" + credential + "」");
        }
        String credKey = credential.substring(CRED_PREFIX.length());
        String configJson = support.envConfigJson(target.getEnvId());
        Map<String, String> vars = envVarsDecryptSupport.decryptParams(configJson);
        String packed = vars.get(credKey);
        if (packed == null || packed.isBlank()) {
            return MonitorProbeResult.fail(null,
                    "凭据引用「" + credKey + "」在本环境参数中不存在，请先在环境参数登记账号口令");
        }
        int sep = packed.indexOf(':');
        if (sep <= 0 || sep == packed.length() - 1) {
            return MonitorProbeResult.fail(null,
                    "凭据「" + credKey + "」格式应为「账号:口令」，请检查环境参数值");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", packed.substring(0, sep));
        body.put("password", packed.substring(sep + 1));
        String refusal = appendExtraFields(config.path(KEY_EXTRA_FIELDS), vars, body);
        if (refusal != null) {
            return MonitorProbeResult.fail(null, refusal);
        }
        TlsPolicy policy = envTlsSupport.resolvePolicyFailSafe(configJson);
        long start = System.nanoTime();
        try {
            TlsHttpResult r = tlsHttp.postJson(url, body, policy, HttpProbe.PROBE_TIMEOUT_MS);
            int latency = support.elapsedMs(start);
            if (r.status() < 200 || r.status() >= 300) {
                return MonitorProbeResult.fail(latency, "登录冒烟失败：HTTP " + r.status());
            }
            return MonitorProbeResult.ok(latency);
        } catch (Exception e) {
            return MonitorProbeResult.fail(support.elapsedMs(start),
                    "登录冒烟失败：" + support.brief(e));
        }
    }

    /**
     * Merge endpoint-declared extra login fields into the body (design doc M1
     * form one). A {@code @env/<key>} value is resolved from the decrypted
     * environment parameters — one-time material never rides in the
     * screen-visible probe config; anything else is a literal (non-secret
     * statics such as a tenant code). Returns a human-readable refusal naming
     * the missing key, or null when every field resolved.
     */
    private static String appendExtraFields(JsonNode extraFields, Map<String, String> vars,
                                            Map<String, Object> body) {
        if (!extraFields.isObject()) {
            return null;
        }
        var fields = extraFields.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String declared = field.getValue().asText("");
            if (declared.startsWith(ENV_REF_PREFIX)) {
                String envKey = declared.substring(ENV_REF_PREFIX.length());
                String value = vars.get(envKey);
                if (value == null || value.isBlank()) {
                    return "附加字段「" + field.getKey() + "」引用的环境参数「" + envKey
                            + "」不存在或为空——验证码这类一次性字段无法预先配置取值，"
                            + "请改用登录态直读模式（mode=state-read）在登录态账本页选取已采集条目";
                }
                body.put(field.getKey(), value);
            } else {
                body.put(field.getKey(), declared);
            }
        }
        return null;
    }

    /**
     * M1 form two: state-read. No login replay — a live ledger row rides as
     * the request header produced by SessionStateSupport (single source) and
     * the target URL answers by bare status: 2xx/302 = alive. A 401/403 here
     * is reported honestly with the status code only; the secret never enters
     * the cause line (masking pin).
     */
    private MonitorProbeResult stateRead(MonitorTarget target, JsonNode config) {
        String stateName = config.path(KEY_STATE_NAME).asText("").trim();
        String checkUrl = config.path(KEY_CHECK_URL).asText("").trim();
        if (stateName.isEmpty() || checkUrl.isEmpty()) {
            return MonitorProbeResult.fail(null,
                    "登录态直读探测需要 stateName 与 checkUrl 两项配置——stateName 在登录态账本页选取条目，"
                            + "checkUrl 填被测系统的受保护或健康地址（不要填身份提供方域名）");
        }
        long start = System.nanoTime();
        try {
            SessionAuthHeader header = sessionStateSupport.probeAuthHeader(target.getProjectId(), stateName);
            TlsPolicy policy = envTlsSupport.resolvePolicyFailSafe(support.envConfigJson(target.getEnvId()));
            TlsHttpResult r = tlsHttp.getRaw(checkUrl, Map.of(header.name(), header.value()),
                    policy, HttpProbe.PROBE_TIMEOUT_MS);
            int latency = support.elapsedMs(start);
            if ((r.status() >= 200 && r.status() < 300) || r.status() == 302) {
                return MonitorProbeResult.ok(latency);
            }
            return MonitorProbeResult.fail(latency,
                    "登录态直读探测未通过：登录态「" + stateName + "」在被测端点被拒=HTTP " + r.status()
                            + "——若该条目确已过期，请到登录态账本页重新采集");
        } catch (ServiceException refused) {
            return MonitorProbeResult.fail(support.elapsedMs(start), refused.getMessage());
        } catch (Exception e) {
            return MonitorProbeResult.fail(support.elapsedMs(start),
                    "登录态直读探测失败：" + support.brief(e));
        }
    }
}
