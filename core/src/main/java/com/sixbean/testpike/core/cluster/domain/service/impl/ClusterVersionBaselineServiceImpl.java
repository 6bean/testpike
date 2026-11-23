package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.cluster.api.dto.ClusterBaselineScanResult;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.domain.component.ComponentSupportRules;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterVersionBaselineService;
import com.sixbean.testpike.core.common.domain.component.VersionCompare;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Descriptor baseline reconciliation implementation for the frozen-document scan loop.
 * <p> The link is the online executor, with the same source as dispatch and health checks via executorService.listOnline and no second online determination.
 * × The reported engine catalog descriptor runtime_version_req refreshes the component-family requirement baseline.
 * When multiple engine requirements coexist in the same family, take the stricter tier, so the higher lower bound wins and interval formats are only >= lower bound or pinned version, using the same decision-spectrum criterion.
 * <p> Current-value protection means the scanner updates only the baseline: existing ledger current values are carried back unchanged in the upsert, with COALESCE stickiness falling back only to target/requirement.
 * The current-overwrite semantics forbid losing heartbeat current values in the scan view, as evidenced by the upsert contract.
 * When there is no ledger row, create a record with current=null marked gray as "not reported" so that missing reports are visible in the matrix view.
 * <p> The honest boundary is that a descriptor requirement key absent from the component-family registry, such as jdk or python for non-registered families, is skipped with a trace.
 * Fabricating an unregistered family such as engine-runtime.jdk to impersonate a managed family is forbidden because the decision spectrum may gray while the ledger records a family without basis as dirty data.
 * Upstream golden-version detection on the target view is disabled, and this implementation forbids premature action by always passing target as null to retain baseline stickiness.
 */
@Service
public class ClusterVersionBaselineServiceImpl implements ClusterVersionBaselineService {

    private static final Logger log = LoggerFactory.getLogger(ClusterVersionBaselineServiceImpl.class);

    private final ExecutorService executorService;
    private final AppautoEngineService engineService;
    private final ClusterComponentService componentService;

    public ClusterVersionBaselineServiceImpl(ExecutorService executorService,
                                             AppautoEngineService engineService,
                                             ClusterComponentService componentService) {
        this.executorService = executorService;
        this.engineService = engineService;
        this.componentService = componentService;
    }

    @Override
    public ClusterBaselineScanResult syncFromDescriptors() {
        // The descriptor requirement view with uppercase engine code keys is fetched only once and consumed per node, and re-querying the catalog per node is forbidden.
        Map<String, Map<String, String>> reqByEngine = new TreeMap<>();
        for (AppautoEngineInfo engine : engineService.listEngines(false)) {
            reqByEngine.put(engine.code().toUpperCase(Locale.ROOT), engine.runtimeVersionReq());
        }

        List<ExecutorInfo> online = executorService.listOnline();
        Set<String> skippedKeys = new LinkedHashSet<>();
        int written = 0;
        for (ExecutorInfo node : online) {
            written += syncNode(String.valueOf(node.id()), node.capabilities(), reqByEngine, skippedKeys);
        }
        if (!skippedKeys.isEmpty()) {
            log.info("部件基准扫描：描述符要求键 {} 不在部件族登记表，本轮诚实跳过（禁冒充纳管）", skippedKeys);
        }
        return new ClusterBaselineScanResult(online.size(), written, List.copyOf(skippedKeys));
    }

    /** For single-node baseline refresh, reported engines and descriptor requirements are aggregated into stricter per-family baselines and then written back family by family. */
    private int syncNode(String nodeId, Map<String, Object> caps,
                         Map<String, Map<String, String>> reqByEngine, Set<String> skippedKeys) {
        Map<String, String> strictestByFamily = new TreeMap<>();
        for (String engineCode : reportedEngines(caps)) {
            Map<String, String> requirements = reqByEngine.get(engineCode.toUpperCase(Locale.ROOT));
            if (requirements == null) {
                continue; // If the engine catalog is disabled or absent, there is no baseline to rely on, so the scan honestly skips it without holding the decision spectrum responsible.
            }
            for (Map.Entry<String, String> req : requirements.entrySet()) {
                String family = "engine-runtime." + req.getKey();
                if (!ComponentSupportRules.knownFamily(family)) {
                    skippedKeys.add(req.getKey());
                    continue;
                }
                strictestByFamily.merge(family, req.getValue(),
                        ClusterVersionBaselineServiceImpl::stricterRequirement);
            }
        }
        if (strictestByFamily.isEmpty()) {
            return 0;
        }
        // Current-value protection reads existing ledger rows, lets the scan update only the baseline and source, and carries current values back unchanged, forbidding loss of heartbeat reports.
        Map<String, String> currentByFamily = new TreeMap<>();
        for (ClusterComponentInfo row : componentService.listByNode(nodeId)) {
            currentByFamily.put(row.componentFamily(), row.currentVersion());
        }
        int landed = 0;
        for (Map.Entry<String, String> entry : strictestByFamily.entrySet()) {
            try {
                componentService.upsert(nodeId, entry.getKey(),
                        currentByFamily.get(entry.getKey()), "scan", entry.getValue(), null);
                landed++;
            } catch (RuntimeException e) {
 // Row-level isolation has the same semantics, so a dirty value in one family must not swallow the remaining baseline refreshes for the same node.
                log.warn("部件基准回写单行失败（诚实跳过该族）: node={} family={} cause={}",
                        nodeId, entry.getKey(), e.getMessage());
            }
        }
        return landed;
    }

    /** When two intervals exist in the same family, take the stricter one, so the higher lower-bound version wins, and pinned versions and unmarked version views also compare by the first version number using the same fault-tolerance criterion as the decision spectrum. */
    private static String stricterRequirement(String a, String b) {
        int cmp = VersionCompare.compare(VersionCompare.firstVersionOf(a), VersionCompare.firstVersionOf(b));
        return cmp >= 0 ? a : b;
    }

    @SuppressWarnings("unchecked")
    private static List<String> reportedEngines(Map<String, Object> caps) {
        Object face = caps == null ? null : caps.get("engines");
        return face instanceof List<?> l ? (List<String>) l : List.of();
    }
}
