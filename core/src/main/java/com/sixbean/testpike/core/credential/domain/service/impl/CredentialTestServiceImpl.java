package com.sixbean.testpike.core.credential.domain.service.impl;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.credential.api.dto.CredentialInfo;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestHeaderInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestGrade;
import com.sixbean.testpike.core.credential.api.dto.CredentialTestOutcome;
import com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.credential.domain.service.CredentialTestService;
import com.sixbean.testpike.core.events.domain.component.ProbeExchangeRunner;
import com.sixbean.testpike.core.events.domain.component.ProbeTrailRecorder;
import com.sixbean.testpike.core.events.domain.component.TaskEventWriter;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.ProbeResult;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Credential vault "test now" door (see CredentialTestService).
 *
 * <p>Honest scope: the platform does not invent a login protocol. The user
 * supplies the target address plus an optional body template; the single
 * placeholder {{credential}} is filled at send time from the vault, and the
 * trail only ever shows the template - never the material itself. When the
 * credential is already bound to a login state, its recorded origin serves
 * as the pre-filled default target.
 */
@Service
public class CredentialTestServiceImpl implements CredentialTestService {

    /** Kind token inside the shared event ledger. */
    public static final String EVENT_JOB_KIND = "credential-test";
    /** Placeholder family: the vault's step syntax {{CRED:<credKey>}}. */
    public static final String PLACEHOLDER_SHAPE = "{{CRED:引用键}}";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT");
    private static final String TIMEOUT_KEY = "credential.test.timeout-ms";
    private static final int TIMEOUT_DEFAULT_MS = 10000;
    /** Hot knob : degrade a 200 login-shell answer to UNKNOWN. */
    static final String FINGERPRINT_KEY = "credential.test.login-fingerprint.enabled";

    private final CredentialService credentialService;
    private final ProbeTrailRecorder trail;
    private final ProbeExchangeRunner runner;
    private final ConfigService configService;
    private final NamedParameterJdbcTemplate jdbc;

    public CredentialTestServiceImpl(CredentialService credentialService, TaskEventWriter events,
                                     ProbeExchangeRunner runner, ConfigService configService,
                                     NamedParameterJdbcTemplate jdbc) {
        this.credentialService = credentialService;
        this.trail = new ProbeTrailRecorder(events, EVENT_JOB_KIND, runner);
        this.runner = runner;
        this.configService = configService;
        this.jdbc = jdbc;
    }

    @Override
    public CredentialTestOutcome test(long credentialId, CredentialTestInput input) {
        CredentialInfo info = credentialService.get(credentialId);
        String token = trail.newRunToken();
        String ref = String.valueOf(credentialId);
        trail.step(token, ref, "凭据验证开始：" + info.name(),
                "项目 #" + info.projectId() + "\n引用键: " + info.credKey()
                        + "\n状态: " + (info.status() != null && info.status() == 1 ? "启用" : "停用")
                        + "\n口令回显: 恒打码（" + (info.secretMask() == null ? "无" : info.secretMask()) + "）");

        String method = input.method() == null ? "POST" : input.method().strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return fail(token, ref, "请求方式不支持", "只接受 GET / POST / PUT，收到：" + input.method());
        }
        String credKey = info.credKey() == null ? "" : info.credKey();

        // Reference surface : repeatable header rows join the URL and
        // the body template as first-class places the vault material rides on.
        Map<String, String> headerTemplates = new LinkedHashMap<>();
        List<CredentialTestHeaderInput> rows =
                input.headers() == null ? List.of() : input.headers();
        int rowIndex = 0;
        for (CredentialTestHeaderInput row : rows) {
            rowIndex++;
            String name = row.name() == null ? "" : row.name().strip();
            if (name.isEmpty() || name.indexOf(':') >= 0
                    || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                return fail(token, ref, "第 " + rowIndex + " 行请求头名称不可用",
                        "名称「" + name + "」不能为空、不能含冒号或换行；请改为标头名"
                                + "（如 Authorization）后重试，或删掉这一行。");
            }
            headerTemplates.put(name, row.value() == null ? "" : row.value());
        }

        String template = input.bodyTemplate() == null ? "" : input.bodyTemplate();
        if ("GET".equals(method) && !template.isEmpty()) {
            trail.step(token, ref, "提示：GET 请求不携带请求体", "表单里的请求体内容本次被忽略（协议语义）。"
                    + "凭据可以通过「请求头」区随 GET 发出。");
            template = "";
        }

