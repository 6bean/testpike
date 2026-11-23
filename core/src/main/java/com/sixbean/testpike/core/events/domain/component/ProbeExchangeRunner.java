package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared outbound exchange for the "test now" doors (login-state probe and
 * credential test ride this one implementation - the request build, the
 * redirect-free transport and the verdict rules must not diverge per door).
 *
 * <p>Transport = the repo-standard java.net.http client (McpHandshakeClient /
 * tool execution baseline, zero new dependency), redirects NEVER followed so
 * a bounce to the login page stays observable instead of silently passing.
 */
@Component
public class ProbeExchangeRunner {

    /** Connection budget fixed at 5s (same value as the MCP handshake door). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Hot knob for the response-body byte budget of the exchange trail. */
    public static final String BODY_BUDGET_KEY = "session.probe.body-max-bytes";
    public static final int BODY_BUDGET_DEFAULT = 65536;
    /** Location fragments that mark a bounce as "sent to the login page". */
    private static final Pattern LOGIN_BOUNCE = Pattern.compile(
            "(?i)(login|signin|sign-in|sso|auth|cas|passport|account/(login|signin))");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ExchangeSecretLedger secrets;
    private final ConfigService configService;

    public ProbeExchangeRunner(ExchangeSecretLedger secrets, ConfigService configService) {
        this.secrets = secrets;
        this.configService = configService;
    }

    /**
     * One rendered trail fragment: the masked plain text for the event detail
     * plus the sealed ciphertext sidecar (null when nothing secret rode by),
     * shaped for TaskEventWriter's six-argument step door. {@code full} is the
     * unclipped twin of {@code text} when a budget cut hid content (the body
     * beyond session.probe.body-max-bytes); null when the preview already
     * carries everything, so the ledger stores no duplicate copy.
     */
    public record TrailPiece(String text, String sealed, String full) {

        /** Piece whose preview is already complete (no full twin to store). */
        public TrailPiece(String text, String sealed) {
            this(text, sealed, null);
        }
    }

