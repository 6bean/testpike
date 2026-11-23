package com.sixbean.testpike.common.genkit;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.NonUiActionContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock response template kernel: renders stub response templates against
 * one served request.
 * <p>Resolves {@code {{mock.*}}} placeholders against one served request:
 * echo references — {@code {{mock.pathVar.user}} / {{mock.query.page}} /
 * {{mock.header.X-Trace-Id}} / {{mock.body '$.data.name'}} — plus a closed
 * function whitelist {@code now / randomInt / cycle}. The {@code ${gen.fn(k=v)}}
 * family stays usable inside the same template through
 * {@link GenTemplateResolver} — one parser per namespace, no second syntax is
 * introduced here (single-parser red line). Body paths run on the restricted
 * JSONPath dialect of {@link NonUiActionContract#jsonPathViolation} with the
 * navigation of {@link GenKit#jsonPathExtract} — no second JSONPath grammar.
 * <p>Fail-closed discipline: unknown function, malformed placeholder shape or
 * unresolvable reference throws a human-readable {@link ServiceException}
 * naming the exact token and listing what is available — a raw placeholder
 * never leaks into a served response.
 * <p>Random family mirrors the gen seed discipline: explicit {@code seed=}
 * replays the same value; otherwise a system seed is drawn and recorded as a
 * {fn, seed} event in the shared sink (name + seed only, never values).
 * One resolver instance serves one response; cycle cursors are per reference
 * so repeated references rotate independently within a single render.
 */
public final class MockTemplateResolver {

    /** {{mock.name rest}} placeholder grammar; the name selects the branch. */
    static final Pattern MOCK_PATTERN =
            Pattern.compile("\\{\\{mock\\.([A-Za-z][A-Za-z0-9]*)([^{}]*)\\}\\}", Pattern.DOTALL);

    /** Closed namespace whitelist — new members must land here (single source). */
    static final List<String> MOCK_FUNCTIONS =
            List.of("pathVar", "query", "header", "body", "now", "randomInt", "cycle", "session");

    /** Echo reference shape: dot + bare key (dashes/dots allowed for header names). */
    private static final Pattern ECHO_KEY = Pattern.compile("^\\.([A-Za-z][A-Za-z0-9_.\\-]*)$");

    /** Parenthesised call shape: fn(args...). */
    private static final Pattern CALL_ARGS = Pattern.compile("^\\((.*)\\)$", Pattern.DOTALL);

    private final MockRequestContext request;
    private final List<Map<String, Object>> seedEvents;
    private final Map<String, Integer> cycleCursors = new LinkedHashMap<>();

    public MockTemplateResolver(MockRequestContext request, List<Map<String, Object>> seedEvents) {
        this.request = request == null
                ? new MockRequestContext("GET", "/mocks/none", Map.of(), Map.of(), Map.of(), null)
                : request;
        this.seedEvents = seedEvents;
    }

    /**
     * Resolve all mock placeholders, then all gen calls in the text.
     * No placeholder = identity; null passes through.
     */
    public String render(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String mockResolved = resolveMock(text);
        // gen namespace rides the single existing parser — same seed sink
        return GenTemplateResolver.resolve(mockResolved, seedEvents);
    }

    private String resolveMock(String text) {
        Matcher m = MOCK_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String rest = m.group(2);
            String token = m.group();
            if (!MOCK_FUNCTIONS.contains(name)) {
                throw new ServiceException("mock 模板未注册的函数「" + name + "」，当前可用函数："
                        + String.join("、", MOCK_FUNCTIONS));
            }
            String value = switch (name) {
                case "pathVar" -> echoPathVar(rest, token);
                case "query" -> echoQuery(rest, token);
                case "header" -> echoHeader(rest, token);
                case "body" -> echoBody(rest, token);
                case "now" -> now(rest, token);
                case "randomInt" -> randomInt(rest, token);
                case "session" -> echoSession(token);
                default -> cycle(rest, token);
            };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---------- echo family ----------

    private String echoPathVar(String rest, String token) {
        String key = echoKey(rest, token, "pathVar");
        String value = request.pathVars().get(key);
        if (value == null) {
            throw new ServiceException("mock 模板引用「" + token + "」：本次命中未捕获路径变量 "
                    + key + "（stub 的模板 path 需含 {" + key + "} 段）");
        }
        return value;
    }

    private String echoQuery(String rest, String token) {
        String key = echoKey(rest, token, "query");
        List<String> values = request.query().get(key);
        if (values == null || values.isEmpty()) {
            throw new ServiceException("mock 模板引用「" + token + "」：本次请求不带查询键 "
                    + key + "，可用键：" + available(request.query().keySet()));
        }
        return values.get(0);
    }

    private String echoHeader(String rest, String token) {
        String key = echoKey(rest, token, "header");
        String value = request.headers().get(key.toLowerCase(Locale.ROOT));
        if (value == null) {
            throw new ServiceException("mock 模板引用「" + token + "」：本次请求不带该请求头（键名判定不区分大小写）");
        }
        return value;
    }

    /**
 * Lane echo : the scenario lane the traffic plane
 * resolved for this request — header, cookie, or freshly allocated — so a
 * mocked response can carry its own journey id back to the caller.
 */
    private String echoSession(String token) {
        String value = request.headers().get("x-tp-mock-session");
        if (value == null || value.isBlank()) {
            throw new ServiceException("mock 模板引用「" + token + "」：本次请求未解析出仿真泳道"
                    + "（泳道由流量面按 请求头>Cookie>自动分配 的顺序解析后注入模板上下文）");
        }
        return value;
    }

    private String echoBody(String rest, String token) {
        String trimmed = rest.trim();
        if (!trimmed.startsWith("'") || !trimmed.endsWith("'") || trimmed.length() < 3) {
            throw new ServiceException("mock 模板「" + token + "」需单个带引号的取值路径，"
                    + "正确形态 {{mock.body '$.data.name'}}");
        }
        String path = trimmed.substring(1, trimmed.length() - 1);
        String subsetViolation = NonUiActionContract.jsonPathViolation(path);
        if (subsetViolation != null) {
            throw new ServiceException("mock 模板 body 取值路径「" + path + "」不合法：" + subsetViolation);
        }
        if (request.bodyJson() == null || request.bodyJson().isBlank()) {
            throw new ServiceException("mock 模板引用「" + token + "」：本次请求无正文，无法取值");
        }
        return GenKit.jsonPathExtract(request.bodyJson(), path);
    }

    /** Dot-key form shared by pathVar/query/header echoes. */
    private static String echoKey(String rest, String token, String fn) {
        Matcher m = ECHO_KEY.matcher(rest);
        if (!m.matches()) {
            throw new ServiceException("mock 模板「" + token + "」形态不合法：{{mock." + fn
                    + ".键名}} 点号后需非空键名");
        }
        return m.group(1);
    }

    // ---------- function family ----------

    private String now(String rest, String token) {
        List<String> positional = callPositional(rest, token, true);
        if (positional.size() > 1) {
            throw new ServiceException("mock 模板「" + token + "」至多一个日期格式参数");
        }
        // GenKit.dateTime owns the default pattern — one clock implementation
        return GenKit.dateTime(positional.isEmpty() ? null : positional.get(0));
    }

    private String randomInt(String rest, String token) {
        List<String> positional = callPositional(rest, token, false);
        Map<String, String> named = callNamed(rest);
        if (positional.size() != 2) {
            throw new ServiceException("mock 模板「" + token + "」需两个整数边界，"
                    + "正确形态 {{mock.randomInt(1,99)}}");
        }
        int min = parseIntBound(positional.get(0), "min", token);
        int max = parseIntBound(positional.get(1), "max", token);
        if (min > max) {
            throw new ServiceException("mock 模板「" + token + "」边界颠倒：min=" + min + " > max=" + max);
        }
        Long seed = seedOf(named.get("seed"), token);
        if (seed == null) {
            seed = GenTemplateResolver.newRandomSeed();
        }
        if (seedEvents != null) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("fn", "randomInt");
            event.put("seed", seed);
            seedEvents.add(event);
        }
        return String.valueOf(new Random(seed).nextInt(max - min + 1) + min);
    }

    private String cycle(String rest, String token) {
        List<String> positional = callPositional(rest, token, false);
        if (positional.isEmpty() || positional.size() > 2) {
            throw new ServiceException("mock 模板「" + token + "」需一个列表引用（请求查询键多值）"
                    + "与可选步长，正确形态 {{mock.cycle('ids',5)}}");
        }
        String key = positional.get(0);
        int step = positional.size() == 2 ? parseIntBound(positional.get(1), "step", token) : 1;
        if (step < 1) {
            throw new ServiceException("mock 模板「" + token + "」步长必须 ≥ 1，当前 " + step);
        }
        List<String> values = request.query().get(key);
        if (values == null || values.isEmpty()) {
            throw new ServiceException("mock 模板 cycle 引用「" + key + "」：本次请求不带该查询键的多值列表，"
                    + "可用键：" + available(request.query().keySet()));
        }
        int pos = cycleCursors.getOrDefault(token, 0);
        cycleCursors.put(token, pos + step);
        return values.get(Math.floorMod(pos, values.size()));
    }

    // ---------- argument plumbing ----------

    /** Positional args of a parenthesised call (quoted values unwrapped). */
    private List<String> callPositional(String rest, String token, boolean optionalCall) {
        if (rest == null || rest.isEmpty()) {
            if (optionalCall) {
                return List.of();
            }
            throw new ServiceException("mock 模板「" + token + "」需要括号参数，正确形态见提示");
        }
        Matcher m = CALL_ARGS.matcher(rest);
        if (!m.matches()) {
            throw new ServiceException("mock 模板「" + token + "」参数需写在括号内");
        }
        List<String> positional = new ArrayList<>();
        for (String segment : splitTopLevel(m.group(1))) {
            String s = segment.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("'")) {
                positional.add(unquote(s, token));
            } else if (ECHO_NAMED.matcher(s).matches()) {
                // named args (seed=...) are consumed separately
                continue;
            } else {
                positional.add(s);
            }
        }
        return positional;
    }

    /** Named args (k=v) of a parenthesised call. */
    private Map<String, String> callNamed(String rest) {
        Map<String, String> named = new LinkedHashMap<>();
        Matcher call = rest == null ? null : CALL_ARGS.matcher(rest);
        if (call == null || !call.matches()) {
            return named;
        }
        for (String segment : splitTopLevel(call.group(1))) {
            Matcher m = ECHO_NAMED.matcher(segment.trim());
            if (m.matches()) {
                named.put(m.group(1), m.group(2));
            }
        }
        return named;
    }

    /** Named-argument shape k=v (same convention as the gen family). */
    private static final Pattern ECHO_NAMED =
            Pattern.compile("^([A-Za-z][A-Za-z0-9]*)=(.*)$", Pattern.DOTALL);

    /** Comma split that respects single quotes (dates/payloads may carry commas). */
    static List<String> splitTopLevel(String rawArgs) {
        List<String> segments = new ArrayList<>();
        if (rawArgs == null) {
            return segments;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < rawArgs.length(); i++) {
            char c = rawArgs.charAt(i);
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == ',' && !inQuote) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
    }

    private static String unquote(String value, String token) {
        if (!value.endsWith("'") || value.length() < 2) {
            throw new ServiceException("mock 模板「" + token + "」参数引号不成对");
        }
        String inner = value.substring(1, value.length() - 1);
        if (inner.contains("'")) {
            throw new ServiceException("mock 模板「" + token + "」参数引号不成对");
        }
        return inner;
    }

    private static int parseIntBound(String raw, String field, String token) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("mock 模板「" + token + "」的 " + field + " 必须是整数，当前「" + raw + "」");
        }
    }

    private static Long seedOf(String raw, String token) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("mock 模板「" + token + "」的 seed 必须是数字（按种子复现），当前「" + raw + "」");
        }
    }

    private static String available(Iterable<String> keys) {
        List<String> out = new ArrayList<>();
        keys.forEach(k -> out.add("「" + k + "」"));
        return out.isEmpty() ? "（无）" : String.join("、", out);
    }
}
