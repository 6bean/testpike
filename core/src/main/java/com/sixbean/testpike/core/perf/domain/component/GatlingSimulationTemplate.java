package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Gatling simulation template generator (no runtime string concatenation into
 * code positions = slot filling only, same discipline inside the adapter layer).
 * <p>Input = normalized slices of a saved scenario view; output = compilable
 * <b>Java DSL</b> simulation source (io.gatling.javaapi.core/http, 3.13.5).
 * Route decision : the Scala compiler is not
 * distributed with the engine (gatling-compiler 404s on Central) while plain
 * javac against gatling-core-java/gatling-http-java compiles and runs — so the
 * generated source is Java, compiled in-process with javax.tools, launched
 * through the classpath form (the 3.x CLI has no source-folder option).
 * <p>Three pressure forms map to the official open-injection steps as proven by
 * javap: CONSTANT_RPS→constantUsersPerSec(double).during(long) /
 * RAMP→rampUsersPerSec(double).to(double).during(long) + hold /
 * SPIKE→sequential rise/hold/fall/tail legs (PerfSpikeSegments, A-12; the
 * old base-constant + stressPeakUsers stack ran twice the declared window). Steps
 * take only the method/url slots of
 * contract-approved interface actions; special characters are escaped into
 * string literals, never raw DSL text into structural positions.
 * <p>Pure text generator (no IO, no engine calls); artifact writing and the
 * javac compile belong to GatlingAdapter.
 */
public final class GatlingSimulationTemplate {

    /** Java string-literal escape (backslash/double-quote/CR/LF) = the single slot-fill entry. */
    private static String javaLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    /** Imports the var-chain helpers need (emitted only when the sim uses the pool). */
    private static final String TP_VAR_IMPORTS = """
            import com.fasterxml.jackson.databind.JsonNode;
            import com.fasterxml.jackson.databind.ObjectMapper;
            import java.util.Iterator;
            import java.util.Map;""";

