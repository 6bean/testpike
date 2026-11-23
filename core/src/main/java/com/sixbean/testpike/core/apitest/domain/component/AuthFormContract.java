package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Save-gate contract for the auth envelope of API test requests (W1c auth presets).
 * <p>Static stateless checker: {@code violation} returns a human-readable message
 * for the first problem found (null = legal). The core save chain
 * (ApiRequestServiceImpl.validate) delegates here — the executor runtime
 * (api_runner._auth_headers) accepts the same five types, so a shape accepted at
 * save time is always executable downstream (single dialect, no second validator).
 * <p>Secret slots (bearer token, custom header value, basic password, api-key value)
 * must carry a credential reference — {@code @cred/} prefix or a {@code {{CRED:key}}}
 * placeholder — never a plaintext value: requests are stored in clear rows and the
 * envelope is masked at snapshot time, so a plaintext secret would leak into the
 * database and every read-back (red line 4, same posture as the monitor LOGIN gate).
 * Signature assembly rows carry template strings ({@code ${gen.*}} per docs/04
 * rule 6a — the executor's single ${gen.fn(k=v)} parser — and
 * {@code {{CRED:...}}} vault references) resolved fresh at dispatch by the upstream single parser —
 * their values are not secret slots and stay unrestricted except non-blank.
 */
public final class AuthFormContract {

    /** Auth types injected by the executor (mirror of api_runner.AUTH_SUPPORTED — change both together). */
    public static final Set<String> SUPPORTED_TYPES =
            Set.of("bearer", "header", "basic", "api-key", "signature");

    /** Explicit no-auth opt-out : a stored declaration, never an injection type. */
    public static final String NONE_TYPE = "none";

