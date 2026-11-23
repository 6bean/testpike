package com.sixbean.testpike.core.perf.domain.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Static scan gate for user-supplied CUSTOM load-test scripts (B-9).
 * <p>A custom script escapes the DSL whitelist, so the only honest defence is
 * refusing — at save time, before any engine ever sees the file — everything
 * that reaches past the engine sandbox. The dictionary below is deliberately
 * conservative: k6 scripts may not import host/native modules or evaluate
 * dynamic code; Gatling simulations may not touch the filesystem, spawn
 * system commands, or shut down the runner; JMeter test plans may not embed
 * dynamic script blocks (BeanShell/JSR223/Groovy) or spawn processes. Each rule names the offending
 * line so a rejected save tells the author exactly where to fix (plain-words
 * door: no bare stack, no generic "invalid script").
 * <p>The gate is pure (no Spring, no IO) so the save chain and the future
 * mount segment share one verdict source — a second enumeration elsewhere is
 * forbidden (red line 6).
 */
public final class PerfCustomScriptScanGate {

    /** One dictionary rule: pattern + the human name of what it forbids. */
    private record Rule(Pattern pattern, String what) {
    }

    /**
     * k6 JS rules: host builtins (node-escape of the goja sandbox), x/ native
     * extensions (file/process/network syscalls), dynamic code execution and
     * the process environment. Matching is per line, case-insensitive for
     * module specifiers.
     */
    private static final List<Rule> K6_RULES = List.of(
            new Rule(Pattern.compile(
                    "import\\s+[^;]+?from\\s*['\"](?:child_process|net|fs|dns|http|https|tls|dgram|os|path|process)['\"]"),
                    "宿主内置模块（k6 沙箱之外）"),
            new Rule(Pattern.compile("import\\s+[^;]+?from\\s*['\"]k6/x/[^'\"]*['\"]"),
                    "原生扩展模块"),
            new Rule(Pattern.compile("(?<![\\w$])(?:eval|require)\\s*\\("),
                    "动态代码执行"),
            new Rule(Pattern.compile("(?<![\\w$])globalThis\\s*\\."),
                    "宿主全局对象访问"),
            new Rule(Pattern.compile("(?i)process\\.env"),
                    "宿主环境变量访问"));

    /**
     * Gatling Scala rules: direct filesystem IO, process spawning, JVM/runner
     * shutdown, and reflective loading. Network access through the engine's
     * own http/ws DSL is the sanctioned path and stays untouched.
     */
    private static final List<Rule> GATLING_RULES = List.of(
            new Rule(Pattern.compile("java\\.io\\.(?:File|FileWriter|FileReader|RandomAccessFile)"),
                    "文件系统"),
            new Rule(Pattern.compile("scala\\.io\\.File"), "文件系统"),
            new Rule(Pattern.compile("(?:Runtime\\.getRuntime\\(\\)|ProcessBuilder|sys\\.process)"),
                    "系统命令"),
            new Rule(Pattern.compile("(?:System\\.exit|Runtime\\.getRuntime\\(\\)\\.halt)"),
                    "强制退出压测进程"),
            new Rule(Pattern.compile("Class\\.forName"), "动态类加载"),
            new Rule(Pattern.compile("java\\.net\\.(?:Socket|ServerSocket|DatagramSocket)"),
                    "绕过引擎的裸网络连接"));

    /**
     * JMeter XML rules: dynamic script blocks escape the sampler sandbox the
     * same way eval does in k6, and process spawning / JVM shutdown are the
     * same escapes Gatling is held to. Plain XML samplers and the engine's own
     * HTTP samplers are the sanctioned path and stay untouched.
     */
    private static final List<Rule> JMX_RULES = List.of(
            new Rule(Pattern.compile("(?i)BeanShell|JSR223|groovy"),
                    "动态脚本块（BeanShell/JSR223/Groovy）"),
            new Rule(Pattern.compile("Runtime\\.getRuntime\\(\\)|ProcessBuilder"),
                    "系统命令"),
            new Rule(Pattern.compile("System\\.exit|\\.halt\\("),
                    "强制退出压测进程"));

    private PerfCustomScriptScanGate() {
    }

    /**
     * Scan one custom script against the dictionary bound to its engine.
     *
     * @param engine  K6, GATLING or JMETER (value domain = PerfRunDispatchSupport.ENGINE_VALUES)
     * @param content raw script text; null/blank = nothing to scan (the save
     *                chain owns the "CUSTOM needs a script body" rule)
     * @return human-readable violations, one per matched line (empty = clean)
     */
    public static List<String> scan(String engine, String content) {
        List<String> violations = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return violations;
        }
        List<Rule> rules = switch (engine == null ? "" : engine.toUpperCase(Locale.ROOT)) {
            case "K6" -> K6_RULES;
            case "GATLING" -> GATLING_RULES;
            case "JMETER" -> JMX_RULES;
            default -> null;
        };
        if (rules == null) {
            violations.add("自定义脚本必须绑定受支持的压测引擎（可选="
                    + String.join("/", PerfRunDispatchSupport.ENGINE_VALUES)
                    + "），当前引擎「" + engine + "」无法执行静态扫描");
            return violations;
        }
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            for (Rule rule : rules) {
                if (rule.pattern().matcher(lines[i]).find()) {
                    violations.add("第 " + (i + 1) + " 行被静态扫描拒绝：自定义脚本不允许"
                            + rule.what() + "，请改用引擎自带的请求 DSL");
                }
            }
        }
        return violations;
    }
}