    /**
 * Class-body members of the var chain (emitted only when the sim uses the
 * pool). Two shapes (A-6, DSL v2 : the plain session pool for
 * PER_VUSER (shareN=1, every user owns its pool — identical to the pre-A-6
 * output, zero regression) and the share-bus variant for SHARED /
 * PER_SESSION_N — bucket membership rides Session.userId (1-based; the
 * divisor TP_SHARE_N is MAX_VALUE for SHARED so the whole run is one
 * bucket), the bucket head publishes captured leaves to TP_SHARED_VARS and
 * members that skipped the login resolve {{VAR:}} through the bus fallback
 * (session first, then bus; still-undefined fails fast with the same
 * corpus). A race where a member consumes before the head published fails
 * the iteration honestly — never a fake value.
 */
    private static String tpVarMembers(int shareN) {
        if (shareN <= 1) {
            return """
                    // ---- testPike var-chain helpers (generated, do not hand-edit) ----
                    static final ObjectMapper TP_MAPPER = new ObjectMapper();

                    /** Single {{VAR:}} resolver over the session pool (undefined = fail fast naming it). */
                    static String tpVar(io.gatling.javaapi.core.Session s, String text) {
                        StringBuilder out = new StringBuilder();
                        int i = 0;
                        while (true) {
                            int a = text.indexOf("{{VAR:", i);
                            if (a < 0) {
                                out.append(text, i, text.length());
                                break;
                            }
                            int b = text.indexOf("}}", a);
                            out.append(text, i, a);
                            String name = text.substring(a + 6, b);
                            Object v = s.get(name);
                            if (v == null) {
                                throw new IllegalArgumentException("变量 " + name + " 未定义（检查前序 saveAs）");
                            }
                            out.append(v);
                            i = b + 2;
                        }
                        return out.toString();
                    }

                    /** Scalar-leaf expansion mirroring the executor jsonpath_subset (non-JSON = throws). */
                    static io.gatling.javaapi.core.Session tpExpandJsonLeaves(io.gatling.javaapi.core.Session session,
                                                                              String name, String raw) {
                        try {
                            return tpWalkLeaves(session, name, TP_MAPPER.readTree(raw));
                        } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException parseFailure) {
                            throw new IllegalStateException("变量捕获失败：响应体不是合法 JSON（" + name + "）", parseFailure);
                        }
                    }

                    static io.gatling.javaapi.core.Session tpWalkLeaves(io.gatling.javaapi.core.Session session,
                                                                        String key, JsonNode node) {
                        if (node.isContainerNode()) {
                            if (node.isArray()) {
                                for (int idx = 0; idx < node.size(); idx++) {
                                    session = tpWalkLeaves(session, key + "[" + idx + "]", node.get(idx));
                                }
                            } else {
                                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                                while (fields.hasNext()) {
                                    Map.Entry<String, JsonNode> e = fields.next();
                                    session = tpWalkLeaves(session, key + "." + e.getKey(), e.getValue());
                                }
                            }
                            return session;
                        }
                        return node.isNull() ? session : session.set(key, node.asText());
                    }
                    // ---- end var-chain helpers ----""";
        }
        return """
                // ---- testPike var-chain helpers (generated, do not hand-edit) ----
                static final ObjectMapper TP_MAPPER = new ObjectMapper();
                static final java.util.concurrent.ConcurrentHashMap<String, String> TP_SHARED_VARS =
                        new java.util.concurrent.ConcurrentHashMap<>();
                static final int TP_SHARE_N = %s;

                /** Once-per-bucket login claims (auth step key = bucket + step id). */
                static final java.util.concurrent.ConcurrentHashMap<String, Boolean> TP_BUCKET_CLAIM =
                        new java.util.concurrent.ConcurrentHashMap<>();

                /** Bucket slot prefix for this user under the declared login strategy. */
                static String tpBucket(io.gatling.javaapi.core.Session s) {
                    return "b" + ((s.userId() - 1) / TP_SHARE_N) + ".";
                }

                /** Single {{VAR:}} resolver: session pool first, then the share bus (undefined = fail fast naming it). */
                static String tpVar(io.gatling.javaapi.core.Session s, String text) {
                    StringBuilder out = new StringBuilder();
                    int i = 0;
                    while (true) {
                        int a = text.indexOf("{{VAR:", i);
                        if (a < 0) {
                            out.append(text, i, text.length());
                            break;
                        }
                        int b = text.indexOf("}}", a);
                        out.append(text, i, a);
                        String name = text.substring(a + 6, b);
                        Object v = s.get(name);
                        if (v == null) {
                            v = TP_SHARED_VARS.get(tpBucket(s) + name);
                        }
                        if (v == null) {
                            throw new IllegalArgumentException("变量 " + name + " 未定义（检查前序 saveAs）");
                        }
                        out.append(v);
                        i = b + 2;
                    }
                    return out.toString();
                }

                /** Scalar-leaf expansion mirroring the executor jsonpath_subset (non-JSON = throws). */
                static io.gatling.javaapi.core.Session tpExpandJsonLeaves(io.gatling.javaapi.core.Session session,
                                                                          String name, String raw) {
                    try {
                        return tpWalkLeaves(session, name, TP_MAPPER.readTree(raw));
                    } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException parseFailure) {
                        throw new IllegalStateException("变量捕获失败：响应体不是合法 JSON（" + name + "）", parseFailure);
                    }
                }

                /** Gated capture: expand leaves into the session AND publish them to the share bus. */
                static io.gatling.javaapi.core.Session tpShareJsonLeaves(io.gatling.javaapi.core.Session session,
                                                                         String name, String raw) {
                    Map<String, String> leaves = new java.util.LinkedHashMap<>();
                    try {
                        tpCollectLeaves(TP_MAPPER.readTree(raw), name, leaves);
                    } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException parseFailure) {
                        throw new IllegalStateException("变量捕获失败：响应体不是合法 JSON（" + name + "）", parseFailure);
                    }
                    String bucket = tpBucket(session);
                    io.gatling.javaapi.core.Session updated = session;
                    for (Map.Entry<String, String> e : leaves.entrySet()) {
                        TP_SHARED_VARS.put(bucket + e.getKey(), e.getValue());
                        updated = updated.set(e.getKey(), e.getValue());
                    }
                    return updated;
                }

                static void tpCollectLeaves(JsonNode node, String key, Map<String, String> out) {
                    if (node.isContainerNode()) {
                        if (node.isArray()) {
                            for (int idx = 0; idx < node.size(); idx++) {
                                tpCollectLeaves(node.get(idx), key + "[" + idx + "]", out);
                            }
                        } else {
                            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                            while (fields.hasNext()) {
                                Map.Entry<String, JsonNode> e = fields.next();
                                tpCollectLeaves(e.getValue(), key + "." + e.getKey(), out);
                            }
                        }
                        return;
                    }
                    if (!node.isNull()) {
                        out.put(key, node.asText());
                    }
                }

                static io.gatling.javaapi.core.Session tpWalkLeaves(io.gatling.javaapi.core.Session session,
                                                                    String key, JsonNode node) {
                    if (node.isContainerNode()) {
                        if (node.isArray()) {
                            for (int idx = 0; idx < node.size(); idx++) {
                                session = tpWalkLeaves(session, key + "[" + idx + "]", node.get(idx));
                            }
                        } else {
                            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                            while (fields.hasNext()) {
                                Map.Entry<String, JsonNode> e = fields.next();
                                session = tpWalkLeaves(session, key + "." + e.getKey(), e.getValue());
                            }
                        }
                        return session;
                    }
                    return node.isNull() ? session : session.set(key, node.asText());
                }
                // ---- end var-chain helpers ----""".formatted(shareN == Integer.MAX_VALUE
                ? "Integer.MAX_VALUE" : String.valueOf(shareN));
    }

