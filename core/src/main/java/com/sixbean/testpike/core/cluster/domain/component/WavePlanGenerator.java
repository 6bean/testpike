package com.sixbean.testpike.core.cluster.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wave order and manifest-completeness rules for the platform one-click upgrade
 * (scope=platform). Pure functions, single source consumed by
 * UpgradePlanServiceImpl create/impact and (later) the advance engine — no
 * second ordering or spectrum copy is allowed anywhere.
 * <p>Wave order is dependency-derived, never user-editable (design 8A.4):
 * managed things go first, the control plane leaves last, so the scheduler
 * driving the plan stays online through every earlier wave and self-heals after
 * its own restart. Rollback walks the same sequence in reverse (wave_seq DESC).
 * The engine-runtime wave sits between agent and frontend by design but is
 * produced through the existing engine-upgrade chain, not as platform steps —
 * this phase keeps that slot reserved (no fabricated steps here).
 */
public final class WavePlanGenerator {

    /** Step-wave order (executor-agent first, core last; engine slot reserved upstream).
     * mock-server upgrades after ai-service and before core: it consumes the core
     * rule snapshot, so the snapshot producer must be the last thing to leave. */
    public static final List<String> WAVE_ORDER = List.of(
            "executor-agent", "frontend", "api", "admin", "ai-service", "mock-server", "core");

    private static final ObjectMapper DIRECTION_MAPPER = new ObjectMapper();

    private WavePlanGenerator() {
    }

    /**
     * Whether a plan's strategy snapshot declares the rollback direction.
     * Single source consumed by both the step-read ordering (plan service) and
     * the wave advance engine — an unparseable or blank snapshot never
     * fabricates a direction (forward is the only honest fallback).
     */
    public static boolean isRollbackDirection(String strategyJson) {
        if (strategyJson == null || strategyJson.isBlank()) {
            return false;
        }
        try {
            return "rollback".equals(DIRECTION_MAPPER.readTree(strategyJson)
                    .path("direction").asText(""));
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether one ledger family participates in the platform wave as a step. */
    public static boolean platformFamily(String family) {
        return family != null && WAVE_ORDER.contains(family);
    }

    /** Wave position of a family (index into WAVE_ORDER; -1 = not a wave family). */
    public static int waveSeq(String family) {
        return WAVE_ORDER.indexOf(family);
    }

    /** Families the matrix pins a version for (lower-cased, trimmed). */
    public static java.util.Set<String> pinnedFamilies(JsonNode matrix) {
        return pinnedTargets(matrix).keySet();
    }

    /**
     * Family -> pinned version for every matrix item declaring a pin (keys
     * lower-cased). Single parse point for the platform wave targets: the plan
     * consumes pinned versions as the upgrade authority, never the caller's
     * free-typed value and never a re-derived baseline.
     */
    public static Map<String, String> pinnedTargets(JsonNode matrix) {
        Map<String, String> pinned = new java.util.LinkedHashMap<>();
        for (JsonNode item : matrix) {
            String target = item.path("pinned").asText("");
            if (!target.isBlank()) {
                pinned.put(componentOf(item).toLowerCase(Locale.ROOT), target.trim());
            }
        }
        return pinned;
    }

    /** One PENDING platform step from a YELLOW ledger row (preview-stage view: ids null). */
    public static UpgradePlanStepView platformStep(ClusterComponentInfo row) {
        return new UpgradePlanStepView(null, null, row.nodeId(), "platform", null, "batch",
                row.currentVersion(), row.targetVersion(), "PENDING", null, "{}",
                row.componentFamily(), waveSeq(row.componentFamily()));
    }

    /**
     * Manifest completeness gate for a platform plan. Two checks, both reusing
     * the existing single sources (zero new spectrum):
     * 1) every release-tracked family must be declared in the component matrix
     *    with a pinned version — a missing pin means "we don't know what vX
     *    contains for that service" and the whole plan has no upgrade authority
     *    (executor-agent is optional: no pin = that wave is skipped, not a
     *    rejection, per design 8A.4);
     * 2) every pinned family must ship an artifact — reuses
     *    {@link ReleaseArtifactRules#pinnedFamiliesWithoutArtifact} so the plan
     *    gate and the register gate can never drift apart.
     *
     * @return null when the manifest can drive a wave plan, else a plain-language
     *         rejection naming exactly what is missing
     */
    public static String manifestViolation(JsonNode matrix, JsonNode artifacts) {
        java.util.Set<String> pinned = pinnedFamilies(matrix);
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String family : ComponentSupportRules.PLATFORM_RELEASE_FAMILIES) {
            if (!pinned.contains(family)) {
                missing.add(family);
            }
        }
        if (!missing.isEmpty()) {
            return "发布清单部件矩阵缺少平台服务的钉定版本（缺：" + String.join(" / ", missing)
                    + "）——平台一键升级的权威是「每个平台服务都钉死了要升到哪个版本」，"
                    + "缺任何一家都不能生成波次计划，请先在升级中心确认候选版本补全矩阵";
        }
        java.util.Set<String> noArtifact =
                ReleaseArtifactRules.pinnedFamiliesWithoutArtifact(matrix, artifacts);
        if (!noArtifact.isEmpty()) {
            return "钉定版本缺少可部署工件（缺：" + String.join(" / ", noArtifact)
                    + "）——声明了版本却没给部署包=承诺了但升不了，请补 artifacts 下载清单或改走手工升级指南";
        }
        return null;
    }

    /** Family name of one matrix item (register input shape uses "component"). */
    private static String componentOf(JsonNode item) {
        String raw = item.path("component").asText("");
        if (raw.isBlank()) {
            raw = item.path("component_family").asText("");
        }
        return raw.trim();
    }
}