    /** One raw exchange result, headers kept for the response echo. */
    public record ExchangeResult(int statusCode, long elapsedMillis,
                                 Map<String, List<String>> headers, byte[] bodyBytes) {

        public String firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && !entry.getValue().isEmpty()) {
                    return entry.getValue().get(0);
                }
            }
            return null;
        }

        /** Content-type of the response, blank-safe. */
        public String contentType() {
            String ct = firstHeader("content-type");
            return ct == null ? "" : ct;
        }
    }

    /**
     * Runs one request and returns the raw exchange. IOException / timeout /
     * interrupt propagate as IOException with a human message so the caller
     * can write the matching error event.
     */
    public ExchangeResult run(String method, String url, Map<String, String> headers,
                              String body, int timeoutMs) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)));
            headers.forEach(builder::header);
            if ("GET".equals(method)) {
                builder.GET();
            } else if (body == null || body.isEmpty()) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            long started = System.nanoTime();
            HttpResponse<byte[]> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return new ExchangeResult(response.statusCode(), elapsed,
                    response.headers().map(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("探测被中断", interrupted);
        }
    }

    /** Validates a user-supplied or recorded target address; null when unusable. */
    public static String normalizedHttpUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String url = candidate.strip();
        if (!url.regionMatches(true, 0, "http://", 0, 7)
                && !url.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        try {
            URI.create(url);
            return url;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Verdict rules shared by both doors (redirect-free transport assumed). */
    public static Verdict verdictOf(int status, String locationHeader) {
        if (status >= 200 && status < 300) {
            return Verdict.VALID;
        }
        if (status == 401 || status == 403) {
            return Verdict.EXPIRED;
        }
        if (status >= 300 && status < 400) {
            return locationHeader != null && LOGIN_BOUNCE.matcher(locationHeader).find()
                    ? Verdict.EXPIRED : Verdict.UNKNOWN;
        }
        return Verdict.UNKNOWN;
    }

    /**
     * Body word family marking a 200 answer that is really a login page shell
     * : SPAs answer unauthenticated calls with 200 + a sign-in page,
     * so a plain 2xx verdict would be a false green. Conservative shape - a
     * password input, an SSO location swap or a login-flavoured title.
     */
    private static final Pattern LOGIN_SHELL = Pattern.compile(
            "(?i)(<input[^>]+type\\s*=\\s*[\"']?password)"
                    + "|(location(\\.href|\\.replace)\\s*[=(][^;\\n]*"
                    + "(login|signin|sign-in|sso|cas|passport))"
                    + "|(<title[^>]*>[^<]*(login|sign[ -]?in|sso|登录))");

    /** True when the response body carries login-shell markers (null-safe). */
    public static boolean looksLikeLoginShell(String bodyText) {
        return bodyText != null && LOGIN_SHELL.matcher(bodyText).find();
    }

    /**
     * Full outbound request echo for the trail: request line, every header
     * (secret families masked, never dropped), sealed sidecar for the values
     * a later reveal door may resolve under the gated audit.
     */
    public TrailPiece renderRequestTrail(String method, String url, Map<String, String> headers) {
        return renderRequestTrail(method, url, headers, java.util.Set.of());
    }

    /**
 * Overload for doors that materialise secret material beyond the word
 * list ( combined-session refs: the value comes from the vault,
 * so every dispatched ref row is secret regardless of its header name).
 * Names in {@code extraSecretNames} (lower-cased) join the masking and
 * sealing set; every other row renders exactly as the word-list form.
 */
    public TrailPiece renderRequestTrail(String method, String url, Map<String, String> headers,
                                         java.util.Set<String> extraSecretNames) {
        StringBuilder text = new StringBuilder();
        List<String> sealed = new ArrayList<>();
        text.append("请求行：").append(method).append(' ').append(url).append('\n');
        text.append("请求头（共 ").append(headers.size()).append(" 项）：");
        headers.forEach((name, value) -> {
            String shown = name.strip().toLowerCase(Locale.ROOT);
            text.append('\n').append(shown).append(": ");
            if (ExchangeSecretLedger.isSensitiveHeader(shown) || extraSecretNames.contains(shown)) {
                // The outbound Cookie header doubles as "which session entries
                // this replay carries" - the names are exactly the operational
                // info the probe page must show, so values are masked pair by
                // pair instead of one opaque blob. Other secret families keep
                // the whole-value mask.
                text.append("cookie".equals(shown) ? maskCookiePairs(value) : secrets.mask(value));
                sealed.add("request:" + shown + "\t" + secrets.seal(value));
            } else {
                text.append(value);
            }
        });
        return new TrailPiece(text.toString(), sealed.isEmpty() ? null : String.join("\n", sealed));
    }

    /** RFC 6265 pair masking: keep cookie names readable, mask every value. */
    private static String maskCookiePairs(String value) {
        StringBuilder out = new StringBuilder();
        for (String piece : value.split(";")) {
            if (out.length() > 0) {
                out.append("; ");
            }
            String[] pair = piece.strip().split("=", 2);
            if (pair.length == 2) {
                out.append(pair[0]).append("=***（总长度 ").append(pair[1].length()).append("）");
            } else {
                out.append(piece.strip());
            }
        }
        return out.toString();
    }

    /**
     * Full response echo for the trail: status line, every response header
     * line by line (multi-valued Set-Cookie included, secret families
     * masked + sealed) and the body within the hot byte budget - an
     * over-budget body ends in the honest total-size pointer, never a
     * silent cut.
     */
    public TrailPiece renderResponseTrail(ExchangeResult result) {
        StringBuilder headerBlock = new StringBuilder();
        StringBuilder text = new StringBuilder();
        List<String> sealed = new ArrayList<>();
        text.append("HTTP 状态：").append(result.statusCode())
                .append(" · ").append(result.elapsedMillis()).append("ms\n");
        int headerLines = 0;
        for (Map.Entry<String, List<String>> entry : result.headers().entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                continue;
            }
            String shown = name.strip().toLowerCase(Locale.ROOT);
            for (String value : entry.getValue()) {
                headerLines++;
                headerBlock.append('\n').append(shown).append(": ");
                if (ExchangeSecretLedger.isSensitiveHeader(shown)) {
                    headerBlock.append(secrets.mask(value));
                    sealed.add("response:" + shown + "\t" + secrets.seal(value));
                } else {
                    headerBlock.append(value);
                }
            }
        }
        text.append("响应头（共 ").append(headerLines).append(" 项）：").append(headerBlock);
        BodyEcho body = renderBody(result);
        String head = text.toString();
        return new TrailPiece(head + '\n' + body.preview(),
                sealed.isEmpty() ? null : String.join("\n", sealed),
                body.full() == null ? null : head + '\n' + body.full());
    }

    /** Body echo: the budgeted preview plus the unclipped twin when a cut hid content. */
    private record BodyEcho(String preview, String full) {
    }

    /** Response body within the hot budget; oversized text stays honest about its total. */
    private BodyEcho renderBody(ExchangeResult result) {
        int budget = bodyBudget();
        int size = result.bodyBytes() == null ? 0 : result.bodyBytes().length;
        String ct = result.contentType();
        boolean looksTextual = ct.isEmpty() || ct.toLowerCase(Locale.ROOT).matches(
                ".*(json|xml|text|html|urlencoded|javascript).*");
        if (!looksTextual) {
            return new BodyEcho("响应体：" + "（响应为 " + ct + " 二进制内容，共 " + size
                    + " 字节，不展开预览）", null);
        }
        if (size <= budget) {
            return new BodyEcho("响应体（字节预算 " + budget + "）：\n" + decode(result.bodyBytes(), size)
                    + (size == 0 ? "（空响应体）" : ""), null);
        }
        String preview = "响应体（字节预算 " + budget + "）：\n" + decode(result.bodyBytes(), budget)
                + "\n（响应共 " + size + " 字节，超出预算 " + (size - budget)
                + " 字节，可点「展开全文」读取完整响应）";
        String full = "响应体（完整，共 " + size + " 字节）：\n" + decode(result.bodyBytes(), size);
        return new BodyEcho(preview, full);
    }

    private static String decode(byte[] bytes, int limit) {
        return new String(bytes, 0, limit, StandardCharsets.UTF_8)
                .replaceAll("\\p{Cntrl}+", " ").strip();
    }

    /** Current body byte budget; an unreadable knob row falls back to the default. */
    private int bodyBudget() {
        try {
            return Math.max(1, Integer.parseInt(configService
                    .getValue(BODY_BUDGET_KEY, String.valueOf(BODY_BUDGET_DEFAULT)).strip()));
        } catch (RuntimeException drift) {
            return BODY_BUDGET_DEFAULT;
        }
    }
}