    /**
     * Slot fill for one text position: plain escaped literal when the step text
     * carries no {{VAR:}}, session-lambda resolver call when it does (mirrors
     * the k6 placeholderWrapped — same intent-against-render discipline, and
     * each engine slot overload pair was javap-proven before emitting).
     */
    private static String varAwareSlot(String raw) {
        String literal = "\"" + javaLiteral(raw) + "\"";
        return raw.contains("{{VAR:") ? "s -> tpVar(s, " + literal + ")" : literal;
    }

    /**
     * Injection steps: pressure model → comma-joined OpenInjectionStep expressions
     * for setUp(scn.injectOpen(...)) (missing key = illegal input, already gated by
     * the caller's validation chain).
     */
    static String injectionBlock(String model, JsonNode config) {
        return switch (model) {
            // B-2 — four new models, all forms javap-proven on
            // gatling-core-java 3.13.5 (incrementUsersPerSec → Stairs.times →
            // eachLevelLasting → startingFrom; nothingFor for idle gaps).
            // FIXED_VU rides two injection families: a wall-clock window is a
            // closed pool (constantConcurrentUsers.during, render picks
            // injectClosed), loops-only is atOnceUsers + the repeat loop bound
            // compiled into the chain by execBlock.
            case "FIXED_VU" -> {
                yield isClosedWindow(config)
                        ? "                constantConcurrentUsers(" + config.get("vu").asInt()
                                + ").during(" + config.get("durationSeconds").asInt() + ")"
                        : "                atOnceUsers(" + config.get("vu").asInt() + ")";
            }
            case "LADDER" -> "                incrementUsersPerSec("
                    + config.get("stepRps").asInt() + ")\n"
                    + "                    .times(" + config.get("steps").asInt() + ")\n"
                    + "                    .eachLevelLasting(" + config.get("stepSeconds").asInt() + ")\n"
                    + "                    .startingFrom(" + config.get("startRps").asInt() + ")";
            case "BUSINESS_CURVE" -> curveInjection(config);
            case "SOAK" -> "                constantUsersPerSec(" + config.get("targetRps").asInt()
                    + ").during(" + config.get("durationHours").asInt() * 3600 + ")";
            case "CONSTANT_RPS" -> "                constantUsersPerSec(" + config.get("targetRps").asInt()
                    + ").during(" + config.get("durationSeconds").asInt() + ")";
            case "RAMP" -> "                rampUsersPerSec(" + config.get("startRps").asInt()
                    + ").to(" + config.get("peakRps").asInt() + ").during("
                    + config.get("rampSeconds").asInt() + "),\n"
                    + "                constantUsersPerSec(" + config.get("peakRps").asInt()
                    + ").during(" + config.get("holdSeconds").asInt() + ")";
            case "SPIKE" -> {
                // A-12 fix : sequential rise/hold/fall/tail legs inside the one
                // declared window (segments single-sourced from PerfSpikeSegments like
                // the k6 template). The old shape stacked a base constant + stressPeakUsers
                // for durationSeconds EACH = a staircase twice as long as requested.
                PerfSpikeSegments segs = PerfSpikeSegments.derive(
                        config.get("durationSeconds").asInt());
                int base = config.get("baseRps").asInt();
                int peak = config.get("peakRps").asInt();
                yield "                rampUsersPerSec(" + base + ").to(" + peak + ").during("
                        + segs.riseSeconds() + "),\n"
                        + "                constantUsersPerSec(" + peak + ").during("
                        + segs.holdSeconds() + "),\n"
                        + "                rampUsersPerSec(" + peak + ").to(" + base + ").during("
                        + segs.fallSeconds() + "),\n"
                        + "                constantUsersPerSec(" + base + ").during("
                        + segs.tailSeconds() + ")";
            }
            default -> throw new IllegalArgumentException("未知压力形态: " + model);
        };
    }

