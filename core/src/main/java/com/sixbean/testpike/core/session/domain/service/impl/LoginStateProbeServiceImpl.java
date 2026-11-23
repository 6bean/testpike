package com.sixbean.testpike.core.session.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;
import com.sixbean.testpike.core.events.domain.component.ProbeTrailRecorder;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.domain.component.SessionStateSupport;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ledger-row session replay against one target origin, fully echoed to the
 * process-event tail (see LoginStateProbeService for the contract).
 *
 * <p>Red lines held: snapshot values are NEVER printed (names and lengths
 * only), the redirect-free transport keeps login bounces observable, the
 * verified stamp moves only on a 2xx answer, and a stale row fails loudly
 * instead of silently reporting.
 */
@Service
public class LoginStateProbeServiceImpl implements LoginStateProbeService {

    /** Kind token inside the shared event ledger. */
    public static final String EVENT_JOB_KIND = "login-state-probe";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String TIMEOUT_KEY = "session.probe.timeout-ms";
    private static final int TIMEOUT_DEFAULT_MS = 10000;

    private final LoginStateService loginStateService;
    private final StorageRouter storageRouter;
    private final SessionStateSupport sessionStateSupport;
    private final ProbeTrailRecorder trail;
    private final ProbeExchangeRunner runner;
    private final ConfigService configService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LoginStateProbeServiceImpl(LoginStateService loginStateService, StorageRouter storageRouter,
                                      SessionStateSupport sessionStateSupport,
                                      TaskEventWriter events, ProbeExchangeRunner runner,
                                      ConfigService configService, NamedParameterJdbcTemplate jdbc) {
        this.loginStateService = loginStateService;
        this.storageRouter = storageRouter;
        this.sessionStateSupport = sessionStateSupport;
        this.trail = new ProbeTrailRecorder(events, EVENT_JOB_KIND, runner);
        this.runner = runner;
        this.configService = configService;
        this.jdbc = jdbc;
    }

    @Override
    public ProbeResult probe(long id, String targetUrl) {
        return probe(id, targetUrl, null, null);
    }

    /** Accepted request shapes for the probe door (same whitelist family as the vault test door). */
    static final java.util.Set<String> PROBE_METHODS = java.util.Set.of("GET", "POST", "PUT");

