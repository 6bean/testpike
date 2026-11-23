package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.session.api.dto.ExtraAuthRef;
import com.sixbean.testpike.core.session.api.dto.SessionAuthHeader;
import com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single point for login-state metadata, consumption resolution, origin
 * whitelist checks and expiry parsing (AV design M4: U1 and AV share this one
 * implementation — second consumers reference, never copy).
 *
 * <p>Expiry parsing is intentionally honest: a value that cannot be parsed
 * yields null and the light shows UNKNOWN (never a guessed window). JWT paths
 * decode the middle segment (base64url) and read the {@code exp} claim; cookie
 * paths read an {@code Expires=} attribute (RFC 1123); storage_state
 * snapshots resolve to the earliest positive per-cookie {@code expires}
 * (session-only cookies carry -1) and stay UNKNOWN when no cookie states
 * one.
 *
 * <p>Resolution follows D9: an explicit state_name takes the exact row; a
 * role_scope that matches more than one enabled row fails closed and lists the
 * candidate names — guessing the first usable row is forbidden. Reads outside
 * the scope_origins whitelist are refused fail-closed (D11). Plaintext material
 * is only ever revealed through the single vault (credentialService) and must
 * never be logged or echoed by callers.
 */
@Component
public class SessionStateSupport {

    /** Ledger kind vocabulary (CHECK constraint mirror). */
    public static final List<String> KINDS = List.of("token", "storage_state", "cookie_set");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COOKIE_EXPIRES =
            Pattern.compile("(?i)\\bExpires=([^;]+)");

    private final NamedParameterJdbcTemplate jdbc;
    private final CredentialService credentialService;

    public SessionStateSupport(NamedParameterJdbcTemplate jdbc, CredentialService credentialService) {
        this.jdbc = jdbc;
        this.credentialService = credentialService;
    }

    /** Parse the declared expiry of captured material; null when unparseable. */
    public LocalDateTime parseExpiresAt(String kind, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return switch (kind == null ? "" : kind) {
            case "token" -> parseJwtExp(rawValue);
            // Leading '[' is the capture-lineage commit shape (entry array);
            // anything else keeps the manual-upload grammar for this kind.
            case "cookie_set" -> rawValue.strip().startsWith("[")
                    ? parseCaptureEntriesExpires(rawValue) : parseCookieExpires(rawValue);
            case "storage_state" -> rawValue.strip().startsWith("[")
                    ? parseCaptureEntriesExpires(rawValue) : parseStorageStateExpires(rawValue);
            default -> null;
        };
    }

    /**
     * Map an absolute expiry to the four-state light against "now" and a
     * renew-lead window (session.renew-lead-minutes, injected by callers).
     */
    public ExpiryState expiryState(LocalDateTime expiresAt, LocalDateTime now, long renewLeadMinutes) {
        return ExpiryState.classify(expiresAt, now, Duration.ofMinutes(Math.max(0, renewLeadMinutes)));
    }

    /**
     * Origin whitelist check with the capture contract's two match tiers:
     * a portless (or default-port) origin — the shape RFC 6265 cookies
     * produce — is covered by any scope entry on the same scheme and host;
     * an origin with an explicit non-default port stays strict-equal. Empty
     * or absent whitelists and unparseable origins read nothing (fail-closed).
     */
    public boolean originAllowed(List<String> scopeOrigins, String origin) {
        if (scopeOrigins == null || scopeOrigins.isEmpty() || origin == null) {
            return false;
        }
        URI wanted = httpOrigin(origin);
        if (wanted == null) {
            return false;
        }
        boolean hostScopedOnly = isDefaultPort(wanted);
        for (String entry : scopeOrigins) {
            URI scope = httpOrigin(entry);
            if (scope == null
                    || !scope.getScheme().equalsIgnoreCase(wanted.getScheme())
                    || !scope.getHost().equalsIgnoreCase(wanted.getHost())) {
                continue;
            }
            if (hostScopedOnly || effectivePort(wanted) == effectivePort(scope)) {
                return true;
            }
        }
        return false;
    }

