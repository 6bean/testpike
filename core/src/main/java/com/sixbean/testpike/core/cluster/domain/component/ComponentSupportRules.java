package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ComponentVerdict;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * This is the component support status tri-color plus gray decision spectrum (version detection system; the frozen document is the sole authoritative standard).
 * <p>Decision sequence (fail-closed: if any step lacks sufficient evidence, honestly mark it gray; never treat missing evidence as green):
 * <ol>
 *   <li>Unknown component family = gray (no baseline exists outside the family registry, so list it honestly as "Unknown component family" in the ledger and do not falsely report support status);</li>
 *   <li>Current value not reported = gray (offline, not installed, or missing report is honestly labeled "Not reported");</li>
 *   <li>Not in the support matrix (requirement gate fails) = red (support level is lost, and the dispatch gate uses this to fail closed and prohibit dispatch)
 *       — even if current value == target value, mark red (loss of support level takes precedence over catching up);</li>
 *   <li>No golden version/upstream baseline = gray (not probed or external network probe unreachable; do not guess green and do not falsely report upgradability,
 *       This is the decision spectrum's placement of the rule "never present an old value as the latest");</li>
 *   <li>Current value == baseline = green (latest within the interval); behind = yellow (upgradable); ahead = yellow (beyond the baseline and unverified,
 *       Likewise, do not mark green—the matrix only endorses up to the baseline version).</li>
 * </ol>
 * <p>Component-family enumeration is data-driven and extensible: fixed platform-side families + {@code engine-runtime.<engine>} prefixed families.
 * The list is mobilecli/maestro/appium/node/wx_devtools/hdc/minium/automator.
 */
public final class ComponentSupportRules {

    /** Fixed platform-side component families. */
    static final Set<String> PLATFORM_FAMILIES = Set.of(
            "core", "admin", "api", "ai-service", "frontend", "mock-server",
            "db-schema", "executor-agent", "browser-kernel");

    /**
 * Platform release-tracked families: the exact subset with upstream release
 * feeds and candidate-promotion semantics (mock-server ships with the
 * platform since ; db-schema/executor-agent/browser-kernel have no
 * platform release cadence). Single source for both
 * the probe rerouting and the confirm write-back — any new
 * consumer must reference this constant instead of re-declaring the set.
 */
    public static final Set<String> PLATFORM_RELEASE_FAMILIES = Set.of(
            "core", "admin", "api", "ai-service", "frontend", "mock-server");

    /** The engine-runtime prefix is followed by the engine name, and bare engine names are also compatible for registration. */
    public static final String ENGINE_PREFIX = "engine-runtime.";

 /** Registered engine-runtime bare names include the list of six engines plus the node runtime. */
    static final Set<String> ENGINE_RUNTIMES = Set.of(
            "mobilecli", "mobilewright", "maestro", "appium", "node",
            "wx_devtools", "hdc", "minium", "automator");

    private ComponentSupportRules() {
    }

    /**
 * Engine descriptor runtime_version_req key maps to the ledger component-family set for planned build steps and support level.
     * The two dimensions share one mapping point; the redline prohibits creating another engine→family table; unregistered family keys are honestly skipped.
     */
    public static Set<String> familiesForRuntimeReq(Map<String, String> runtimeVersionReq) {
        Set<String> families = new LinkedHashSet<>();
        if (runtimeVersionReq == null) {
            return families;
        }
        for (String reqKey : runtimeVersionReq.keySet()) {
            String family = ENGINE_PREFIX + reqKey;
            if (knownFamily(family)) {
                families.add(family);
            }
        }
        return families;
    }

    /** Whether a component family is registered determines whether a ledger row can have a support baseline. */
    public static boolean knownFamily(String family) {
        if (family == null || family.isBlank()) {
            return false;
        }
        String f = family.trim();
        if (PLATFORM_FAMILIES.contains(f)) {
            return true;
        }
        if (f.startsWith(ENGINE_PREFIX)) {
            return ENGINE_RUNTIMES.contains(f.substring(ENGINE_PREFIX.length()));
        }
        return ENGINE_RUNTIMES.contains(f);
    }

    /**
     * The main entry point of the decision spectrum takes the ledger triplet input: current value/interval/baseline; null means evidence is missing for that dimension.
     */
    public static ComponentVerdict judge(String family, String current, String requirement, String target) {
        if (!knownFamily(family)) {
            return gray("未知部件族「" + family + "」不在支持登记表，无判定基准可依——"
                    + "如需纳管请在部件族清单登记（数据化可扩展，禁硬编码二套）");
        }
        if (current == null || current.isBlank()) {
            return gray("该部件版本未上报（离线或上报通道未覆盖），支持态诚实置灰——"
                    + "心跳扩报或扫描回写后自动重判");
        }
        String cur = VersionCompare.firstVersionOf(current);
        if (requirement != null && !requirement.isBlank()
                && !VersionCompare.satisfies(cur, requirement)) {
            return new ComponentVerdict(ComponentVerdict.RED,
                    "现值 " + cur + " 不在支持区间「" + requirement + "」内——支持度失守，"
                            + "该节点此部件相关能力已禁用派发（fail-closed），请按升级指引处置");
        }
        if (target == null || target.isBlank()) {
            return gray("满足支持区间但无金版本/上游最新基准（未探测或外网探测不可达）——"
                    + "禁拿旧值冒充最新，基准回写后自动重判");
        }
        String tgt = VersionCompare.firstVersionOf(target);
        int cmp = VersionCompare.compare(cur, tgt);
        if (cmp == 0) {
            return new ComponentVerdict(ComponentVerdict.GREEN,
                    "现值 " + cur + " 在支持区间内且为最新基准，支持态绿");
        }
        if (cmp < 0) {
            return new ComponentVerdict(ComponentVerdict.YELLOW,
                    "现值 " + cur + " 落后最新基准 " + tgt + "——可升级（升级走命令队列配方，禁手工散升）");
        }
        return new ComponentVerdict(ComponentVerdict.YELLOW,
                "现值 " + cur + " 超前基准 " + tgt + "——超出支持矩阵背书范围未经回归验证，"
                        + "暂按可升级观察处理（基准刷新后自动重判）");
    }

    private static ComponentVerdict gray(String reason) {
        return new ComponentVerdict(ComponentVerdict.UNKNOWN, reason);
    }
}