    @Override
    public ProbeResult probe(long id, String targetUrl, String method, String bodyTemplate) {
        LoginStateInfo info = loginStateService.get(id);
        String token = trail.newRunToken();
        String ref = String.valueOf(id);
        String verb = method == null || method.isBlank()
                ? "GET" : method.strip().toUpperCase(java.util.Locale.ROOT);
        if (!PROBE_METHODS.contains(verb)) {
            return fail(token, ref, "请求方式不支持",
                    "只接受 GET / POST / PUT，收到：" + method
                            + "——请在「立即测试」对话框的请求方式里选择其一。");
        }
        trail.step(token, ref, "探测开始：" + info.stateName() + "（" + info.kind() + "）",
                "项目 #" + info.projectId()
                        + "\n形态: " + info.kind()
                        + "\n声明过期: " + (info.expiresAt() == null ? "未声明" : info.expiresAt().format(TS))
                        + "\n上次验证: " + (info.lastVerifiedAt() == null ? "从未" : info.lastVerifiedAt().format(TS)));

        String url = ProbeExchangeRunner.normalizedHttpUrl(targetUrl);
        String urlSource = "页面指定的地址";
        if (targetUrl != null && !targetUrl.isBlank() && url == null) {
            return fail(token, ref, "地址格式不可用", "只接受 http:// 或 https:// 开头的地址，收到：" + targetUrl);
        }
        if (url == null) {
            url = firstHttpOrigin(info.scopeOrigins());
            urlSource = "账本作用源列表（scope origins）的第一条";
        }
        if (url == null) {
            return fail(token, ref, "找不到探测目标地址",
                    "这一行没有登记作用源（scope origins 为空），请在表单里手动填写目标地址后再试。");
        }
        trail.step(token, ref, "探测目标：" + url, "地址来源：" + urlSource
                + "\n作用源全集: " + (info.scopeOrigins() == null || info.scopeOrigins().isEmpty()
                ? "（无）" : String.join(", ", info.scopeOrigins())));

        // Request-body template (11-19 user command): GET never carries a body
        // (protocol semantics, same treatment as the vault test door — hint
        // on the trail, body dropped, nothing else changes). Placeholders are
        // counted through the shared injector grammar; the SEND-time fill goes
        // through the same vault materialisation as combined headers, and the
        // trail only ever echoes the template source with placeholders intact.
        String bodyTemplateSource = bodyTemplate == null ? "" : bodyTemplate;
        if ("GET".equals(verb) && !bodyTemplateSource.isEmpty()) {
            trail.step(token, ref, "提示：GET 请求不携带请求体",
                    "表单里的请求体内容本次被忽略（协议语义）。需要携带请求体的接口请把请求方式改为 POST 或 PUT。");
            bodyTemplateSource = "";
        }
        int bodyReferences = 0;
        for (String credKey : com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector
                .extractCredReferenceKeys(bodyTemplateSource)) {
            bodyReferences += com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector
                    .countReferencesTo(bodyTemplateSource, credKey);
        }
        String bodyToSend = null;
        if (!bodyTemplateSource.isEmpty()) {
            try {
                bodyToSend = sessionStateSupport.materialiseTemplate(info.projectId(), bodyTemplateSource);
            } catch (ServiceException unresolved) {
                return fail(token, ref, "请求体引用无法装配", unresolved.getMessage());
            }
        }

        // Material loading : the vault is the primary source because
        // both write channels (manual registration and capture commit) store
        // their material there; the bucket object is only a legacy
        // compatibility read point and is consulted when no vault value
        // exists. A row with neither source fails with the remedy path named.
        Map<String, String> headers = new LinkedHashMap<>();
        String materialSource = null;
        if (info.credentialId() != null) {
            String revealed = sessionStateSupport.revealStateValue(info.projectId(), info.stateName());
            if (revealed != null && !revealed.isBlank()) {
                try {
                    headers.putAll(sessionStateSupport.vaultReplayHeaders(
                            info.kind(), info.stateName(), revealed));
                    materialSource = "保险库凭据（credKey=" + info.credKey() + "）";
                } catch (ServiceException unusable) {
                    return fail(token, ref, "会话材料无法解析", unusable.getMessage());
                }
            }
        }
        if (materialSource == null) {
            if (info.stateObjectKey() == null || info.stateObjectKey().isBlank()) {
                return fail(token, ref, "没有可重放的会话材料",
                        "登录态「" + info.stateName() + "」的保险库与快照桶里都没有会话材料——"
                                + "请在「登录态账本」重新登记（填写凭据值）或到采集页完成一次登录采集，再对本行点「立即测试」。");
            }
            byte[] rawState;
            try {
                rawState = storageRouter.systemProvider().get(info.stateObjectKey());
            } catch (RuntimeException unreadable) {
                return fail(token, ref, "快照对象读取失败",
                        "对象存储不可达或对象已不存在：" + unreadable.getMessage() + "\n（可先跑「存储连通测试」定位对象存储链路）");
            }
            try {
                buildAuthHeaders(info.kind(), rawState, headers);
            } catch (ServiceException malformed) {
                return fail(token, ref, "快照内容无法解析", malformed.getMessage());
            }
            materialSource = "桶快照（旧形态兼容读点）";
        }
        trail.step(token, ref, "会话材料：" + materialSource,
                "取值形态=" + info.kind() + "；值不回显（明文不进事件流）");

        // dispatch assembly: the combined-session refs ride AFTER the
        // kind primary header, each value decrypted from the vault at this
        // instant (audited single door). These rows are secret material no
        // matter their header names, so they join the mask+seal set and the
        // plaintext never reaches the event-stream body.
        java.util.Set<String> extraSecretNames = java.util.Set.of();
        if (info.extraAuthRefs() != null && !info.extraAuthRefs().isEmpty()) {
            try {
                Map<String, String> extras = sessionStateSupport.extraAuthHeaders(
                        info.projectId(), info.kind(), info.extraAuthRefs());
                headers.putAll(extras);
                extraSecretNames = extras.keySet().stream()
                        .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                trail.step(token, ref, "附加会话头：" + extras.size() + " 条已随派发注入",
                        "头名=" + String.join(", ", extras.keySet())
                                + "；实值只进瞬时请求，不进事件流正文（保险库派发前解密，解显走受门审计）");
            } catch (ServiceException unresolved) {
                return fail(token, ref, "附加会话头无法装配", unresolved.getMessage());
            }
        }

        // Same protocol default as the vault test door: body-carrying verbs
        // ride JSON (a user-registered Content-Type combined header wins).
        if (!"GET".equals(verb)) {
            headers.putIfAbsent("Content-Type", "application/json");
        }

        ProbeExchangeRunner.TrailPiece requestTrail = runner.renderRequestTrail(verb, url, headers, extraSecretNames);
        String requestDetail = requestTrail.text()
                + (bodyTemplateSource.isEmpty() ? ""
                        : "\n请求体（模板原文，" + bodyReferences + " 处保险库引用）:\n" + bodyTemplateSource)
                + "\n协议语义：浏览器会话快照重放，跟随重定向已关闭（3xx 落点原样呈现）";
        trail.step(token, ref, "构造请求：" + verb + " " + url, requestDetail, requestTrail.sealed());

        int timeoutMs = timeoutMs();
        ProbeExchangeRunner.ExchangeResult result;
        try {
            result = runner.run(verb, url, headers, bodyToSend, timeoutMs);
        } catch (IOException transport) {
            trail.transportError(token, ref, verb + " " + url, transport, timeoutMs);
            return closed(token, ref, Verdict.FAILED);
        }

        String location = result.firstHeader("Location");
        Verdict verdict = trail.responseStepAndVerdict(token, ref, result);
        String note = switch (verdict) {
            case VALID -> "会话在目标处放行（2xx）。注意：若该地址本身免登录，请换受保护资源地址复核。";
            case EXPIRED -> "判定为已失效；请在采集页重新登录采集，账本会自动更新。";
            case UNKNOWN -> "目标返回了非决定性状态（" + result.statusCode()
                    + (location == null ? "" : "，Location=" + location) + "），请人工核对被测系统。";
            case FAILED -> "";
        };
        trail.step(token, ref, "判定：" + verdict.human(), note);
        if (verdict == Verdict.VALID) {
            jdbc.update("UPDATE tp_login_state SET last_verified_at = now(), updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id));
        }
        return closed(token, ref, verdict);
    }

    private ProbeResult fail(String token, String ref, String summary, String detail) {
        trail.error(token, ref, summary, detail);
        return closed(token, ref, Verdict.FAILED);
    }

    private ProbeResult closed(String token, String ref, Verdict verdict) {
        trail.step(token, ref, "探测结束：" + verdict.human(), null);
        return new ProbeResult(token, verdict);
    }

    private static String firstHttpOrigin(List<String> origins) {
        if (origins == null) {
            return null;
        }
        for (String origin : origins) {
            String url = ProbeExchangeRunner.normalizedHttpUrl(origin);
            if (url != null) {
                return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            }
        }
        return null;
    }

    /**
     * Legacy bucket-snapshot parser (compatibility read point only — new
     * writes never touch the bucket column). Turns a snapshot object into
     * real request headers. The trail echo itself comes from the runner's
     * single-source request rendering, so this door no longer keeps its own
     * masked-string copy. Handles all three ledger kinds; throws when the
     * snapshot yields nothing replayable.
     */
    private void buildAuthHeaders(String kind, byte[] rawState, Map<String, String> headers) {
        String content = new String(rawState, StandardCharsets.UTF_8).strip();
        if (content.isEmpty()) {
            throw new ServiceException("快照对象内容为空，无法构造会话头。", BizErrorCode.VALIDATION_ERROR);
        }
        if ("token".equals(kind)) {
            String token = content.startsWith("{") ? jsonText(content, "token") : content;
            headers.put("Authorization", "Bearer " + token);
            return;
        }
        StringBuilder real = new StringBuilder();
        for (String[] pair : cookiePairs(content, kind)) {
            if (real.length() > 0) {
                real.append("; ");
            }
            real.append(pair[0]).append('=').append(pair[1]);
        }
        headers.put("Cookie", real.toString());
    }

    /** Cookie name/value pairs from either Playwright snapshot or cookie lists. */
    private List<String[]> cookiePairs(String content, String kind) {
        List<String[]> pairs = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode cookies = root.isArray() ? root : root.path("cookies");
            for (JsonNode cookie : cookies) {
                String name = cookie.path("name").asText("");
                String value = cookie.path("value").asText("");
                if (!name.isEmpty() && !value.isEmpty()) {
                    pairs.add(new String[]{name, value});
                }
            }
        } catch (Exception unparsable) {
            if ("cookie_set".equals(kind)) {
                // legacy raw form: "k1=v1; k2=v2"
                for (String piece : content.split(";")) {
                    String[] pair = piece.strip().split("=", 2);
                    if (pair.length == 2 && !pair[0].isEmpty() && !pair[1].isEmpty()) {
                        pairs.add(new String[]{pair[0], pair[1]});
                    }
                }
            }
        }
        if (pairs.isEmpty()) {
            throw new ServiceException("快照里解析不出任何 cookie（形态=" + kind
                    + "），请确认采集内容或改用页面指定地址＋手工核对。", BizErrorCode.VALIDATION_ERROR);
        }
        return pairs;
    }

    private String jsonText(String content, String field) {
        try {
            String value = objectMapper.readTree(content).path(field).asText("");
            if (value.isEmpty()) {
                throw new ServiceException("快照 JSON 里缺少 " + field + " 字段。", BizErrorCode.VALIDATION_ERROR);
            }
            return value;
        } catch (ServiceException explicit) {
            throw explicit;
        } catch (Exception malformed) {
            throw new ServiceException("token 快照不是可解析的 JSON 或明文。", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private int timeoutMs() {
        try {
            return Math.max(1000, Integer.parseInt(
                    configService.getValue(TIMEOUT_KEY, String.valueOf(TIMEOUT_DEFAULT_MS)).strip()));
        } catch (NumberFormatException drift) {
            return TIMEOUT_DEFAULT_MS;
        }
    }
}