        // Address may itself carry the placeholder (three-surface reference):
        // resolve the template source first, count and inject BEFORE the URL
        // shape validation (raw braces are not a URI error, they are a slot).
        String urlTemplate;
        String urlSource;
        if (input.url() != null && !input.url().isBlank()) {
            urlTemplate = input.url().strip();
            urlSource = "页面填写的目标地址";
        } else {
            urlTemplate = boundLoginStateOrigin(credentialId);
            urlSource = "绑定的登录态账本作用源（最近更新的一条）";
        }
        if (urlTemplate == null) {
            return fail(token, ref, "找不到验证目标地址",
                    "该凭据还没有可参照的登录态绑定，请在表单里填写目标系统的登录或受保护地址"
                            + "（http/https 开头）后再试。");
        }

        // Three-surface reconciliation : URL + headers + body count
        // against ONE number via the shared injector grammar (red line: no
        // second regex family for the same dialect).
        java.util.regex.Pattern injectPattern = java.util.regex.Pattern.compile(
                "\\{\\{CRED:" + java.util.regex.Pattern.quote(credKey) + "\\}\\}");
        int injections = CredPlaceholderInjector.countReferencesTo(urlTemplate, credKey);
        for (String value : headerTemplates.values()) {
            injections += CredPlaceholderInjector.countReferencesTo(value, credKey);
        }
        injections += CredPlaceholderInjector.countReferencesTo(template, credKey);

        // The reveal door carries the per-use audit stamp (frozen rule: every
        // decryption leaves a trace); only templates are ever shown on screen.
        String secret = injections > 0 ? credentialService.revealSecret(credentialId) : null;
        String url = ProbeExchangeRunner.normalizedHttpUrl(
                secret == null ? urlTemplate
                        : injectPattern.matcher(urlTemplate)
                                .replaceAll(java.util.regex.Matcher.quoteReplacement(secret)));
        if (url == null) {
            return fail(token, ref, "地址格式不可用",
                    "目标地址需要以 http:// 或 https:// 开头（占位符会在发送前填好，不影响格式），"
                            + "收到：" + urlTemplate + "。请修正地址后重试。");
        }
        trail.step(token, ref, "验证目标：" + url, "地址来源：" + urlSource);