    /** True when the auth block is an explicit opt-out ({@code {"type":"none"}}). */
    public static boolean isNone(JsonNode auth) {
        return auth != null && auth.isObject()
                && NONE_TYPE.equals(auth.path("type").asText("").trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Placement slots for api-key injection (same pair the executor honours). */
    public static final Set<String> PLACEMENTS = Set.of("header", "query");

    /** Secret-slot reference: @cred/ vault path or a whole-string {{CRED:key}} placeholder. */
    private static final Pattern CRED_REF =
            Pattern.compile("^(@cred/\\S+|\\{\\{CRED:[A-Za-z_][A-Za-z0-9_]*\\}\\}|.*\\{\\{CRED:[A-Za-z_][A-Za-z0-9_]*\\}\\}.*?)$");

    private AuthFormContract() {
        // static contract component, no instances
    }

    /** Auth object verdict: human-readable violation text, null = legal; empty object = no auth. */
    public static String violation(JsonNode auth) {
        if (auth == null || auth.isNull()) {
            return null;
        }
        // Shape order matters (ApiOrchestrationContract precedent): scalar nodes
        // report isEmpty() == true (JsonNode.size() is 0 for non-containers), so
        // testing isEmpty() before isObject() would wave non-object garbage
        // through the gate and fake-green it downstream. An empty array is the
        // honest "no auth" posture (same as {}); everything non-object fails closed.
        if (!auth.isObject()) {
            return auth.isArray() && auth.isEmpty() ? null
                    : "认证配置必须是对象（形如 {\"type\":\"bearer\",...}；无需认证保持 {})";
        }
        if (auth.isEmpty()) {
            return null;
        }
        String type = auth.path("type").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (type.isEmpty()) {
            return "认证配置写了字段但缺少 type——支持的类型：" + typeList()
                    + "；无需认证请保持空对象 {}";
        }
        // Explicit opt-out : {"type":"none"} is a legal
        // stored form meaning "this request sends no auth even when its
        // collection carries a default" — the block-replacement rule needs a
        // visible way to say "not this", otherwise inheritance is invisible.
        // It injects nothing, so it stays outside SUPPORTED_TYPES (the executor
        // mirror); the assembler resolves it to an empty block before dispatch.
        if (NONE_TYPE.equals(type)) {
            return null;
        }
        if (!SUPPORTED_TYPES.contains(type)) {
            return "认证类型「" + type + "」未支持（当前仅 " + typeList()
                    + " 注入）——请调整请求定义，勿假装无认证执行";
        }
        return switch (type) {
            case "bearer" -> bearerViolation(auth);
            case "header" -> headerViolation(auth);
            case "basic" -> basicViolation(auth);
            case "api-key" -> apiKeyViolation(auth);
            default -> signatureViolation(auth);
        };
    }

    /** Sorted type list shared by verdicts and error assembly (no duplicated literals). */
    public static String typeList() {
        return String.join(" / ", SUPPORTED_TYPES.stream().sorted().toList());
    }

    private static String bearerViolation(JsonNode auth) {
        String token = auth.path("token").asText("").trim();
        if (token.isEmpty()) {
            return "bearer 认证缺少 token（凭据引用形态：@cred/ 开头或 {{CRED:键}} 占位，"
                    + "在凭据管理中登记后引用）";
        }
        return secretSlotViolation("bearer 的 token", token);
    }

    private static String headerViolation(JsonNode auth) {
        if (auth.path("name").asText("").trim().isEmpty()) {
            return "header 认证缺少头名（name），例 name=X-API-KEY";
        }
        String value = auth.path("value").asText("");
        if (value.isBlank()) {
            return "header 认证缺少头值（value）——凭据引用形态：@cred/ 开头或 {{CRED:键}} 占位";
        }
        return secretSlotViolation("header 的 value", value);
    }

    private static String basicViolation(JsonNode auth) {
        if (auth.path("username").asText("").trim().isEmpty()) {
            return "basic 认证缺少用户名（username）";
        }
        String password = auth.path("password").asText("");
        if (password.isBlank()) {
            return "basic 认证缺少口令——请在字段 password 填凭据引用"
                    + "（@cred/ 开头或 {{CRED:键}} 占位，在凭据管理中登记后引用）";
        }
        return secretSlotViolation("basic 的 password", password);
    }

    private static String apiKeyViolation(JsonNode auth) {
        if (auth.path("name").asText("").trim().isEmpty()) {
            return "api-key 认证缺少键名（name），例 name=apiKey";
        }
        String value = auth.path("value").asText("");
        if (value.isBlank()) {
            return "api-key 认证缺少键值（value）——凭据引用形态：@cred/ 开头或 {{CRED:键}} 占位";
        }
        String secretViolation = secretSlotViolation("api-key 的 value", value);
        if (secretViolation != null) {
            return secretViolation;
        }
        String placement = auth.path("placement").asText("header").trim().toLowerCase(java.util.Locale.ROOT);
        if (!PLACEMENTS.contains(placement)) {
            return "api-key 注入位置「" + placement + "」不支持（仅 header / query，缺省 header）"
                    + "——请调整请求定义";
        }
        return null;
    }

    private static String signatureViolation(JsonNode auth) {
        JsonNode rows = auth.get("assemblies");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            return "signature 认证缺少装配行（assemblies 至少一行 {into,name,value}，"
                    + "into=header/query 注入位置、name=键名、value=模板串"
                    + "，时间戳/nonce/签名用 ${gen.*} 函数，密钥用 {{CRED:键}} 引用）";
        }
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                return "signature 装配行必须是对象（into/name/value），当前值「" + row.asText() + "」";
            }
            if (row.path("name").asText("").trim().isEmpty()) {
                return "signature 装配行缺少注入键名（name）";
            }
            String valueText = row.path("value").asText("");
            if (valueText.isBlank()) {
                return "signature 装配行缺少 value（模板串，例 ${gen.timestamp()} 或 "
                        + "${gen.hmacSha256(...)}）";
            }
            String into = row.path("into").asText("header").trim().toLowerCase(java.util.Locale.ROOT);
            if (!PLACEMENTS.contains(into)) {
                return "signature 装配位置「" + into + "」不支持（仅 header / query，缺省 header）"
                        + "——请调整请求定义";
            }
            // Dialect guard (docs/04 rule 6a): templates execute through the
            // executor's single ${gen.fn(k=v)} parser. A {{gen.*}} wrapper is a
            // different namespace family the parser never touches — it would be
            // dispatched verbatim and sent as a literal header value (fake green).
            // Refusing at save time keeps "accepted at save" == "executable".
            if (valueText.contains("{{gen.")) {
                return "signature 装配行的模板函数须用 ${gen.函数名(参数=值)} 写法"
                        + "（当前为 {{gen.}} 形态——执行器唯一解析器只认 ${gen.*}，"
                        + "{{gen.}} 形态会原文发出导致假通过）";
            }
        }
        return null;
    }

    /** Red line: a secret slot must be a credential reference, never a plaintext value. */
    private static String secretSlotViolation(String slot, String value) {
        if (!CRED_REF.matcher(value.trim()).matches()) {
            return slot + " 必须是凭据引用（@cred/ 开头，或 {{CRED:键}} 占位）——"
                    + "明文密钥禁入请求定义，否则会落库并进历史快照；"
                    + "请先在凭据管理中登记该密钥再以引用名接入";
        }
        return null;
    }

    /** Type options for mirrors (frontend form and error assembly share this single source). */
    public static List<String> typeOptions() {
        return SUPPORTED_TYPES.stream().sorted().toList();
    }
}