    /** Parse one http(s) origin; anything else (paths, other schemes, junk) is refused. */
    private static URI httpOrigin(String raw) {
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                return null;
            }
            return uri;
        } catch (java.net.URISyntaxException ex) {
            return null;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    /** True when the URI states no port or only its scheme's default port. */
    private static boolean isDefaultPort(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            return true;
        }
        return port == (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
    }

    /**
     * Consumption resolution. Exactly one selector must be supplied:
     * stateName (precise row, D9) wins over roleScope when present; roleScope
     * resolves among enabled rows and fails closed on ambiguity by listing
     * candidates. Disabled rows are never consumable.
     */
    public LoginState resolve(long projectId, String stateName, String roleScope) {
        boolean byName = stateName != null && !stateName.isBlank();
        boolean byRole = roleScope != null && !roleScope.isBlank();
        if (!byName && !byRole) {
            throw new ServiceException(
                    "A login state must be selected by name or by test role.",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (byName) {
            LoginState row = queryOne(
                    "SELECT " + LoginState.SELECT_COLUMNS + " FROM tp_login_state l"
                            + " WHERE l.project_id = :pid AND l.state_name = :name",
                    new MapSqlParameterSource("pid", projectId).addValue("name", stateName));
            if (row == null) {
                throw new ServiceException(
                        "登录态「" + stateName + "」在本项目不存在——请到「登录态账本」页点「新建登录态」登记，或改用已登记的名称。",
                        BizErrorCode.NOT_FOUND);
            }
            if (row.getStatus() != null && row.getStatus() == 0) {
                throw new ServiceException(
                        "登录态「" + stateName + "」已停用，不能用于消费——请到「登录态账本」页找到该行点「启用」，或点「新建登录态」重新登记。",
                        BizErrorCode.NOT_FOUND);
            }
            return row;
        }
        List<LoginState> rows = jdbc.query(
                "SELECT " + LoginState.SELECT_COLUMNS + " FROM tp_login_state l"
                        + " WHERE l.project_id = :pid AND l.role_scope = :role AND l.status = 1"
                        + " ORDER BY l.state_name",
                new MapSqlParameterSource("pid", projectId).addValue("role", roleScope),
                LoginState.ROW_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException(
                    "角色「" + roleScope + "」在本项目没有可用登录态——请到「登录态账本」页点「新建登录态」登记该角色的登录态。",
                    BizErrorCode.NOT_FOUND);
        }
        if (rows.size() > 1) {
            String candidates = rows.stream().map(LoginState::getStateName)
                    .reduce((a, b) -> a + "、" + b).orElse("");
            throw new ServiceException(
                    "角色「" + roleScope + "」下存在多个登录态（" + candidates
                            + "），请从上述清单中指定一个登录态名称填入引用处的「登录态」栏，系统不代为挑选。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    /**
 * Materialise the browser-context snapshot for one ledger row — the only
 * place the capture-entry array is translated into the Playwright
 * this document as-is and never re-derives it, a second translation site
 * is the drift this ruling forbids).
 *
 * <p>Three source forms are honoured: a capture-lineage entry array
 * (cookies get their domain from the recorded origin, the scheme decides
 * {@code secure}, a missing expiry becomes the session-cookie sentinel
 * {@code -1}, local-storage entries group under their origin); a manual
 * Playwright grammar object, passed through byte-for-byte; anything else
 * honestly refuses rather than guessing, because an anonymous run at a
 * protected URL misattributes every later step.
 *
 * <p>A {@code token} row is refused with the remedy named: browser
 * contexts take cookies and storage, an API case takes the token.
 *
 * @return a Playwright storage_state JSON document
 */
    public String browserStorageStateJson(long projectId, String stateName, String roleScope) {
        LoginState row = resolve(projectId, stateName, roleScope);
        if ("token".equals(row.getKind())) {
            throw new ServiceException(
                    "登录态「" + row.getStateName() + "」是 token 类型，不能注入浏览器上下文——"
                            + "请到「登录态账本」页点「新建登录态」，类型选 storage_state（浏览器会话快照整体重放）重新采集该账号的浏览器快照（token 供接口用例使用）。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (row.getCredentialId() == null) {
            throw new ServiceException(
                    "登录态「" + row.getStateName() + "」没有可取回的快照内容——请到「登录态账本」页点「新建登录态」重新采集后再试。",
                    BizErrorCode.NOT_FOUND);
        }
        String raw = credentialService.revealSecret(row.getCredentialId());
        if (raw == null || raw.isBlank()) {
            throw new ServiceException(
                    "登录态「" + row.getStateName() + "」的快照内容为空，请到「登录态账本」页点「新建登录态」重新采集。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String trimmed = raw.strip();
        return trimmed.startsWith("[")
                ? fromCaptureEntries(trimmed, row.getStateName())
                : passThroughPlaywrightGrammar(trimmed, row.getStateName());
    }

    /**
     * Capture-lineage entries (the commit round's checked list) to the
     * storage_state document. Unknown entry kinds carry no browser meaning and
     * are skipped; a row whose entries yield neither cookies nor storage is
     * refused instead of handing the browser an empty context.
     */
    private String fromCaptureEntries(String entriesJson, String stateName) {
        JsonNode entries;
        try {
            entries = JSON.readTree(entriesJson);
        } catch (Exception malformed) {
            throw refuseUntranslatable(stateName);
        }
        if (!entries.isArray()) {
            throw refuseUntranslatable(stateName);
        }
        ObjectNode state = JSON.createObjectNode();
        ArrayNode cookies = state.putArray("cookies");
        Map<String, ArrayNode> storageByOrigin = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            String kind = entry.path("kind").asText("");
            String name = entry.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            if ("cookie".equals(kind)) {
                URI origin = httpOrigin(entry.path("origin").asText(""));
                if (origin == null) {
                    throw new ServiceException(
                            "登录态「" + stateName + "」里的 Cookie「" + name
                                    + "」没有可用的来源站点，无法注入浏览器——请到「登录态账本」页点「新建登录态」重新采集该条目。",
                            BizErrorCode.VALIDATION_ERROR);
                }
                ObjectNode cookie = cookies.addObject();
                cookie.put("name", name);
                cookie.put("value", entry.path("value").asText(""));
                cookie.put("domain", origin.getHost());
                cookie.put("path", "/");
                cookie.put("secure", origin.getScheme().equalsIgnoreCase("https"));
                JsonNode expires = entry.path("expiresAtEpochSecond");
                cookie.put("expires",
                        expires.isNumber() && expires.longValue() > 0 ? expires.longValue() : -1L);
            } else if ("local_storage".equals(kind)) {
                String origin = normalizedOrigin(entry.path("origin").asText(""));
                if (origin == null) {
                    throw new ServiceException(
                            "登录态「" + stateName + "」里的本地存储「" + name
                                    + "」没有可用的来源站点，无法注入浏览器——请到「登录态账本」页点「新建登录态」重新采集该条目。",
                            BizErrorCode.VALIDATION_ERROR);
                }
                storageByOrigin.computeIfAbsent(origin, key -> JSON.createArrayNode()).addObject()
                        .put("name", name)
                        .put("value", entry.path("value").asText(""));
            }
        }
        ArrayNode origins = state.putArray("origins");
        storageByOrigin.forEach((origin, items) -> {
            ObjectNode node = origins.addObject();
            node.put("origin", origin);
            node.set("localStorage", items);
        });
        if (cookies.isEmpty() && origins.isEmpty()) {
            throw new ServiceException(
                    "登录态「" + stateName + "」的快照不含浏览器可注入的 Cookie 或本地存储，请到「登录态账本」页点「新建登录态」重新采集。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return state.toString();
    }

    /** A manual upload already in Playwright grammar is used as recorded. */
    private String passThroughPlaywrightGrammar(String raw, String stateName) {
        try {
            JsonNode doc = JSON.readTree(raw);
            if (doc.isObject() && (doc.has("cookies") || doc.has("origins"))) {
                return raw;
            }
        } catch (Exception malformed) {
            // fall through to the honest refusal below
        }
        throw refuseUntranslatable(stateName);
    }

    private ServiceException refuseUntranslatable(String stateName) {
        return new ServiceException(
                "登录态「" + stateName + "」的快照内容无法用于浏览器注入，请到「登录态账本」页点「新建登录态」，类型选 storage_state（浏览器会话快照整体重放）重新采集。",
                BizErrorCode.VALIDATION_ERROR);
    }

    /** Origin scheme://host[:port] of an http(s) URL, or null when unparseable. */
    private static String normalizedOrigin(String raw) {
        URI origin = httpOrigin(raw);
        if (origin == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder()
                .append(origin.getScheme().toLowerCase())
                .append("://")
                .append(origin.getHost().toLowerCase());
        if (origin.getPort() >= 0 && !isDefaultPort(origin)) {
            sb.append(':').append(origin.getPort());
        }
        return sb.toString();
    }

    /**
     * Reveal the plaintext behind a state for server-side injection only.
     * Snapshot-only rows (storage_state without vault reference) honestly fail.
     * Callers must never log or echo the returned value.
     */
    public String revealStateValue(long projectId, String stateName) {
        LoginState row = resolve(projectId, stateName, null);
        if (row.getCredentialId() == null) {
            throw new ServiceException(
                    "登录态「" + stateName + "」没有保险库引用，只有快照对象，不能按密文直读——请到「登录态账本」页点「新建登录态」，类型选 token（访问令牌随请求头递交）或 cookie_set（Cookie 集合重放）重新登记。",
                    BizErrorCode.NOT_FOUND);
        }
        return credentialService.revealSecret(row.getCredentialId());
    }

    /**
 * Header name the kind's primary credential rides as (single source for
 * the combined-session header conflict check, ): a token row
 * occupies Authorization, every browser-shape row occupies Cookie.
 */
    public static String mainHeaderNameOfKind(String kind) {
        return "token".equals(kind) ? "Authorization" : "Cookie";
    }

    /** {@code {{CRED:key}}} placeholder grammar shared with the apitest doors. */
    private static final Pattern CRED_TOKEN = Pattern.compile("\\{\\{CRED:([^{}]+)}}");

    /**
 * dispatch assembly: the combined-session header rows a login
 * state rides alongside its kind primary header, materialised at the
 * dispatch instant only. Every value comes from the vault through the
 * audited revealSecret door (the ledger row keeps references, never
 * secrets); a {@code {{CRED:key}}} placeholder inside a value template
 * is replaced by that credential's plaintext, an empty template rides
 * the ref's own credential value. The primary header name always wins —
 * a clashing ref was refused at save time and a defensive clash here is
 * skipped instead of silently overwriting the real session header; a
 * duplicate header name inside the list keeps the first row; non-header
 * destinations (save-refused too) never materialise. A ref whose
 * credential vanished fails loudly with the vault remedy named.
 * Returned values are secret material — callers must never log or echo
 * them.
 */
    public Map<String, String> extraAuthHeaders(long projectId, String kind, List<ExtraAuthRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return Map.of();
        }
        String primary = mainHeaderNameOfKind(kind);
        Map<String, String> headers = new LinkedHashMap<>();
        for (ExtraAuthRef ref : refs) {
            if (ref == null || ref.credKey() == null || ref.credKey().isBlank()) {
                continue;
            }
            if (ref.into() != null && !ref.into().isBlank()
                    && !ExtraAuthRef.INTO_HEADER.equalsIgnoreCase(ref.into().strip())) {
                continue;
            }
            String headerName = ref.headerName() == null ? "" : ref.headerName().strip();
            if (headerName.isEmpty() || headerName.equalsIgnoreCase(primary)) {
                continue;
            }
            boolean repeated = headers.keySet().stream().anyMatch(seen -> seen.equalsIgnoreCase(headerName));
            if (repeated) {
                continue;
            }
            headers.put(headerName, materialiseRef(projectId, ref));
        }
        return headers;
    }

    /** Plaintext payload of one ref: raw vault value or template-filled prefix form. */
    private String materialiseRef(long projectId, ExtraAuthRef ref) {
        String template = ref.valueTemplate();
        if (template == null || template.isBlank()) {
            return plaintextOfRefCredential(projectId, ref.credKey().strip());
        }
        return materialiseTemplate(projectId, template);
    }

    /**
 * Fill every {@code {{CRED:key}}} token of a template through the vault
 * single source (shared with combined-header materialisation — one
 * substitution loop per dialect, grammar, no second copy).
 * Unknown keys fail named with the vault door. The returned plaintext is
 * for transient in-flight use only: callers must never log or echo it.
 */
    public String materialiseTemplate(long projectId, String template) {
        Matcher tokens = CRED_TOKEN.matcher(template);
        StringBuilder filled = new StringBuilder();
        while (tokens.find()) {
            tokens.appendReplacement(filled,
                    Matcher.quoteReplacement(plaintextOfRefCredential(projectId, tokens.group(1).strip())));
        }
        tokens.appendTail(filled);
        return filled.toString();
    }

    /** credKey → plaintext through the vault single source; vanished rows fail named. */
    private String plaintextOfRefCredential(long projectId, String credKey) {
        long credentialId = credentialService.list(projectId, null).stream()
                .filter(c -> credKey.equals(c.credKey()))
                .map(c -> c.id())
                .findFirst()
                .orElse(-1L);
        if (credentialId < 0) {
            throw new ServiceException("引用「" + credKey + "」指向的凭据在本项目保险库已不存在，无法装配"
                    + "（附加会话头与请求体占位符共用此校验）——请到「凭据保险箱」重新建档，"
                    + "再回到「登录态账本」复核这条引用。", BizErrorCode.NOT_FOUND);
        }
        return credentialService.revealSecret(credentialId);
    }

    /**
     * Transport header for server-side liveness reads of one ledger row
     * (design doc M1 form two): a token row rides as
     * {@code Authorization: Bearer <value>}, a cookie_set row as
     * {@code Cookie: <values>}. Captured cookie entry arrays are flattened
     * here so no consumer re-derives the grammar (single-source rule). A
     * storage_state snapshot has no header form and honestly refuses with
     * the remedy named. Never log or echo the returned value.
     */
    public SessionAuthHeader probeAuthHeader(long projectId, String stateName) {
        LoginState row = resolve(projectId, stateName, null);
        String value = revealStateValue(projectId, row.getStateName());
        if ("token".equals(row.getKind())) {
            return new SessionAuthHeader("Authorization", "Bearer " + value);
        }
        if ("cookie_set".equals(row.getKind())) {
            return new SessionAuthHeader("Cookie", flattenCookieHeader(value));
        }
        throw new ServiceException(
                "登录态「" + stateName + "」是 storage_state 快照形态，没有接口请求头形态可用于直读——"
                        + "请到「登录态账本」选取类型为 token（访问令牌随请求头递交）或 cookie_set（Cookie 集合重放）的条目，或点「新建登录态」按这两种类型重新登记。",
                BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * Replay headers built from the plaintext revealed for one vault-backed
     * ledger row. Both write channels (manual registration and capture
     * commit) store material in the vault, so every header-replay door reads
     * it through here instead of re-deriving the grammar. A token row rides
     * as {@code Authorization: Bearer <value>}; cookie_set and storage_state
     * rows yield one {@code Cookie} header from whichever stored shape is
     * present: a capture-lineage entry array (translated by the shared
     * {@link #fromCaptureEntries}), a Playwright document (its cookies
     * array), or the legacy raw {@code k=v; ...} line (passed through).
     * The returned values are secret material — callers must never log or
     * echo them.
     */
    public Map<String, String> vaultReplayHeaders(String kind, String stateName, String revealedValue) {
        String text = revealedValue == null ? "" : revealedValue.strip();
        if (text.isEmpty()) {
            throw new ServiceException(
                    "登录态「" + stateName + "」的保险库值是空的，请在「登录态账本」重新登记或重新采集。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("token".equals(kind)) {
            return Map.of("Authorization", "Bearer " + text);
        }
        return Map.of("Cookie", cookieLineFromMaterial(stateName, text));
    }

    /** Cookie header line from any stored vault shape of a browser session. */
    private String cookieLineFromMaterial(String stateName, String text) {
        if (text.startsWith("[")) {
            try {
                return cookieLineFromCookies(
                        JSON.readTree(fromCaptureEntries(text, stateName)).path("cookies"), stateName);
            } catch (ServiceException explicit) {
                throw explicit;
            } catch (Exception malformed) {
                throw untranslatableMaterial(stateName, text);
            }
        }
        if (text.startsWith("{")) {
            try {
                JsonNode doc = JSON.readTree(text);
                if (doc.has("cookies") || doc.has("origins")) {
                    return cookieLineFromCookies(doc.path("cookies"), stateName);
                }
            } catch (ServiceException explicit) {
                throw explicit;
            } catch (Exception malformed) {
                // fall through to the honest refusal below
            }
            throw untranslatableMaterial(stateName, text);
        }
        String line = flattenCookieHeader(text);
        if (!line.contains("=")) {
            throw untranslatableMaterial(stateName, text);
        }
        return line;
    }

    /** name=value pairs of a Playwright cookies array; empty arrays fail. */
    private static String cookieLineFromCookies(JsonNode cookies, String stateName) {
        StringBuilder pairs = new StringBuilder();
        for (JsonNode cookie : cookies) {
            String name = cookie.path("name").asText("");
            String value = cookie.path("value").asText("");
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            if (pairs.length() > 0) {
                pairs.append("; ");
            }
            pairs.append(name).append('=').append(value);
        }
        if (pairs.length() == 0) {
            throw new ServiceException(
                    "登录态「" + stateName + "」的会话材料里没有可用于请求头重放的 Cookie"
                            + "（本地存储无法以请求头重放）——请在「登录态账本」重新采集含 Cookie 的快照，或改用浏览器执行通道。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return pairs.toString();
    }

    private ServiceException untranslatableMaterial(String stateName, String text) {
        // Conservative shape hint (R63 pointer-accuracy, 11-19 report): when a
        // single-line eyJ-prefixed dotted value is pasted into a browser-kind
        // row, the generic refusal would send the user to re-collect forever —
        // name the likely door instead. The classifier never echoes content.
        String hint = looksLikeBearerToken(text)
                ? "\n当前内容看起来是一段令牌文本：若该系统以 Authorization 头认证，"
                    + "请点该行「编辑」把登录态类型改为 token（凭据值留空即保留现值）后保存；"
                    + "如还需携带其它头，在编辑对话框的「附加会话头」区登记。"
                : "";
        return new ServiceException(
                "登录态「" + stateName + "」的保险库内容不是可识别的会话形态"
                        + "（Cookie 条目数组、Playwright 快照或 k=v 原文），"
                        + "请到「登录态账本」页点「新建登录态」重新登记或重新采集。" + hint,
                BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * Shape classifier used only to point refusals at the likely fix door.
     * A JWT-shaped bearer token is ey[base64url] . payload [. signature] with
     * no ';' and no line break; anything richer falls back to the generic
     * refusal. Never echoes or persists the inspected content.
     */
    public static boolean looksLikeBearerToken(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        if (t.isEmpty() || t.contains(";") || t.contains("\n")) {
            return false;
        }
        long dots = t.chars().filter(c -> c == '.').count();
        return t.startsWith("eyJ") && dots >= 1 && dots <= 2;
    }

    /**
 * Save-door consistency gate (11-19 report: a raw token pasted into a
 * kept the user cycling): material supplied with a browser-kind row must
 * actually replay as a Cookie header through the SAME grammar source.
 * Token rows accept any single-value material; a blank secret means
 * "keep the stored material" and is not re-checked here.
 */
    public void assertMaterialMatchesKind(String kind, String stateName, String secret) {
        if (!KINDS.contains(kind) || "token".equals(kind)
                || secret == null || secret.isBlank()) {
            return;
        }
        vaultReplayHeaders(kind, stateName, secret);
    }

    /** Header grammar passthrough; capture-entry arrays become {@code n1=v1; n2=v2}. */
    private static String flattenCookieHeader(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("[")) {
            return text;
        }
        try {
            StringBuilder pairs = new StringBuilder();
            for (JsonNode entry : JSON.readTree(text)) {
                if (!entry.hasNonNull("name")) {
                    continue;
                }
                if (pairs.length() > 0) {
                    pairs.append("; ");
                }
                pairs.append(entry.get("name").asText()).append('=').append(entry.path("value").asText(""));
            }
            return pairs.toString();
        } catch (Exception malformed) {
            return text;
        }
    }

    private LoginState queryOne(String sql, MapSqlParameterSource params) {
        List<LoginState> rows = jdbc.query(sql, params, LoginState.ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDateTime parseJwtExp(String token) {
        String[] segments = token.split("\\.");
        if (segments.length < 2) {
            return null;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(segments[1]);
            JsonNode exp = JSON.readTree(new String(payload, StandardCharsets.UTF_8)).get("exp");
            if (exp == null || !exp.isNumber()) {
                return null;
            }
            return LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(exp.longValue()), ZoneId.systemDefault());
        } catch (Exception malformed) {
            return null;
        }
    }

    private LocalDateTime parseCookieExpires(String cookieHeader) {
        Matcher m = COOKIE_EXPIRES.matcher(cookieHeader);
        if (!m.find()) {
            return null;
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(m.group(1).trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME);
            return when.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * Capture-lineage commit shape: a JSON array of checked entries, each
     * with an optional {@code expiresAtEpochSecond} (null or -1 marks a
     * session-only value). Same honest earliest-positive rule as the
     * Playwright snapshot; a malformed array stays an UNKNOWN, never a guess.
     */
    private LocalDateTime parseCaptureEntriesExpires(String entriesJson) {
        try {
            JsonNode entries = JSON.readTree(entriesJson);
            if (!entries.isArray()) {
                return null;
            }
            long earliest = Long.MAX_VALUE;
            boolean found = false;
            for (JsonNode entry : entries) {
                JsonNode expires = entry.path("expiresAtEpochSecond");
                if (expires.isNumber() && expires.longValue() > 0 && expires.longValue() < earliest) {
                    earliest = expires.longValue();
                    found = true;
                }
            }
            return found
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(earliest), ZoneId.systemDefault())
                    : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * Storage-state snapshots (Playwright shape) carry no single instant but
     * a per-cookie {@code expires} in epoch seconds — {@code -1} marks a
     * session-only cookie. The earliest positive value decides the snapshot's
     * lifetime: the first credential to die kills the session. No positive
     * expiry (or unparseable JSON) stays an honest UNKNOWN — never a guess.
     */
    private LocalDateTime parseStorageStateExpires(String snapshotJson) {
        try {
            JsonNode cookies = JSON.readTree(snapshotJson).path("cookies");
            if (!cookies.isArray()) {
                return null;
            }
            long earliest = Long.MAX_VALUE;
            boolean found = false;
            for (JsonNode cookie : cookies) {
                JsonNode expires = cookie.path("expires");
                if (expires.isNumber() && expires.longValue() > 0 && expires.longValue() < earliest) {
                    earliest = expires.longValue();
                    found = true;
                }
            }
            return found
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(earliest), ZoneId.systemDefault())
                    : null;
        } catch (Exception malformed) {
            return null;
        }
    }
}