        String body = secret == null ? template
                : injectPattern.matcher(template)
                        .replaceAll(java.util.regex.Matcher.quoteReplacement(secret));
        Map<String, String> headers = new LinkedHashMap<>();
        if (!"GET".equals(method)) {
            headers.put("Content-Type", "application/json");
        }
        java.util.Set<String> secretHeaderNames = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
            String rendered = secret == null ? entry.getValue()
                    : injectPattern.matcher(entry.getValue())
                            .replaceAll(java.util.regex.Matcher.quoteReplacement(secret));
            if (CredPlaceholderInjector.countReferencesTo(entry.getValue(), credKey) > 0) {
                secretHeaderNames.add(entry.getKey().toLowerCase(Locale.ROOT));
            }
            headers.put(entry.getKey(), rendered);
        }
        // fail-closed honesty: any leftover {{CRED:...}} targets a foreign key -
        // this door only ever tests the current row's credential.
        if (CredPlaceholderInjector.containsCredReference(
                java.util.List.of(new java.util.ArrayList<>(headers.values()),
                        body, url))) {
            return fail(token, ref, "引用了其他凭据，本次未发送",
                    "请求头/请求体里引用了非本行凭据的键（立即测试只检验当前凭据行「" + credKey
                            + "」）。修法：把引用键改为本行引用键，或到「API 测试」用 bearer 认证引用该凭据后重试。");
        }

        ProbeExchangeRunner.TrailPiece requestTrail = runner.renderRequestTrail(
                method, url, headers, secretHeaderNames);
        StringBuilder requestEcho = new StringBuilder("递交的请求（凭据材料恒打码，实发为真值）：\n");
        requestEcho.append(requestTrail.text());
        requestEcho.append("\n请求体（模板原文，").append(injections)
                .append(" 处 {{CRED:").append(credKey)
                .append("}} 占位符发送前由保险箱材料填充，计数含 URL、请求头与请求体）：\n");
        requestEcho.append(template.isEmpty() ? "（无请求体——纯连通探测）" : template);
        if (injections == 0) {
            requestEcho.append("\n提示：本次没有引用本凭据的占位符（").append(PLACEHOLDER_SHAPE)
                    .append("），请求不携带凭据材料（等效于地址连通测试）。"
                            + "修法：在「请求头」区点「用此凭据」预填，或在模板里写占位符。");
        }
        trail.step(token, ref, "构造请求：" + method + " " + url, requestEcho.toString(),
                requestTrail.sealed());

        int timeoutMs = timeoutMs();
        ProbeExchangeRunner.ExchangeResult result;
        try {
            result = runner.run(method, url, headers, "GET".equals(method) ? null : body, timeoutMs);
        } catch (IOException transport) {
            trail.transportError(token, ref, method + " " + url, transport, timeoutMs);
            return closed(token, ref, Verdict.FAILED, null, injections, "");
        }

        String location = result.firstHeader("Location");
        Verdict verdict = trail.responseStepAndVerdict(token, ref, result);
        String fingerprintRaw = configService.getValue(FINGERPRINT_KEY, "true").strip();
        boolean fingerprintOn = !"0".equals(fingerprintRaw)
                && !"false".equalsIgnoreCase(fingerprintRaw);
        boolean loginShell = verdict == Verdict.VALID && fingerprintOn
                && ProbeExchangeRunner.looksLikeLoginShell(decodeHeadline(result));
        CredentialTestGrade grade = switch (verdict) {
            case EXPIRED -> CredentialTestGrade.EXPIRED;
            case FAILED -> null;
            case UNKNOWN -> CredentialTestGrade.UNKNOWN;
            case VALID -> injections == 0 ? CredentialTestGrade.UNVERIFIED
                    : loginShell ? CredentialTestGrade.UNKNOWN : CredentialTestGrade.VERIFIED;
        };
        String note = switch (verdict) {
            case VALID -> loginShell
                    ? "目标返回 200，但响应体带登录页特征（口令输入框/登录标题/SSO 跳转脚本），"
                    + "疑似未放行——不据此判定凭据失效。修法：改用真实业务接口地址复核后重测。"
                    : injections > 0
                    ? "凭据已验证：目标接受（2xx，引用 " + injections + " 处），可用性确认。"
                    : "本次请求未引用凭据材料（占位符 0 处），结果只证明地址可达，凭据本身未被检验。"
                            + "修法：点「用此凭据」预填请求头，或在模板里写 " + PLACEHOLDER_SHAPE + " 后重测。";
            case EXPIRED -> "目标拒绝了请求（401/403），凭据被拒。修法："
                    + (injections > 0 ? "到「凭据保险箱」更新本行口令后重测；" : "确认请求头引用了本行凭据；")
                    + "若确认口令仍有效，请核对被测系统的授权范围。";
            case UNKNOWN -> "目标返回了非决定性状态（" + result.statusCode()
                    + (location == null ? "" : "，Location=" + location)
                    + "），无法据此判定凭据有效性。修法：改用真实业务接口地址复核后重测。";
            case FAILED -> "";
        };
        trail.step(token, ref, "判定：" + verdict.human(), note);
        if (grade != null) {
            jdbc.update("UPDATE tp_credential SET test_evidence = :grade,"
                            + " last_verified_at = CASE WHEN :grade = 'VERIFIED'"
                            + " THEN now() ELSE last_verified_at END, updated_at = now()"
                            + " WHERE id = :id",
                    new MapSqlParameterSource("grade", grade.name()).addValue("id", credentialId));
        }
        return closed(token, ref, verdict, grade, injections, note);
    }

    /** Decoded head of the response body for the shell scan (budget-bounded). */
    private static String decodeHeadline(ProbeExchangeRunner.ExchangeResult result) {
        byte[] bytes = result.bodyBytes();
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int head = Math.min(bytes.length, 8192);
        String text = new String(bytes, 0, head, StandardCharsets.UTF_8);
        return text.isBlank() ? null : text;
    }

    /** First recorded origin of the newest login state bound to this credential. */
    private String boundLoginStateOrigin(long credentialId) {
        List<String> rows = jdbc.queryForList(
                "SELECT l.scope_origins::text FROM tp_login_state l"
                        + " WHERE l.credential_id = :cred AND l.status = 1"
                        + " ORDER BY l.updated_at DESC LIMIT 1",
                new MapSqlParameterSource("cred", credentialId), String.class);
        if (rows.isEmpty()) {
            return null;
        }
        for (String piece : rows.get(0).replaceAll("[\\[\\]\"]", "").split(",")) {
            String url = ProbeExchangeRunner.normalizedHttpUrl(piece.strip());
            if (url != null) {
                return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            }
        }
        return null;
    }

    private CredentialTestOutcome fail(String token, String ref, String summary, String detail) {
        trail.error(token, ref, summary, detail);
        return new CredentialTestOutcome(token, Verdict.FAILED.name(), null, summary,
                summary + "：" + detail, 0);
    }

    private CredentialTestOutcome closed(String token, String ref, Verdict verdict,
                                         CredentialTestGrade grade, int injections, String note) {
        trail.step(token, ref, "验证结束：" + (grade == null ? verdict.human() : grade.human()), null);
        return new CredentialTestOutcome(token, verdict.name(),
                grade == null ? null : grade.name(),
                grade == null ? null : grade.human(), note, injections);
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