    /**
     * FIXED_VU termination form (single source for the injection block and the
     * render chain wrapping): a positive durationSeconds = closed wall-clock pool
     * (injectClosed + constantConcurrentUsers); loops-only bounds the chain via
     * repeat(loops) instead. The save gate guarantees at least one of the keys.
     */
    private static boolean isClosedWindow(JsonNode config) {
        JsonNode window = config.get("durationSeconds");
        return window != null && window.isNumber() && window.asInt() > 0;
    }

    /**
     * BUSINESS_CURVE (B-2): sequential constantUsersPerSec legs on the hour grid
     * (PerfCurveSegments is the single source shared with the k6 template). Idle
     * hours become nothingFor gaps — never a 0-rate leg, which would fake a
     * paused-but-loaded stage in the attribution timeline.
     */
    private static String curveInjection(JsonNode config) {
        List<PerfCurveSegments.Segment> segs = PerfCurveSegments.derive(
                config, config.get("durationSeconds").asInt());
        List<String> legs = new ArrayList<>();
        int cursor = 0;
        for (PerfCurveSegments.Segment seg : segs) {
            int gap = seg.startTimeSeconds() - cursor;
            if (gap > 0) {
                legs.add("                nothingFor(" + gap + ")");
            }
            legs.add("                constantUsersPerSec(" + seg.rate()
                    + ").during(" + seg.spanSeconds() + ")");
            cursor = seg.startTimeSeconds() + seg.spanSeconds();
        }
        return String.join(",\n", legs);
    }

    /** Request legs: step sequence → scenario exec chain (flat scenarios).
     *  @see #execBlock(JsonNode, int, List) for the per-link subset form. */
    static String execBlock(JsonNode steps, int shareN) {
        return execBlock(steps, shareN, null, false);
    }

    /** Request legs with the CF-9 load-test marker applied by default (step
     *  headers win when they declare the same name — same override semantics
     *  as the k6 side merging HEADERS under per-step headers). */
    static String execBlock(JsonNode steps, int shareN, boolean headerMarkIsolation) {
        return execBlock(steps, shareN, null, headerMarkIsolation);
    }

