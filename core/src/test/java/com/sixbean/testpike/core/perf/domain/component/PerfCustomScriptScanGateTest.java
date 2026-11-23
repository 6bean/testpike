package com.sixbean.testpike.core.perf.domain.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the static scan gate that guards user-supplied CUSTOM
 * load-test scripts at save time (B-9). The gate refuses anything that reaches
 * beyond the engine sandbox: native extension imports and dynamic code
 * execution for k6 JavaScript, filesystem access and system commands for
 * Gatling Scala. Verdicts name the offending line so a rejected save tells the
 * author exactly where to fix.
 */
class PerfCustomScriptScanGateTest {

    private static final String CLEAN_K6_SCRIPT = """
            import http from 'k6/http';
            import { check, sleep } from 'k6/sleep';
            export const options = { vus: 10, duration: '30s' };
            export default function () {
              const res = http.get('https://target.local/api/orders');
              check(res, { 'status 200': (r) => r.status === 200 });
              sleep(1);
            }
            """;

    private static final String CLEAN_GATLING_SCRIPT = """
            class SearchSimulation extends Simulation {
              val scn = scenario("search").exec(http("query").get("/api/search?q=shoe"))
              setUp(scn.injectAtOnce(50).protocols(httpProtocol))
            }
            """;

    @Test
    void cleanK6ScriptPassesScan() {
        assertThat(PerfCustomScriptScanGate.scan("K6", CLEAN_K6_SCRIPT)).isEmpty();
    }

    @Test
    void cleanGatlingScriptPassesScan() {
        assertThat(PerfCustomScriptScanGate.scan("GATLING", CLEAN_GATLING_SCRIPT)).isEmpty();
    }

    @Test
    void k6NativeExtensionImportIsRefusedWithLineAndReason() {
        String script = "import fs from 'k6/x/fs';\nexport default function () {}";
        List<String> violations = PerfCustomScriptScanGate.scan("K6", script);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("第 1 行").contains("原生扩展");
    }

    @Test
    void k6HostModuleImportIsRefused() {
        String script = """
                import http from 'k6/http';
                import child_process from 'child_process';
                """;
        assertThat(PerfCustomScriptScanGate.scan("K6", script))
                .anySatisfy(v -> assertThat(v).contains("第 2 行"));
    }

    @Test
    void k6DynamicCodeExecutionIsRefused() {
        String script = "const f = eval('1+1');";
        assertThat(PerfCustomScriptScanGate.scan("K6", script))
                .anySatisfy(v -> assertThat(v).contains("第 1 行"));
        assertThat(PerfCustomScriptScanGate.scan("K6", "const g = require('fs');"))
                        .isNotEmpty();
    }

    @Test
    void gatlingSystemCommandIsRefused() {
        String script = """
                class EvilSimulation extends Simulation {
                  Runtime.getRuntime().exec("rm -rf /")
                }
                """;
        List<String> violations = PerfCustomScriptScanGate.scan("GATLING", script);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("第 2 行").contains("系统命令");
    }

    @Test
    void gatlingFilesystemAccessIsRefused() {
        String script = "val f = new java.io.File(\"/etc/passwd\")";
        assertThat(PerfCustomScriptScanGate.scan("GATLING", script))
                .anySatisfy(v -> assertThat(v).contains("第 1 行").contains("文件系统"));
    }

    @Test
    void gatlingRunnerShutdownIsRefused() {
        String script = "System.exit(0)";
        assertThat(PerfCustomScriptScanGate.scan("GATLING", script))
                .anySatisfy(v -> assertThat(v).contains("第 1 行"));
    }

    @Test
    void multipleViolationsAreAllNamed() {
        String script = """
                import net from 'net';
                const p = process.env;
                eval("x");
                """;
        assertThat(PerfCustomScriptScanGate.scan("K6", script)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void unknownEngineIsRefusedNamingTheSupportedSet() {
        // "JMETER" joined the supported value domain with the third load
        // engine; probe with a name no adapter can claim and require the
        // refusal to spell out every engine from the single source.
        assertThat(PerfCustomScriptScanGate.scan("LOADRUNNER", "whatever"))
                .anySatisfy(v -> assertThat(v).contains("K6").contains("GATLING").contains("JMETER"));
    }

    @Test
    void blankScriptProducesNoScanNoise() {
        assertThat(PerfCustomScriptScanGate.scan("K6", "  ")).isEmpty();
        assertThat(PerfCustomScriptScanGate.scan("K6", null)).isEmpty();
    }
}
