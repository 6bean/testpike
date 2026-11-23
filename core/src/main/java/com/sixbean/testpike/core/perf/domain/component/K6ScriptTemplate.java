package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * RAMP→ramping-arrival-rate ramp + hold stages / SPIKE→ramping-arrival-rate rise-hold-fall-tail
 */
public final class K6ScriptTemplate {

    
    private static String jsLiteral(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
 * Weight-fraction view of the scenario pressure config for ONE link —
 * delegates to {@link PerfMixGroups#scaledConfig} (the single scaling
 * source both engines ride: k6 executor blocks and Gatling populations
 * can never drift apart on the same declaration). Kept as the adapter-
 * facing entry ( A-12 VU divisor calls this too, so emitted per-block
 * numbers and the pinned stride divisor share one judgement).
 */
    static ObjectNode scaledConfigForLink(JsonNode config, PerfMixGroups groups,
                                          PerfMixGroups.Link link) {
        return groups.scaledConfig(config, link);
    }

    /** Flat-chain entry kept source-compatible (pre-B-3 callers, no mix). */
    static String scenariosBlock(String model, JsonNode config, int vuPerRps) {
        return scenariosBlock(model, config, vuPerRps, null);
    }

    /**
 * Executor blocks for the whole scenario. A flat chain (no transaction
 * declared) renders EXACTLY the historical blocks — zero regression is the
 * acceptance criterion of this segment. A mix chain renders one block set
 * PER LINK (B-3 c-2β): weight-scaled pressure numbers, link-unique keys,
 * an {@code exec} pointer at the link's own step function and a
 * {@code transaction} scenario tag so the report side can split the links
 * without re-deriving the mix.
 */
    static String scenariosBlock(String model, JsonNode config, int vuPerRps, PerfMixGroups groups) {
        if (groups == null || groups.isFlat()) {
            return blocksFor(model, config, vuPerRps, "", "");
        }
        List<String> out = new ArrayList<>();
        for (PerfMixGroups.Link link : groups.links()) {
            String execSlot = "\n                          exec: '" + groups.funcName(link) + "',"
                    + "\n                          tags: { transaction: \"" + jsLiteral(link.name()) + "\" },";
            out.add(blocksFor(model, scaledConfigForLink(config, groups, link),
                    vuPerRps, groups.scenarioKey(link) + "_", execSlot));
        }
        return String.join("\n", out);
    }

    /** One model's executor blocks under a key prefix ("" = historical keys)
 * with an optional exec/tags slot inserted inside every block.
 * <p> A-12: preAllocatedVUs across all arrival-rate executors scale with the
 * K8 coefficient (ceil(rate x vuPerRps)) instead of the hard 1:1 pin — a dirty or
 * missing coefficient falls back to 1 upstream, so this stays the historical shape
 * by default. SPIKE now maps to ramping-arrival-rate rise/hold/fall/tail stages
 * (the old ramping-vus mapping put RPS values into VU-target slots = unit
 * confusion, and both stages ran the full duration = a staircase twice as long). */
    private static String blocksFor(String model, JsonNode config, int vuPerRps,
                                    String keyPrefix, String execSlot) {
        return switch (model) {
            // B-2 : FIXED_VU is a closed pool — with a wall-clock
            // window it maps to constant-vus (each VU loops the iteration until the
            // window closes); loops-only maps to per-vu-iterations, the only k6
            // executor whose termination is a per-user count. thinkTimeMs rides an
            // in-iteration sleep (render tail), never dropped.
            case "FIXED_VU" -> fixedVuBlock(config, keyPrefix, execSlot);
            case "LADDER" -> ladderBlock(config, vuPerRps, keyPrefix, execSlot);
            case "BUSINESS_CURVE" -> curveBlock(config, vuPerRps, keyPrefix, execSlot);
            case "SOAK" -> """
                        %ssoak: {
                          executor: 'constant-arrival-rate',
                          rate: %s,
                          timeUnit: '1s',
                          duration: '%ss',
                          preAllocatedVUs: %s,%s
                        },""".formatted(keyPrefix, config.get("targetRps").asInt(),
                    config.get("durationHours").asInt() * 3600,
                    PerfVuBudget.scale(config.get("targetRps").asInt(), vuPerRps), execSlot);
            case "CONSTANT_RPS" -> """
                        %sperf: {
                          executor: 'constant-arrival-rate',
                          rate: %s,
                          timeUnit: '1s',
                          duration: '%ss',
                          preAllocatedVUs: %s,%s
                        },""".formatted(keyPrefix, config.get("targetRps").asInt(),
                    config.get("durationSeconds").asInt(),
                    PerfVuBudget.scale(config.get("targetRps").asInt(), vuPerRps), execSlot);
            case "RAMP" -> """
                        %sramp: {
                          executor: 'ramping-arrival-rate',
                          startRate: %s,
                          timeUnit: '1s',
                          stages: [{ target: %s, duration: '%ss' }],
                          preAllocatedVUs: %s,%s
                        },
                        %shold: {
                          executor: 'constant-arrival-rate',
                          rate: %s,
                          timeUnit: '1s',
                          duration: '%ss',
                          preAllocatedVUs: %s,%s
                          startTime: '%ss',
                        },""".formatted(keyPrefix, config.get("startRps").asInt(),
                    config.get("peakRps").asInt(), config.get("rampSeconds").asInt(),
                    PerfVuBudget.scale(config.get("peakRps").asInt(), vuPerRps),
                    execSlot.isEmpty() ? "" : execSlot + "\n                        ",
                    keyPrefix,
                    config.get("peakRps").asInt(), config.get("holdSeconds").asInt(),
                    PerfVuBudget.scale(config.get("peakRps").asInt(), vuPerRps),
                    execSlot.isEmpty() ? "" : execSlot + "\n                        ",
                    config.get("rampSeconds").asInt());
            case "SPIKE" -> spikeBlock(config, vuPerRps, keyPrefix, execSlot);
            default -> throw new IllegalArgumentException("未知压力形态: " + model);
        };
    }

    /** SPIKE = rise/hold/fall/tail sequential stages inside the one declared window
     *  (segments single-sourced from PerfSpikeSegments and think-time free — the
     *  arrival-rate executors own the pacing; VU budget rides the same K8
     *  coefficient as the other executors). */
    private static String spikeBlock(JsonNode config, int vuPerRps,
                                     String keyPrefix, String execSlot) {
        PerfSpikeSegments segs = PerfSpikeSegments.derive(config.get("durationSeconds").asInt());
        int peak = config.get("peakRps").asInt();
        int base = config.get("baseRps").asInt();
        return """
                    %sspike: {
                      executor: 'ramping-arrival-rate',
                      startRate: %s,
                      timeUnit: '1s',
                      stages: [
                        { target: %s, duration: '%ss' },
                        { target: %s, duration: '%ss' },
                        { target: %s, duration: '%ss' },
                        { target: %s, duration: '%ss' },
                      ],
                      preAllocatedVUs: %s,%s
                    },""".formatted(keyPrefix, base, peak, segs.riseSeconds(),
                peak, segs.holdSeconds(), base, segs.fallSeconds(),
                base, segs.tailSeconds(),
                PerfVuBudget.scale(Math.max(peak, base), vuPerRps), execSlot);
    }

    /**
     * FIXED_VU (B-2): closed pool. A declared wall-clock window maps to
     * constant-vus (the pool size is pinned, each VU loops its iteration until
     * the window closes); loops-only maps to per-vu-iterations with the loop
     * count as the per-user bound — the only k6 executor whose termination is a
     * count. The save gate guarantees at least one of the two keys exists.
     */
    private static String fixedVuBlock(JsonNode config, String keyPrefix, String execSlot) {
        int vu = config.get("vu").asInt();
        JsonNode duration = config.get("durationSeconds");
        if (duration != null && duration.isNumber() && duration.asInt() > 0) {
            return """
                        %spool: {
                          executor: 'constant-vus',
                          vus: %d,
                          duration: '%ds',%s
                        },""".formatted(keyPrefix, vu, duration.asInt(), execSlot);
        }
        return """
                    %spool: {
                      executor: 'per-vu-iterations',
                      vus: %d,
                      iterations: %d,%s
                    },""".formatted(keyPrefix, vu, config.get("loops").asInt(), execSlot);
    }

    /**
 * LADDER (B-2): rate search staircase. ramping-arrival-rate stages walking
 * startRps + i x stepRps for i in 1..steps, each stepSeconds long — the same
 * top (startRps + stepRps x steps) the budget policy and the VUSER_EXCLUSIVE
 * segment divisor claim, so the three faces can never drift.
 */
    private static String ladderBlock(JsonNode config, int vuPerRps,
                                      String keyPrefix, String execSlot) {
        int start = config.get("startRps").asInt();
        int step = config.get("stepRps").asInt();
        int stepSeconds = config.get("stepSeconds").asInt();
        int levels = config.get("steps").asInt();
        StringBuilder stages = new StringBuilder();
        for (int i = 1; i <= levels; i++) {
            if (i > 1) {
                stages.append(",");
            }
            stages.append("\n                        { target: ")
                    .append(start + step * i)
                    .append(", duration: '").append(stepSeconds).append("s' }");
        }
        return """
                    %sladder: {
                      executor: 'ramping-arrival-rate',
                      startRate: %d,
                      timeUnit: '1s',
                      stages: [%s,
                      ],
                      preAllocatedVUs: %d,%s
                    },""".formatted(keyPrefix, start, stages,
                PerfVuBudget.scale(start + step * levels, vuPerRps), execSlot);
    }

    /**
     * BUSINESS_CURVE (B-2): hourly constant-arrival-rate segments, rate =
     * peakRps x weight/100 (PerfCurveSegments is the single source shared with
     * the Gatling template — idle hours never become segments). startTime pins
     * each segment onto the hour grid so the segments run sequentially inside
     * one scenario block instead of all firing from t=0.
     */
    private static String curveBlock(JsonNode config, int vuPerRps,
                                     String keyPrefix, String execSlot) {
        List<PerfCurveSegments.Segment> segs = PerfCurveSegments.derive(
                config, config.get("durationSeconds").asInt());
        List<String> blocks = new ArrayList<>();
        for (PerfCurveSegments.Segment seg : segs) {
            blocks.add("""
                        %shour%02d: {
                          executor: 'constant-arrival-rate',
                          rate: %d,
                          timeUnit: '1s',
                          duration: '%ds',
                          startTime: '%ds',
                          preAllocatedVUs: %d,%s
                        },""".formatted(keyPrefix, seg.hour(), seg.rate(), seg.spanSeconds(),
                    seg.startTimeSeconds(), PerfVuBudget.scale(seg.rate(), vuPerRps), execSlot));
        }
        return String.join("\n", blocks);
    }

    /** In-script JsonPath subset evaluator ($.a.b[0].c, same grammar whitelist as
     *  the executor kernel — filter/script segments never reach here because the
     *  save + compile gates reject them first). Emitted only when a step has
     *  expect.body rows. */
    private static final String TP_JSONPATH_FN = """
            function tpJsonPath(res, path) {
              let cur = res.json();
              const segs = path.replace(/^\\$\\.?/, '').split('.');
              for (let seg of segs) {
                const m = seg.match(/^([^\\[]*)((\\[[0-9]+\\])*)$/);
                if (!m) return undefined;
                if (m[1]) { cur = (cur == null) ? undefined : cur[m[1]]; }
                for (const i of (m[2] || '').matchAll(/\\[([0-9]+)\\]/g)) {
                  cur = (cur == null) ? undefined : cur[Number(i[1])];
                }
                if (cur === undefined) return undefined;
              }
              return cur;
            }""";

    /**
 * Per-VU variable pool + single resolver + scalar-leaf expansion (B-1, DSL v2
 * . k6 gives each VU its own module instance, so a module-level object
 * is exactly the per-VUSER semantics the D4 strategy needs (values never leak
 * across VUs). The resolver is the only substitution point in the script
 * (single-parser red line); an undefined reference fails fast naming the
 * variable and pointing at the previous saveAs — wording mirrors the executor
 * kernel (driver/env_resolver), zero resolved values in any message. Leaf key
 * shape mirrors the executor jsonpath_subset.scalar_leaves (resp.field /
 * resp[0], scalars only — objects/arrays never enter the pool head).
 */
    private static final String TP_VAR_FN = """
            const tpVars = {};
            function tpVarResolve(text) {
              return String(text).replace(/\\{\\{VAR:([^}]+)\\}\\}/g, (whole, name) => {
                const v = tpVars[name];
                if (v === undefined) {
                  throw new Error('变量 ' + name + ' 未定义（检查前序 saveAs）');
                }
                return String(v);
              });
            }
            function tpScalarLeaves(value, name) {
              const out = {};
              (function walk(node, key) {
                if (node !== null && typeof node === 'object') {
                  for (const k of Object.keys(node)) {
                    walk(node[k], Array.isArray(node) ? key + '[' + k + ']' : key + '.' + k);
                  }
                } else {
                  out[key] = node;
                }
              })(value, name);
              return out;
            }""";

    /** Request chain: step sequence → default-function body lines.
 * <p>D5 auth bucketing (T275f-3b): a step declaring auth==true keeps the fixed
 * tag name "auth" (the executor perf_worker moves auth points out of the three
 * business lines via tags.name=="auth"; do not rename), others stay stepN.
 * <p>DSL v2 slot consumption: step headers merge over the isolation HEADERS,
 * a JSON body becomes the payload (Content-Type defaulted when absent),
 * expect.status maps to expectedStatuses so a mismatch is engine-native
 * http_req_failed (business failure rides as the same error line the breaker
 * watches — the control plane never judges twice), expect.body rows compile
 * into k6 checks against the in-script jsonpath subset.
 * <p>B-1 variable chain : url/header/body texts carrying {{VAR:}}
 * go through the single in-script resolver (static strings stay plain
 * literals — no wrapper), a saveAs step compiles into per-VU scalar-leaf
 * capture after the call; a non-JSON response then fails the iteration
 * honestly (JSON.parse throws) instead of faking a green run. */
    static String requestsBlock(JsonNode steps) {
        List<String> lines = new ArrayList<>();
        int idx = 0;
        for (JsonNode step : steps) {
            idx++;
            appendStepLines(lines, step, idx);
        }
        return String.join("\n", lines);
    }

    /**
     * One step → its request/capture/check lines, numbered by the GLOBAL step
     * index so mix links never share a tags.name series (link A step 1 and
     * link B step 1 are different request series). Extracted from
     * {@link #requestsBlock} so the flat chain and the per-link functions of a
     * mix chain compile steps through the exact same code path (red line 6).
     */
    private static void appendStepLines(List<String> lines, JsonNode step, int idx) {
        String method = jsLiteral(step.path("method").asText("GET").toUpperCase());
        String urlRaw = step.path("url").asText("");
        String urlExpr = placeholderWrapped(urlRaw);
        String tagName = step.path("auth").asBoolean(false) ? "auth" : "step" + idx;

        List<String> headerEntries = new ArrayList<>();
        JsonNode headers = step.get("headers");
        boolean hasContentType = false;
        if (headers != null && headers.isObject()) {
            for (var e : (Iterable<java.util.Map.Entry<String, JsonNode>>) headers::fields) {
                headerEntries.add("\"" + jsLiteral(e.getKey()) + "\": "
                        + placeholderWrapped(e.getValue().asText("")));
                if (e.getKey().equalsIgnoreCase("Content-Type")) {
                    hasContentType = true;
                }
            }
        }
        JsonNode body = step.get("body");
        boolean hasBody = body != null && !body.isNull();
        if (hasBody && !hasContentType) {
            headerEntries.add("\"Content-Type\": \"application/json\"");
        }
        String headersExpr = headerEntries.isEmpty()
                ? "headers: HEADERS"
                : "headers: Object.assign({}, HEADERS, { " + String.join(", ", headerEntries) + " })";

        String payload = hasBody
                ? placeholderWrapped(body.isTextual() ? body.asText() : body.toString())
                : "null";

        List<String> opts = new ArrayList<>();
        opts.add(headersExpr);
        opts.add("tags: { name: \"" + tagName + "\" }");
        JsonNode expect = step.get("expect");
        if (expect != null && expect.isObject()
                && expect.has("status") && !expect.get("status").isNull()) {
            opts.add("expectedStatuses: [" + expect.get("status").asInt() + "]");
        }

        List<JsonNode> bodyRows = new ArrayList<>();
        if (expect != null && expect.isObject() && expect.path("body").isArray()) {
            expect.get("body").forEach(bodyRows::add);
        }
        String saveAs = step.path("saveAs").asText("");
        boolean captures = !saveAs.isBlank();
        if (bodyRows.isEmpty() && !captures) {
            lines.add("  http.request(\"" + method + "\", " + urlExpr + ", "
                    + payload + ", { " + String.join(", ", opts) + " });");
            return;
        }
        lines.add("  const res" + idx + " = http.request(\"" + method + "\", " + urlExpr
                + ", " + payload + ", { " + String.join(", ", opts) + " });");
        if (captures) {
            // Scalar-leaf capture mirrors the executor (JSON body only; a
            // non-JSON response throws = honest iteration failure).
            lines.add("  Object.assign(tpVars, tpScalarLeaves(JSON.parse(res" + idx
                    + ".body), \"" + jsLiteral(saveAs) + "\"));");
        }
        List<String> checks = new ArrayList<>();
        int rowIdx = 0;
        for (JsonNode row : bodyRows) {
            rowIdx++;
            String path = jsLiteral(row.path("path").asText(""));
            String matchType = row.path("matchType").asText("contains");
            String text = jsLiteral(row.path("text").asText(""));
            String predicate = switch (matchType) {
                case "exact" -> "String(tpJsonPath(r, \"" + path + "\")) === \"" + text + "\"";
                default -> "String(tpJsonPath(r, \"" + path + "\")).indexOf(\""
                        + text + "\") >= 0";
            };
            checks.add("\"step" + idx + ".body" + rowIdx + "\": (r) => " + predicate);
        }
        // Emit the check line only when body rows exist — a capture-only step
        // must not compile into an empty check object (fake judgement slot).
        if (!checks.isEmpty()) {
            lines.add("  if (!check(res" + idx + ", { " + String.join(", ", checks)
                    + " })) tpBusinessFail.add(1);");
        }
    }

    /**
     * Mix chain (B-3 c-2β): one named step function per link, in declaration
     * order, each riding its member steps with their GLOBAL step numbers (one
     * iteration of a link = that link's whole business journey). The dataset
     * row seed and the FIXED_VU think-time line close every link function the
     * same way the flat default function does — no link silently loses pacing
     * or pool rows.
     */
    static String linkFunctionsBlock(PerfMixGroups groups, JsonNode steps,
                                     String thinkLine, String rowSeedLine) {
        List<String> out = new ArrayList<>();
        for (PerfMixGroups.Link link : groups.links()) {
            List<String> lines = new ArrayList<>();
            for (int stepIndex : link.stepIndices()) {
                appendStepLines(lines, steps.get(stepIndex), stepIndex + 1);
            }
            out.add("export function " + groups.funcName(link) + "() {\n"
                    + rowSeedLine
                    + String.join("\n", lines)
                    + (thinkLine.isEmpty() ? "" : "\n  " + thinkLine)
                    + "\n}");
        }
        return String.join("\n\n", out);
    }

    /**
     * Slot fill for one text field: plain escaped literal when no {{VAR:}} is
     * present, resolver call when it is (the engine never sees raw placeholders —
     * substitution happens exclusively inside tpVarResolve at iteration time).
     */
    private static String placeholderWrapped(String raw) {
        String literal = "\"" + jsLiteral(raw) + "\"";
        return raw.contains("{{VAR:") ? "tpVarResolve(" + literal + ")" : literal;
    }

    /**
 * Data-pool helpers ( segment B②a, emitted only when the script binds a
 * dataset). Rows were materialized next to the script at compile time and
 * are loaded once per VU in the init context (k6 open resolves relative to
 * the script directory — same-host assumption pinned in the dispatch ruling).
 * Row values enter the SAME tpVars pool the {{VAR:}} resolver already reads
 * (single-parser red line: no second substitution point).
 * <p>CYCLE: iteration index with a per-VU start offset so concurrent users
 * never press with the same row in lockstep; rows are reused round-robin,
 * so a long run can never stall on "no row left".
 * VUSER_EXCLUSIVE: stride segment by virtual user (VUs are preAllocated at
 * compile time, so the divisor is a pinned literal) — user i owns rows
 * i, i+V, i+2V... and cycles inside its own segment; an empty segment fails
 * the iteration honestly naming the pool exhaustion (never a silent
 * row-share that would fake exclusivity). The compile stage refuses pools
 * smaller than the VU count before this code ever runs.
 * (SEQUENTIAL_EXCLUSIVE has no cross-VU atomic pointer in k6 — rejected at
 * compile time by K6Adapter, so no helper is emitted for it here.)
 */
    private static String tpDatasetFn(String fileName, int vuCount) {
        return """
                const tpRows = JSON.parse(open("%s"));
                function tpDatasetRow() {
                  if (tpRows.length === 0) {
                    throw new Error('压测数据池「%s」没有可用行（池为空）');
                  }
                  const slice = [];
                  for (let i = __VU - 1; i < tpRows.length; i += %d) slice.push(tpRows[i]);
                  if (slice.length === 0) {
                    throw new Error('压测数据池行不足：本虚拟用户（__VU=' + __VU
                        + '）在独占切片下分不到任何行，请扩充数据集行数或降低并发');
                  }
                  return slice[__ITER %% slice.length];
                }""".formatted(jsLiteral(fileName), jsLiteral(fileName), vuCount);
    }

    /** CYCLE variant of the row picker (round-robin with a per-VU phase offset). */
    private static String tpDatasetCycleFn(String fileName) {
        return """
                const tpRows = JSON.parse(open("%s"));
                function tpDatasetRow() {
                  if (tpRows.length === 0) {
                    throw new Error('压测数据池「%s」没有可用行（池为空）');
                  }
                  // Per-VU start offset (__VU-1) keeps users out of lockstep rows.
                  return tpRows[(tpRows.length + (__VU - 1) + __ITER) %% tpRows.length];
                }""".formatted(jsLiteral(fileName), jsLiteral(fileName));
    }

    /**
 *
 * @param dataset Bound CSV data pool
 * @param vuPerRps A-12 K8 coefficient (1..10) sizing preAllocatedVUs per arrival-rate unit
 */
    public String render(String model, JsonNode config, JsonNode steps, String baseUrl,
                         boolean headerMarkIsolation, TemplateDataset dataset, int vuPerRps) {
        String headers = headerMarkIsolation
                ? "const HEADERS = { \"X-Load-Test\": \"1\" };"
                : "const HEADERS = {};";
        // B-3 : one grouping source decides both faces of the mix —
        // the flat chain keeps the historical single default function and the
        // historical executor blocks byte-for-byte, a declared mix swaps to
        // per-link functions with per-link weighted blocks.
        PerfMixGroups groups = PerfMixGroups.derive(steps);
        boolean mixed = !groups.isFlat();
        // B-2 FIXED_VU think time: a closed pool paces itself, so the declared
        // thinking time must land inside the iteration or the pool presses harder
        // than declared (the two open models are rate-driven and ignore it).
        String thinkLine = "FIXED_VU".equals(model)
                ? thinkTimeSleepLine(config) : "";
        // The iteration body: flat = the historical default function (byte-shape
        // preserved — think time rides the tail the same way since B-2); mixed =
        // one named function per link (exec pointers in the blocks above
        // reference these names).
        String rowSeedLine = dataset != null
                ? "  Object.assign(tpVars, tpDatasetRow());\n" : "";
        String requests = mixed ? "" : requestsBlock(steps);
        String tail = thinkLine.isEmpty() ? "" : "  " + thinkLine + "\n";
        String iterationBody = mixed
                ? linkFunctionsBlock(groups, steps, thinkLine, rowSeedLine)
                : "export default function () {\n" + rowSeedLine + "\n" + requests + tail + "}";
        // expect.body rows compile into k6 checks; the check import, the business-
        // failure counter and the in-script jsonpath-subset helper are emitted only
        // when the script checks (counter feeds the error line — a content
        // mismatch must move the same series the breaker watches, A-4).
        boolean usesChecks = iterationBody.contains("check(res");
        // The per-VU pool, resolver and leaf-expansion helpers are emitted only
        // when the script actually carries a capture or a {{VAR:}} reference
        // (static scenarios keep the previous minimal shape — zero regression).
        // A bound dataset always uses the pool (rows flow through tpVars), which
        // also forces the resolver onto the table since step rows feed {{VAR:}}.
        boolean usesVars = dataset != null || iterationBody.contains("tpVarResolve(")
                || iterationBody.contains("Object.assign(tpVars,");
        String checkImport = usesChecks
                ? "import { check } from 'k6';\nimport { Counter } from 'k6/metrics';\n"
                        + "const tpBusinessFail = new Counter('tp_business_fail');\n"
                : "";
        String helper = (usesChecks ? "\n" + TP_JSONPATH_FN : "")
                + (usesVars ? "\n" + TP_VAR_FN : "");
        // Row picker (dataset binding only; the assignment rides the SAME
        // tpVars object the resolver reads).
        String datasetHelper = "";
        if (dataset != null) {
            datasetHelper = PerfRowAllocModes.CYCLE.equals(dataset.allocMode())
                    ? "\n" + tpDatasetCycleFn(dataset.fileName())
                    : "\n" + tpDatasetFn(dataset.fileName(), dataset.vuCount());
        }
        if (!helper.isEmpty() || !datasetHelper.isEmpty()) {
            helper = helper + datasetHelper + "\n";
        }
        String sleepImport = thinkLine.isEmpty() ? "" : "import { sleep } from 'k6';\n";
        return """
                import http from 'k6/http';
                %s%s
                // Generated by the testPike perf control plane (template = K6ScriptTemplate, do not hand-edit).
                // Pressure model=%s; executor block=scenarios; steps passed the interface-action gate;
                // SLA judgement stays in the control plane (engine self-stats are not trusted).
                export const options = {
                  scenarios: {
                %s
                  },
                };

                export const BASE_URL = "%s";
                %s
                %s
                %s
                """.formatted(checkImport, sleepImport, model,
                scenariosBlock(model, config, vuPerRps, groups),
                jsLiteral(baseUrl), headers, helper, iterationBody);
    }

    /**
     * thinkTimeMs → k6 sleep(...) seconds line (B-2; whole seconds print without a
     * fraction so golden samples stay readable). Missing/zero = no line (an
     * optional key, never required by the save gate).
     */
    private static String thinkTimeSleepLine(JsonNode config) {
        int ms = config.path("thinkTimeMs").asInt(0);
        if (ms <= 0) {
            return "";
        }
        return ms % 1000 == 0
                ? "sleep(" + (ms / 1000) + ");"
                : "sleep(" + (ms / 1000.0) + ");";
    }

    /** Pre-B②a render form (no dataset, coefficient=1 default shape): kept so callers without a pool stay source-compatible. */
    public String render(String model, JsonNode config, JsonNode steps, String baseUrl,
                         boolean headerMarkIsolation) {
        return render(model, config, steps, baseUrl, headerMarkIsolation, null, 1);
    }
}