    /**
 * Request legs for ONE population of a mix (B-3 c-2γ): {@code indices}
 * selects which entries of the step array join this link's chain, while
 * every request name keeps its GLOBAL step number ({@code step<idx>-METHOD}
 * and the auth bucket-claim key) so two links never collapse into one
 * report series and a gated login claim stays link-distinct. Null indices
 * = the whole array (flat scenarios, historical shape).
 * <p>Slot consumption mirrors the k6 template (DSL v2 segment): step headers via
 * {@code .header(k, v)}, a JSON body via {@code .body(StringBody(payload))}
 * with Content-Type defaulted, expect.status via {@code status.is(n)} and
 * expect.body rows via substring/jsonpath checks — a failed check counts into
 * the Gatling KO statistics, so a business mismatch rides the error line the
 * control plane already reads (single judgement, no second spec).
 * <p>Auth bucketing: the k6 side keeps its tag-name convention; the Gatling
 * request names keep stepN (the perf_worker buckets auth points by the shared
 * contract on the k6 side; renaming here would break existing report lines).
 * <p>A-6 login strategy (DSL v2 : when the declared strategy shares
 * sessions (shareN &gt; 1), each auth-flagged leg rides inside
 * {@code .doIf(<once-per-bucket claim>).then(exec(...), exec(tpShareJsonLeaves))}
 * so exactly one user per bucket performs the login and its captured leaves
 * publish to the share bus; every other user skips the leg and consumes via
 * the bus fallback in tpVar. PER_VUSER (shareN = 1) keeps the unwrapped
 * session-pool shape (zero regression). The chain-builder forms are
 * javap-proven: doIf(Function&lt;Session,Boolean&gt;).then(Executable...)
 * and HttpRequestActionBuilder implements Executable (ActionBuilder extends
 * Executable in gatling-core-java 3.13.5). */
    static String execBlock(JsonNode steps, int shareN, List<Integer> indices,
                            boolean headerMarkIsolation) {
        boolean gated = shareN > 1;
        List<String> lines = new ArrayList<>();
        for (int slot = 0; slot < (indices == null ? steps.size() : indices.size()); slot++) {
            int idx = indices == null ? slot : indices.get(slot);
            JsonNode step = steps.get(idx);
            int globalNumber = idx + 1;
            String method = step.path("method").asText("GET");
            String url = varAwareSlot(step.path("url").asText(""));
            List<String> chain = new ArrayList<>();
            String legIndent = gated ? "                    " : "                ";
            chain.add(legIndent + ".exec(http(\"step" + globalNumber + "-" + method + "\")\n"
                    + "                        ." + method.toLowerCase() + "(" + url + ")");
            JsonNode headers = step.get("headers");
            boolean hasContentType = false;
            // CF-9 marker baseline: HEADER_MARK scenarios ride the same
            // X-Load-Test contract the k6 side emits, BUT a step that declares
            // the header itself keeps its own value (override wins — mirrors
            // Object.assign({}, HEADERS, stepHeaders) on the k6 template; the
            // guard also prevents a duplicated header line under Gatling's
            // append-style builder).
            boolean stepDeclaresMarker = false;
            if (headers != null && headers.isObject()) {
                for (var e : (Iterable<java.util.Map.Entry<String, JsonNode>>) headers::fields) {
                    if (headerMarkIsolation && e.getKey().equalsIgnoreCase("X-Load-Test")) {
                        stepDeclaresMarker = true;
                    }
                }
            }
            if (headerMarkIsolation && !stepDeclaresMarker) {
                chain.add("                        .header(\"X-Load-Test\", \"1\")");
            }
            if (headers != null && headers.isObject()) {
                for (var e : (Iterable<java.util.Map.Entry<String, JsonNode>>) headers::fields) {
                    chain.add("                        .header(\"" + javaLiteral(e.getKey())
                            + "\", " + varAwareSlot(e.getValue().asText("")) + ")");
                    if (e.getKey().equalsIgnoreCase("Content-Type")) {
                        hasContentType = true;
                    }
                }
            }
            JsonNode body = step.get("body");
            if (body != null && !body.isNull()) {
                String payload = body.isTextual() ? body.asText() : body.toString();
                chain.add("                        .body(StringBody(" + varAwareSlot(payload) + "))");
                if (!hasContentType) {
                    chain.add("                        .header(\"Content-Type\", \"application/json\")");
                }
            }
            JsonNode expect = step.get("expect");
            if (expect != null && expect.isObject()) {
                if (expect.has("status") && !expect.get("status").isNull()) {
                    chain.add("                        .check(status().is("
                            + expect.get("status").asInt() + "))");
                }
                JsonNode rows = expect.get("body");
                if (rows != null && rows.isArray()) {
                    for (JsonNode row : rows) {
                        String text = javaLiteral(row.path("text").asText(""));
                        if ("exact".equals(row.path("matchType").asText("contains"))) {
                            chain.add("                        .check(bodyString().find().is(\""
                                    + text + "\"))");
                        } else {
                            chain.add("                        .check(substring(\"" + text + "\").exists())");
                        }
                    }
                }
            }
            // B-1 capture leg: save the raw body into the session, then expand
            // scalar JSON leaves under the saveAs name (key shape mirrors k6/
            // executor; a non-JSON body throws = honest iteration failure).
            // A-6 gated captures publish to the share bus instead (tpShareJsonLeaves).
            String saveAs = step.path("saveAs").asText("");
            boolean captures = !saveAs.isBlank();
            if (captures) {
                chain.add("                        .check(bodyString().saveAs(\"tp_resp_"
                        + javaLiteral(saveAs) + "\"))");
            }
            // `.exec(http("name")` left the .exec( open; every slot line (.post/.header/
            // .body/.check) is self-closed, so the leg ends with a single closing paren
            // (the template's scenario chain supplies the terminating semicolon).
            chain.add(legIndent + ")");
            String leg = String.join("\n", chain);
            String captureLine = captures
                    ? (gated
                        ? "                    exec(s -> tpShareJsonLeaves(s, \"" + javaLiteral(saveAs)
                            + "\", s.getString(\"tp_resp_" + javaLiteral(saveAs) + "\")))"
                        : "                .exec(s -> tpExpandJsonLeaves(s, \"" + javaLiteral(saveAs)
                            + "\", s.getString(\"tp_resp_" + javaLiteral(saveAs) + "\")))")
                    : null;
            boolean isAuth = step.path("auth").asBoolean(false);
            if (gated && isAuth) {
                // Auth legs run once per bucket: the claiming user executes the
                // leg + publishes; others evaluate the claim predicate (false)
                // and skip. The claim rides ConcurrentHashMap.putIfAbsent == null.
                lines.add("                .doIf(s -> TP_BUCKET_CLAIM.putIfAbsent(tpBucket(s)"
                        + " + \"step" + globalNumber + "\", Boolean.TRUE) == null)");
                // The leg already self-closes (`.exec(http(...)…)` → `exec(http(...…)`
                // with the same trailing paren line); CoreDsl.exec(Executable...)
                // takes the request builder because ActionBuilder extends Executable.
                List<String> inner = new ArrayList<>();
                inner.add(leg.replaceFirst("^\\s+\\.exec\\(", "                    exec("));
                if (captureLine != null) {
                    inner.add(captureLine);
                }
                lines.add("                    .then(" + String.join(",\n", inner) + ")");
                continue;
            }
            lines.add(leg);
            if (captureLine != null) {
                // Self-closed line: .exec( + the tpExpandJsonLeaves call both close here.
                lines.add(captureLine);
            }
        }
        return String.join("\n", lines);
    }

    /** A-6: strategy string → bucket divisor (1 = PER_VUSER, N = PER_SESSION_N,
     *  MAX_VALUE = SHARED = one bucket for the run). Unknown spellings never
     *  reach here (save-time normalizeStrategy is the single validation point). */
    static int shareDivisor(String loginStrategy) {
        if (loginStrategy == null || loginStrategy.isBlank() || "PER_VUSER".equals(loginStrategy)) {
            return 1;
        }
        if ("SHARED".equals(loginStrategy)) {
            return Integer.MAX_VALUE;
        }
        if (loginStrategy.startsWith("PER_SESSION_")) {
            return Integer.parseInt(loginStrategy.substring("PER_SESSION_".length()));
        }
        return 1;
    }

    /**
     * Render the full simulation source.
     *
     * @param simulationName generated class simple name (uniquified by the caller so concurrent compiles never overwrite)
     * @param model          pressure form
     * @param config         pressure parameters (required keys guaranteed by the scenario validation chain)
     * @param steps          request steps (already passed the D2 interface-action gate)
     * @param baseUrl        environment base URL ({{ENV:}} placeholder passes through verbatim = executor-side resolution single point)
     * @param loginStrategy  A-6 D4 strategy (PER_VUSER default / SHARED / PER_SESSION_N — decides the gate shape)
     */
    public String render(String simulationName, String model, JsonNode config,
                         JsonNode steps, String baseUrl, String loginStrategy) {
        return render(simulationName, model, config, steps, baseUrl, loginStrategy, null, false);
    }

    /**
 * Data-pool feeder line ( segment B②a, emitted only when the simulation
 * binds a dataset). The csv feeder places each row's columns directly into
 * the session — the SAME store the tpVar {{VAR:}} resolver reads — so column
 * values ride the single resolver with zero new syntax (single-parser red
 * line). Strategy mapping (native forms only, recon ruling):
 * SEQUENTIAL_EXCLUSIVE → queue (single copy, file order, drained = the
 * iteration fails honestly = native lease semantics);
 * CYCLE → circular (never starves a long run).
 * VUSER_EXCLUSIVE never reaches here — GatlingAdapter rejects it at compile
 * time (the 3.13.5 Java DSL exposes queue/random/shuffle/circular/shard but
 * no per-user private-slice feeder, javap-proven).
 * The file name escapes into a string literal only; the sidecar sits in the
 * compiled classes directory = on the engine classpath (file feeder resource
 * resolution, cwd-independent — the manifest classesDir is what perf_worker
 * puts on -cp).
 */
    static String feedLine(TemplateDataset dataset) {
        String feeder = PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE.equals(dataset.allocMode())
                ? ".queue()" : ".circular()";
        return "                .feed(csv(\"" + javaLiteral(dataset.fileName()) + "\")"
                + feeder + ")";
    }

    /**
 * Render the full simulation source with optional dataset binding.
 *
 * @param dataset data pool (null = pool-free scenario, previous shape —
 * zero regression on static simulations)
 * @param headerMarkIsolation CF-9: scenario declared HEADER_MARK isolation
 * = every outbound request carries the X-Load-Test marker
 * (same contract the k6 template emits — one vocabulary for
 * shadow-traffic filtering across engines)
 */
    public String render(String simulationName, String model, JsonNode config,
                         JsonNode steps, String baseUrl, String loginStrategy,
                         TemplateDataset dataset) {
        return render(simulationName, model, config, steps, baseUrl, loginStrategy,
                dataset, false);
    }

    public String render(String simulationName, String model, JsonNode config,
                         JsonNode steps, String baseUrl, String loginStrategy,
                         TemplateDataset dataset, boolean headerMarkIsolation) {
        int shareN = shareDivisor(loginStrategy);
        boolean gated = shareN > 1;
        String exec = execBlock(steps, shareN, headerMarkIsolation);
        // The var-chain pool/imports are emitted only when the simulation
        // actually carries a capture or a {{VAR:}} reference — static scenarios
        // keep the previous minimal shape (zero regression, mirror of k6 render).
        // A-6: a gated (shared-session) scenario always needs the bucket members.
        boolean usesVars = gated || exec.contains("tpVar(s,")
                || exec.contains("tpExpandJsonLeaves");
        String varImports = usesVars ? "\n" + TP_VAR_IMPORTS : "";
        String execStaticImport = gated
                ? varImports + "\nimport static io.gatling.javaapi.core.CoreDsl.exec;" : varImports;
        // data pool: csv static import + the feeder line right after the
        // scenario declaration (Gatling feeds the session before each iteration,
        // so {{VAR:column}} slots inside the exec chain resolve from row values;
        // the Feeds surface is javap-proven on StructureBuilder in 3.13.5 and
        // the javac segment of GatlingAdapter.compile is the live signature
        // check — a wrong form never leaves the compile stage).
        String csvImport = dataset != null
                ? "\nimport static io.gatling.javaapi.core.CoreDsl.csv;" : "";
        String feed = dataset != null ? "\n" + feedLine(dataset) : "";
        String varMembers = usesVars ? "\n" + tpVarMembers(shareN) : "";
        // B-2 FIXED_VU chain shape (think time rides the chain tail so a closed
        // pool pauses per user per loop — the same slot the k6 side fills with
        // sleep; loops-only wraps the legs in repeat(loops) — the only way a
        // per-user count bounds a Gatling chain).
        int thinkMs = "FIXED_VU".equals(model) ? config.path("thinkTimeMs").asInt(0) : 0;
        String pauseTail = thinkMs > 0 ? "\n                .pause(" + thinkMs + ")" : "";
        boolean loopBounded = "FIXED_VU".equals(model) && !isClosedWindow(config);
        boolean closedInjection = "FIXED_VU".equals(model) && isClosedWindow(config);
        String injectKind = closedInjection ? "injectClosed" : "injectOpen";

        // B-3 : one PerfMixGroups source (shared with the k6
        // side) decides both faces of the mix. A flat chain renders EXACTLY the
        // historical one-scenario simulation (zero regression = the acceptance
        // criterion); a declared mix renders ONE POPULATION PER LINK — its own
        // scenario carrying only its member steps (global step numbering keeps
        // report series distinct) with its own weight-scaled injection inside
        // the one setUp(...) varargs call (Simulation$SetUp.setUp(PopulationBuilder...)
        // and ScenarioBuilder.injectOpen/injectClosed → PopulationBuilder are
        // javap-proven in 3.13.5; the javac stage is the live proof). The links
        // press concurrently at the declared ratio — a uniform rate would fake
        // the mix, the same discipline as the k6 c-2β per-link executor blocks.
        PerfMixGroups groups = PerfMixGroups.derive(steps);
        StringBuilder scenarioSection = new StringBuilder();
        StringBuilder populations = new StringBuilder();
        if (groups.isFlat()) {
            String chain = exec + pauseTail;
            if (loopBounded) {
                // The wrapped body must open with a CoreDsl static expression
                // (exec(...)/doIf(...) — javap-proven both exist); the first line's
                // leading dot is exactly that removal. The feeder stays at scenario
                // level: one row per user iteration, rows reused inside the loop
                // (documented pool semantics of the bounded form).
                chain = "                .repeat(" + config.get("loops").asInt() + ").on(\n"
                        + unwrapLeadingCombinator(exec) + pauseTail + "\n                )";
            }
            scenarioSection.append("                    ScenarioBuilder scn = scenario(\"")
                    .append(simulationName).append("\")").append(feed).append("\n")
                    .append(chain).append(";");
            populations.append("scn.").append(injectKind).append("(\n")
                    .append(injectionBlock(model, config))
                    .append("\n                        )");
        } else {
            List<String> legs = new ArrayList<>();
            for (PerfMixGroups.Link link : groups.links()) {
                String linkExec = execBlock(steps, shareN, link.stepIndices(), headerMarkIsolation);
                String linkChain = linkExec + pauseTail;
                if (loopBounded) {
                    linkChain = "                .repeat(" + config.get("loops").asInt()
                            + ").on(\n" + unwrapLeadingCombinator(linkExec) + pauseTail
                            + "\n                )";
                }
                String pop = "                    " + groups.funcName(link) + "." + injectKind + "(\n"
                        + injectionBlock(model, groups.scaledConfig(config, link))
                        + "\n                    )";
                legs.add(pop);
                scenarioSection.append("                    ScenarioBuilder ")
                        .append(groups.funcName(link)).append(" = scenario(\"")
                        .append(javaLiteral(groups.scenarioKey(link))).append("\")")
                        .append("\n")
                        .append(linkChain).append(";\n\n");
            }
            scenarioSection.setLength(scenarioSection.length() - 2);
            populations.append(String.join(",\n", legs)).append("\n                        ");
        }
        return """
                package com.sixbean.testpike.perf.gen;

                import io.gatling.javaapi.core.ScenarioBuilder;
                import io.gatling.javaapi.core.Simulation;
                import io.gatling.javaapi.http.HttpProtocolBuilder;
                %s
                import static io.gatling.javaapi.core.CoreDsl.StringBody;
                import static io.gatling.javaapi.core.CoreDsl.bodyString;
                import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
                import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
                import static io.gatling.javaapi.core.CoreDsl.incrementUsersPerSec;
                import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
                import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
                import static io.gatling.javaapi.core.CoreDsl.nothingFor;
                import static io.gatling.javaapi.core.CoreDsl.scenario;
                import static io.gatling.javaapi.core.CoreDsl.substring;
                import static io.gatling.javaapi.http.HttpDsl.http;
                import static io.gatling.javaapi.http.HttpDsl.status;%s

                /**
                 * Generated by the testPike perf control plane (template = GatlingSimulationTemplate, do not hand-edit).
                 * Pressure model=%s; loginStrategy=%s; steps passed the interface-action gate.
                 */
                public class %s extends Simulation {
                %s
                    HttpProtocolBuilder httpProtocol = http.baseUrl("%s");

                %s

                    {
                        setUp(%s).protocols(httpProtocol);
                    }
                }
                """.formatted(execStaticImport + loopStaticImports(loopBounded), csvImport,
                model, loginStrategy, simulationName,
                varMembers, javaLiteral(baseUrl),
                scenarioSection.toString(),
                populations.toString());
    }

    /** Loop-bounded FIXED_VU compiles legs inside repeat().on(...): the wrapped
     *  body must open with a CoreDsl static, so only the leading dot of the very
     *  first combinator is stripped (chains open with either .exec( or the gated
     *  .doIf( — both CoreDsl factories are javap-proven; a dot deeper in the
     *  chain belongs to .then(exec( and must never move). */
    private static String unwrapLeadingCombinator(String exec) {
        int at = 0;
        while (at < exec.length() && Character.isWhitespace(exec.charAt(at))) {
            at++;
        }
        if (exec.startsWith(".exec(", at) || exec.startsWith(".doIf(", at)) {
            return exec.substring(0, at) + exec.substring(at + 1);
        }
        return exec;
    }

    /** Static imports the loop wrapper needs on top of the var-chain set. */
    private static String loopStaticImports(boolean loopBounded) {
        if (!loopBounded) {
            return "";
        }
        return "\nimport static io.gatling.javaapi.core.CoreDsl.exec;"
                + "\nimport static io.gatling.javaapi.core.CoreDsl.doIf;";
    }
}
