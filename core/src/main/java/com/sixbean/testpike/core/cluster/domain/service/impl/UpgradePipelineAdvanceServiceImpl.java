package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.report.PassRateSql;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.component.WavePlanGenerator;
import com.sixbean.testpike.core.cluster.domain.service.ClusterCommandService;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import com.sixbean.testpike.core.cluster.domain.service.UpgradePipelineAdvanceService;
import com.sixbean.testpike.core.cluster.domain.service.UpgradePlanService;
import com.sixbean.testpike.core.cluster.domain.service.UpgradeSuiteOutcome;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.suites.api.dto.SuiteRunResult;
import com.sixbean.testpike.core.suites.domain.service.RegressionSuiteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


@Service
public class UpgradePipelineAdvanceServiceImpl implements UpgradePipelineAdvanceService {

    private static final Logger log = LoggerFactory.getLogger(UpgradePipelineAdvanceServiceImpl.class);

    
    static final int CMD_SUCCESS = 3;
    static final int CMD_FAILED = 4;

    
    static final List<Integer> BATCH_TERMINAL = List.of(6, 7, 8);

 
    static final String CANARY_TIMEOUT_KEY = "cluster.canary-timeout-seconds";
    static final String BATCH_TIMEOUT_KEY = "cluster.batch-timeout-seconds";
    static final String PASS_RATE_MIN_KEY = "cluster.canary-pass-rate-min";
    static final int DEFAULT_CANARY_TIMEOUT = 1800;
    static final int DEFAULT_BATCH_TIMEOUT = 3600;
    static final int DEFAULT_PASS_RATE_MIN = 90;

 
    static final int DEFAULT_COOL = 300;
    static final int DEFAULT_BATCH_SIZE = 1;

    private final NamedParameterJdbcTemplate jdbc;
    private final UpgradePlanService planService;
    private final ClusterCommandService commandService;
    private final RegressionSuiteService suiteService;
    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ConfigCacheHolder configCacheHolder;
    private final ReleaseManifestService manifestService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UpgradePipelineAdvanceServiceImpl(NamedParameterJdbcTemplate jdbc,
                                             UpgradePlanService planService,
                                             ClusterCommandService commandService,
                                             RegressionSuiteService suiteService,
                                             NotificationService notificationService,
                                             AdminAlertRecipients adminAlertRecipients,
                                             ConfigCacheHolder configCacheHolder,
                                             ReleaseManifestService manifestService) {
        this.jdbc = jdbc;
        this.planService = planService;
        this.commandService = commandService;
        this.suiteService = suiteService;
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
        this.configCacheHolder = configCacheHolder;
        this.manifestService = manifestService;
    }

    @Override
    public void advanceOnce() {
        for (UpgradePlanInfo plan : planService.listAll()) {
            try {
                if ("RUNNING".equals(plan.status())) {
                    advanceRunningPlan(plan);
                } else if ("BRAKED".equals(plan.status())) {
                    consumeRollbackReceipts(plan);
                }
            } catch (Exception e) {
                log.warn("升级计划 #{} 推进失败（下轮自动重试）: {}", plan.id(), e.getMessage());
            }
        }
    }


    private void advanceRunningPlan(UpgradePlanInfo plan) {
        List<UpgradePlanStepView> steps = stepsOf(plan.id());
        JsonNode strategy = readJson(plan.strategy());

        if (pollReceipts(plan, steps)) {
            return;
        }

        // Platform waves ride the manifest-pinned authority + agent health
        // receipts (03 §2.9, design 8A.4). The canary-suite/baselineRef gates
        // below are engine semantics — a platform plan never enters them; the
        // safety net here is the wave order plus the agent-side local rollback.
        if ("platform".equals(plan.scope())) {
            advancePlatformWaves(plan);
            return;
        }

        if (steps.stream().anyMatch(s -> "node".equals(s.kind()))) {
            brakeWithCanaryAlert(plan, "节点 agent 升级的金版本窗口消费位尚未实装——"
                    + "本计划含节点步，自动刹车防止半成品派发；引擎升级请建 engine 范围计划");
            return;
        }
        long baselineRef = parseLongOr(textOf(strategy.path("baselineRef")), -1L);
        if (baselineRef <= 0) {
            brakeWithCanaryAlert(plan, "金丝雀金样例缺失：升级策略未指定有效的回归套件基线"
                    + "（baselineRef 必须是回归集数字 id）——自动刹车，未下发任何升级命令；"
                    + "请先在回归集页选好基线套件再重建计划");
            return;
        }

        steps = stepsOf(plan.id());
        List<UpgradePlanStepView> canary = steps.stream()
                .filter(s -> "canary".equals(s.phase())).toList();
        if (canary.isEmpty()) {
            brakeWithCanaryAlert(plan, "升级计划无灰度槽步——金丝雀缺位不放行分批推进"
                    + "（启动时应至少指派一台灰度机）");
            return;
        }
        for (UpgradePlanStepView s : canary) {
            if (parseLongOr(s.nodeId(), -1L) <= 0) {
                brakeWithCanaryAlert(plan, "灰度节点标识「" + s.nodeId()
                        + "」不是已入池执行器 id——升级派发按执行器行寻址（台账 node_id 同谱），禁白烧");
                return;
            }
        }

        dispatchSteps(plan, canary);
        steps = stepsOf(plan.id());
        canary = steps.stream().filter(s -> "canary".equals(s.phase())).toList();
        if (canary.stream().anyMatch(s -> !"SUCCESS".equals(s.status()))) {
            return;
        }

        Long canaryPlanId = evidenceLong(canary.get(0).evidence(), "canaryPlanId");
        if (canaryPlanId == null) {
            runCanarySuite(plan, baselineRef, canary.get(0));
            return;
        }
        UpgradeSuiteOutcome outcome = evalCanarySuite(canaryPlanId);
        if (outcome == UpgradeSuiteOutcome.WAITING) {
            return;
        }
        if (outcome == UpgradeSuiteOutcome.NO_TERMINAL_TASK) {
            brakeAndRollback(plan, "金丝雀回归套件批次已全部终态但无终态执行任务（空跑）——"
                    + "通过率无从判定，自动刹车防无证据放行；请核对基线套件用例配置", canary);
            return;
        }
        if (outcome == UpgradeSuiteOutcome.BELOW_MIN) {
            brakeAndRollback(plan, "金丝雀回归套件通过率低于下限 "
                    + intConfig(PASS_RATE_MIN_KEY, DEFAULT_PASS_RATE_MIN)
                    + "%——自动刹车，灰度机已排入回滚", canary);
            return;
        }
        notifyAdmins("BATCH_DONE", plan.id(), "灰度验证通过",
                "升级计划 #" + plan.id() + " 金丝雀回归套件达标，进入分批推进阶段");

        advanceBatchWaves(plan, strategy, stepsOf(plan.id()));
    }

    
    private void advanceBatchWaves(UpgradePlanInfo plan, JsonNode strategy,
                                   List<UpgradePlanStepView> steps) {
        List<UpgradePlanStepView> batchSteps = steps.stream()
                .filter(s -> "batch".equals(s.phase()))
                .sorted((a, b) -> a.nodeId().compareTo(b.nodeId()))
                .toList();
        if (batchSteps.isEmpty()) {
            planService.complete(plan.id());
            return;
        }
        int size = (int) Math.max(DEFAULT_BATCH_SIZE,
                parseLongOr(textOf(strategy.path("batchSize")), DEFAULT_BATCH_SIZE));
        int cool = (int) Math.max(0, parseLongOr(textOf(strategy.path("cool")), DEFAULT_COOL));
        LocalDateTime now = LocalDateTime.now();

        for (int from = 0; from < batchSteps.size(); from += size) {
            List<UpgradePlanStepView> wave = batchSteps.subList(from,
                    Math.min(from + size, batchSteps.size()));
            if (wave.stream().anyMatch(s -> "FAILED".equals(s.status()))) {
                return;
            }
            if (wave.stream().allMatch(s -> "SUCCESS".equals(s.status()))) {
                continue;
            }
            if (wave.stream().anyMatch(s -> "RUNNING".equals(s.status()))) {
                return;
            }
            if (from > 0) {
                List<UpgradePlanStepView> prev = batchSteps.subList(Math.max(0, from - size), from);
                LocalDateTime prevFinish = prev.stream()
                        .map(this::stepUpdatedAt).max(LocalDateTime::compareTo).orElse(now);
                if (!cooldownElapsed(now, prevFinish, cool)) {
                    return;
                }
            }
            dispatchSteps(plan, wave);
            return;
        }
        planService.complete(plan.id());
    }

    // ---------- Platform wave advance: manifest authority + token-channel dispatch ----------

    /**
     * Platform wave advance: walk the persisted wave_seq strictly one wave at a
     * time — forward = agent group first, core last; rollback = the same
     * sequence reversed (direction from the single-source strategy flag). A
     * wave is left alone while any of its steps is RUNNING (waiting for agent
     * receipts) and the plan completes only after the last wave lands.
     * Decision-based (no sleep) and idempotent on re-scan: steps already
     * carrying a command_id are never re-dispatched, so a core restart
     * mid-wave resumes without double-issuing.
     */
    private void advancePlatformWaves(UpgradePlanInfo plan) {
        TreeMap<Integer, List<UpgradePlanStepView>> waves = new TreeMap<>();
        for (UpgradePlanStepView step : stepsOf(plan.id())) {
            int seq = step.waveSeq() == null ? Integer.MAX_VALUE : step.waveSeq();
            waves.computeIfAbsent(seq, k -> new ArrayList<>()).add(step);
        }
        if (waves.isEmpty()) {
            brakeWithCanaryAlert(plan, "平台升级计划没有任何波次步——无升级权威不可放行，"
                    + "请回升级中心按发布清单重建计划");
            return;
        }
        boolean rollback = WavePlanGenerator.isRollbackDirection(plan.strategy());
        Collection<List<UpgradePlanStepView>> ordered =
                rollback ? waves.descendingMap().values() : waves.values();
        for (List<UpgradePlanStepView> wave : ordered) {
            // Wave fully landed: judge the next one.
            if (wave.stream().allMatch(s -> "SUCCESS".equals(s.status()))) {
                continue;
            }
            // Wave in flight: wait for service-agent receipts, never jump waves.
            if (wave.stream().anyMatch(s -> "RUNNING".equals(s.status()))) {
                return;
            }
            List<UpgradePlanStepView> pending = wave.stream()
                    .filter(s -> "PENDING".equals(s.status()) && s.commandId() == null).toList();
            // Mixed residue (e.g. FAILED after a lost brake race): the receipt
            // poller owns braking, this branch only prevents a spin.
            if (pending.isEmpty()) {
                return;
            }
            dispatchPlatformWave(plan, pending, rollback);
            return;
        }
        planService.complete(plan.id());
    }

    /**
     * Dispatch one platform wave over the enroll-token channel (consumer =
     * installkit service-agent, protocol 03 section 2.9). Every element is
     * resolved for the whole wave BEFORE any command is queued — a missing
     * token address, a missing release registration, or a missing artifact
     * brakes the plan with zero commands (fail-closed: no half dispatch, no
     * guessed package). Forward waves pin to the plan's manifest version;
     * rollback waves pin to the ledger last-known-good and take their artifact
     * from that old version's registered manifest (supply chain closed on both
     * directions).
     */
    private void dispatchPlatformWave(UpgradePlanInfo plan, List<UpgradePlanStepView> wave,
                                      boolean rollback) {
        List<Map.Entry<UpgradePlanStepView, Map<String, Object>>> ready = new ArrayList<>();
        for (UpgradePlanStepView step : wave) {
            long tokenId = parseTokenTarget(step.nodeId());
            if (tokenId <= 0) {
                brakeWithCanaryAlert(plan, "平台波次「" + step.componentFamily() + "」的节点标识「"
                        + step.nodeId() + "」不是已入池主机令牌（token:{id} 形态）——平台命令的唯一"
                        + "消费方是该主机 service-agent 的令牌通道，缺令牌禁白烧派发，自动刹车未下发任何命令；"
                        + "请先在机群页为该主机签发令牌入池");
                return;
            }
            String family = step.componentFamily();
            String version = step.targetVersion();
            if (family == null || family.isBlank() || version == null || version.isBlank()) {
                brakeWithCanaryAlert(plan, "平台波次步缺少部件族或目标版本（step=" + step.id()
                        + "）——无权威禁派发，自动刹车");
                return;
            }
            String manifestVersion = rollback ? version : plan.manifestVersion();
            ReleaseManifestInfo manifest = manifestVersion == null ? null
                    : manifestService.findByVersion(manifestVersion);
            if (manifest == null) {
                brakeWithCanaryAlert(plan, (rollback ? "回滚目标版本 " : "升级清单版本 ") + manifestVersion
                        + " 在发布清单中没有登记件——缺登记=没有可信工件可下发（禁猜包派发），"
                        + "波次「" + family + "」自动刹车未下发任何命令；请先在升级中心登记该版本的发布清单");
                return;
            }
            JsonNode artifact = artifactOfManifest(manifest, family);
            if (artifact == null) {
                brakeWithCanaryAlert(plan, "发布清单 " + manifestVersion + " 缺部件族「" + family
                        + "」的部署工件（url+sha256）——缺件禁猜包派发，波次自动刹车未下发任何命令；"
                        + "请补齐清单 artifacts 下载清单后重建计划");
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (!"executor-agent".equals(family)) {
                payload.put("service", family);
            }
            payload.put("target_version", version);
            payload.put("artifact_url", artifact.path("url").asText(""));
            payload.put("sha256", artifact.path("sha256").asText(""));
            ready.add(Map.entry(step, payload));
        }

        for (Map.Entry<UpgradePlanStepView, Map<String, Object>> item : ready) {
            UpgradePlanStepView step = item.getKey();
            boolean agentFamily = "executor-agent".equals(step.componentFamily());
            String type = agentFamily
                    ? (rollback ? "agent-rollback" : "agent-upgrade")
                    : (rollback ? "platform-service-rollback" : "platform-service-upgrade");
            ClusterCommandView cmd;
            try {
                cmd = commandService.enqueue(new ClusterCommandInput(type, "enroll-token",
                        parseTokenTarget(step.nodeId()), item.getValue(), plan.operator()));
            } catch (ServiceException e) {
                // Token revoked/expired between resolution and enqueue: honest
                // brake (the queue gate's plain-language reason already names
                // what happened — pass it through, dispatch nothing further).
                brakeWithCanaryAlert(plan, "平台波次派发被命令闸拒绝：" + e.getMessage()
                        + "（本波未继续下发，自动刹车）");
                return;
            }
            jdbc.update("UPDATE tp_upgrade_plan_step SET status = 'RUNNING', command_id = :cid,"
                            + " updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("cid", cmd.id())
                            .addValue("id", step.id()));
        }
        notifyAdmins("UPGRADE_STARTED", plan.id(), "平台升级波次已派发",
                "升级计划 #" + plan.id() + "（" + scopeLabel(plan.scope()) + "）已派发波次 "
                        + ready.size() + " 台命令，等待主机 service-agent 领取回执");
    }

    /** Artifact row of one family inside a registered manifest; null = absent. */
    private JsonNode artifactOfManifest(ReleaseManifestInfo manifest, String family) {
        try {
            for (JsonNode item : readJson(manifest.artifacts())) {
                if (family.equalsIgnoreCase(item.path("component_family").asText("").trim())) {
                    return item;
                }
            }
        } catch (Exception e) {
            return null; // unparseable artifact list = treated as missing (fail-closed)
        }
        return null;
    }

    /** Token channel target id from the ledger node shape "token:<id>"; -1 = not token-addressed. */
    private static long parseTokenTarget(String nodeId) {
        if (nodeId == null || !nodeId.startsWith("token:")) {
            return -1L;
        }
        return parseLongOr(nodeId.substring("token:".length()), -1L);
    }


    
    private boolean pollReceipts(UpgradePlanInfo plan, List<UpgradePlanStepView> steps) {
        List<UpgradePlanStepView> running = steps.stream()
                .filter(s -> "RUNNING".equals(s.status()) && s.commandId() != null).toList();
        if (running.isEmpty()) {
            return false;
        }
        boolean touched = false;
        for (UpgradePlanStepView step : running) {
            Integer status = commandStatus(step.commandId());
            if (status == null) {
                continue;
            }
            if (status == CMD_SUCCESS) {
                jdbc.update("UPDATE tp_upgrade_plan_step SET status = 'SUCCESS', updated_at = now()"
                        + " WHERE id = :id", new MapSqlParameterSource("id", step.id()));
                stampLastKnownGood(step);
                touched = true;
            } else if (status == CMD_FAILED) {
                brakeOnCommandFailure(plan, step, running);
                return true;
            }
        }
        UpgradePlanStepView earliest = running.stream()
                .min((a, b) -> stepUpdatedAt(a).compareTo(stepUpdatedAt(b))).orElse(null);
        if (earliest != null) {
            int timeout = "canary".equals(earliest.phase())
                    ? intConfig(CANARY_TIMEOUT_KEY, DEFAULT_CANARY_TIMEOUT)
                    : intConfig(BATCH_TIMEOUT_KEY, DEFAULT_BATCH_TIMEOUT);
            if (Duration.between(stepUpdatedAt(earliest), LocalDateTime.now()).getSeconds() > timeout) {
                brakeOnTimeout(plan, earliest, running, timeout);
                return true;
            }
        }
        return !touched;
    }

    /**
     * Ledger write-back on a landed health receipt: the target version this
     * step swapped becomes the node x family last-known-good — the rollback
     * authority consumed by the one-click rollback plan. Engine steps carry no
     * platform family column and stay untouched (their rollback source is the
     * existing per-executor receipt chain).
     */
    private void stampLastKnownGood(UpgradePlanStepView step) {
        if (step.componentFamily() == null || step.targetVersion() == null
                || step.targetVersion().isBlank()) {
            return;
        }
        jdbc.update("UPDATE tp_cluster_component SET last_known_good = :v, updated_at = now()"
                        + " WHERE node_id = :node AND component_family = :family",
                new MapSqlParameterSource().addValue("v", step.targetVersion())
                        .addValue("node", step.nodeId()).addValue("family", step.componentFamily()));
    }

    private void brakeOnCommandFailure(UpgradePlanInfo plan, UpgradePlanStepView failedStep,
                                       List<UpgradePlanStepView> running) {
        String reason = "升级命令执行失败（命令 #" + failedStep.commandId()
                + "，节点 " + failedStep.nodeId() + "，阶段 " + failedStep.phase()
                + "）——自动刹车";
        if ("canary".equals(failedStep.phase())) {
            brakeAndRollback(plan, reason + "，灰度机已排入回滚",
                    running.stream().filter(s -> "canary".equals(s.phase()))
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        } else {
            brakePlan(plan, reason + "（灰度机已验证通过不回滚；请核对命令回执后重建剩余计划）");
            notifyAdmins("CANARY_FAILED", plan.id(), "升级自动刹车", reason);
        }
    }

    private void brakeOnTimeout(UpgradePlanInfo plan, UpgradePlanStepView earliest,
                                List<UpgradePlanStepView> running, int timeoutSeconds) {
        String reason = "升级命令超时未回执（节点 " + earliest.nodeId() + " 阶段 "
                + earliest.phase() + " 已超 " + timeoutSeconds + " 秒）——自动刹车";
        if ("canary".equals(earliest.phase())) {
            brakeAndRollback(plan, reason + "，灰度机已排入回滚",
                    running.stream().filter(s -> "canary".equals(s.phase()))
                            .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        } else {
            brakePlan(plan, reason + "（在跑命令不打断，回执迟到走命令队列幂等分支）");
            notifyAdmins("CANARY_FAILED", plan.id(), "升级自动刹车", reason);
        }
    }


    
    private void dispatchSteps(UpgradePlanInfo plan, List<UpgradePlanStepView> steps) {
        List<UpgradePlanStepView> pending = steps.stream()
                .filter(s -> "PENDING".equals(s.status()) && s.commandId() == null).toList();
        if (pending.isEmpty()) {
            return;
        }
        for (UpgradePlanStepView step : pending) {
            long targetId = parseLongOr(step.nodeId(), -1L);
            if (targetId <= 0) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("engines", List.of(step.engineCode() == null ? "" : step.engineCode()));
            ClusterCommandView cmd = commandService.enqueue(new ClusterCommandInput(
                    "engine-upgrade", "executor", targetId, payload, plan.operator()));
            jdbc.update("UPDATE tp_upgrade_plan_step SET status = 'RUNNING', command_id = :cid,"
                            + " updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("cid", cmd.id())
                            .addValue("id", step.id()));
        }
        notifyAdmins("UPGRADE_STARTED", plan.id(), "升级派发开始",
                "升级计划 #" + plan.id() + "（" + scopeLabel(plan.scope()) + "）已派发 "
                        + pending.size() + " 台命令，等待执行器领取回执");
    }

    
    private void brakeWithCanaryAlert(UpgradePlanInfo plan, String reason) {
        brakePlan(plan, reason);
        notifyAdmins("CANARY_FAILED", plan.id(), "升级自动刹车", reason);
    }

    
    private void brakeAndRollback(UpgradePlanInfo plan, String reason,
                                  List<UpgradePlanStepView> canary) {
        brakePlan(plan, reason);
        notifyAdmins("CANARY_FAILED", plan.id(), "升级自动刹车", reason);
        List<UpgradePlanStepView> freshCanary = canary.isEmpty()
                ? stepsOf(plan.id()).stream().filter(s -> "canary".equals(s.phase())).toList()
                : canary;
        for (UpgradePlanStepView step : freshCanary) {
            if (evidenceLong(step.evidence(), "rollbackCommandId") != null) {
                continue;
            }
            long targetId = parseLongOr(step.nodeId(), -1L);
            if (targetId <= 0 || step.currentVersion() == null || step.currentVersion().isBlank()) {
                log.warn("升级计划 #{} 灰度节点 {} 无法排队回滚（缺执行器 id 或原版本）——人工介入",
                        plan.id(), step.nodeId());
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("engines", List.of(step.engineCode() == null ? "" : step.engineCode()));
            payload.put("target_version", step.currentVersion());
            ClusterCommandView rbk = commandService.enqueue(new ClusterCommandInput(
                    "engine-rollback", "executor", targetId, payload, plan.operator()));
            mergeEvidence(step.id(), Map.of("rollbackCommandId", rbk.id()));
        }
    }

    
    private void consumeRollbackReceipts(UpgradePlanInfo plan) {
        List<UpgradePlanStepView> rollbackPending = new ArrayList<>();
        boolean anyRollbackStep = false;
        for (UpgradePlanStepView step : stepsOf(plan.id())) {
            Long rbkId = evidenceLong(step.evidence(), "rollbackCommandId");
            if (rbkId == null) {
                continue;
            }
            if ("rollback".equals(step.phase())) {
                anyRollbackStep = true;
            } else if (isCommandSuccess(rbkId)) {
                rollbackPending.add(step);
            }
        }
        if (!rollbackPending.isEmpty()) {
            for (UpgradePlanStepView step : rollbackPending) {
                jdbc.update("UPDATE tp_upgrade_plan_step SET phase = 'rollback', updated_at = now()"
                        + " WHERE id = :id", new MapSqlParameterSource("id", step.id()));
            }
            anyRollbackStep = true;
        }
        if (anyRollbackStep && noUnlandedRollback(plan)) {
            notifyAdmins("ROLLBACK_DONE", plan.id(), "灰度机回滚完成",
                    "升级计划 #" + plan.id() + " 灰度机回滚命令全部成功，机器已恢复原版本；"
                            + "计划保持已刹车终态，修复后可重建新计划");
        }
    }

    private boolean noUnlandedRollback(UpgradePlanInfo plan) {
        for (UpgradePlanStepView step : stepsOf(plan.id())) {
            Long rbkId = evidenceLong(step.evidence(), "rollbackCommandId");
            if (rbkId != null && !"rollback".equals(step.phase())) {
                return false;
            }
        }
        return true;
    }

    private void brakePlan(UpgradePlanInfo plan, String reason) {
        try {
            planService.brake(plan.id(), reason);
        } catch (Exception e) {
            log.info("升级计划 #{} 刹车未生效（可能已终态，CAS 保护）: {}", plan.id(), e.getMessage());
        }
    }


    private void runCanarySuite(UpgradePlanInfo plan, long baselineRef, UpgradePlanStepView anchor) {
        try {
            SuiteRunResult result = suiteService.run(suiteOperator(plan), baselineRef);
            mergeEvidence(anchor.id(), Map.of("canaryPlanId", result.planId()));
            log.info("升级计划 {} 金丝雀套件 {} 已触发，执行计划 {}",
                    plan.id(), baselineRef, result.planId());
        } catch (Exception e) {
            brakeWithCanaryAlert(plan, "金丝雀回归套件触发失败：" + e.getMessage()
                    + "——自动刹车（基线套件 id=" + baselineRef + " 需存在且配置完整）");
        }
    }

    private UpgradeSuiteOutcome evalCanarySuite(long canaryPlanId) {
        Integer notTerminal = jdbc.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE plan_id = :planId"
                        + " AND status NOT IN (:terminal)",
                new MapSqlParameterSource().addValue("planId", canaryPlanId)
                        .addValue("terminal", BATCH_TERMINAL), Integer.class);
        if (notTerminal == null || notTerminal > 0) {
            return UpgradeSuiteOutcome.WAITING;
        }
        List<Long> batchIds = jdbc.queryForList(
                "SELECT id FROM tp_exec_batch WHERE plan_id = :planId",
                new MapSqlParameterSource("planId", canaryPlanId), Long.class);
        if (batchIds.isEmpty()) {
            return UpgradeSuiteOutcome.NO_TERMINAL_TASK;
        }
        Double rate = jdbc.queryForObject("SELECT " + PassRateSql.RATE_EXPR
                + " FROM tp_exec_task t WHERE t.batch_id IN (:ids)",
                new MapSqlParameterSource("ids", batchIds), Double.class);
        if (rate == null) {
            return UpgradeSuiteOutcome.NO_TERMINAL_TASK;
        }
        double min = intConfig(PASS_RATE_MIN_KEY, DEFAULT_PASS_RATE_MIN) / 100.0;
        return rate >= min ? UpgradeSuiteOutcome.PASSED : UpgradeSuiteOutcome.BELOW_MIN;
    }

    private long suiteOperator(UpgradePlanInfo plan) {
        long parsed = parseLongOr(plan.operator(), -1L);
        if (parsed > 0) {
            return parsed;
        }
        List<Long> admins = adminAlertRecipients.adminUserIds();
        return admins.isEmpty() ? 0L : admins.get(0);
    }


    private void notifyAdmins(String event, long planId, String title, String content) {
        for (Long adminId : adminAlertRecipients.adminUserIds()) {
            try {
                notificationService.create(new NotificationCreateInput(adminId, null, event,
                        title, content, event + ":" + planId + ":" + adminId));
            } catch (Exception e) {
                log.warn("升级通知 {} 发送失败（plan={} admin={}）: {}", event, planId, adminId,
                        e.getMessage());
            }
        }
    }


    private Integer commandStatus(long commandId) {
        return jdbc.queryForObject("SELECT status FROM tp_cluster_command WHERE id = :id",
                new MapSqlParameterSource("id", commandId), Integer.class);
    }

    private boolean isCommandSuccess(long commandId) {
        Integer status = commandStatus(commandId);
        return status != null && status == CMD_SUCCESS;
    }

    private List<UpgradePlanStepView> stepsOf(long planId) {
        return jdbc.query("SELECT id, plan_id, node_id, kind, engine_code, phase,"
                        + " current_version, target_version, status, command_id, evidence::text,"
                        + " component_family, wave_seq"
                        + " FROM tp_upgrade_plan_step WHERE plan_id = :planId ORDER BY"
                        + " CASE phase WHEN 'canary' THEN 0 WHEN 'batch' THEN 1 ELSE 2 END,"
                        + " COALESCE(wave_seq, -1), node_id, engine_code",
                new MapSqlParameterSource("planId", planId),
                (rs, n) -> new UpgradePlanStepView(rs.getLong("id"), rs.getLong("plan_id"),
                        rs.getString("node_id"), rs.getString("kind"), rs.getString("engine_code"),
                        rs.getString("phase"), rs.getString("current_version"),
                        rs.getString("target_version"), rs.getString("status"),
                        rs.getObject("command_id") == null ? null : rs.getLong("command_id"),
                        rs.getString("evidence"), rs.getString("component_family"),
                        rs.getObject("wave_seq") == null ? null : rs.getInt("wave_seq")));
    }

    private void mergeEvidence(long stepId, Map<String, Object> patch) {
        try {
            jdbc.update("UPDATE tp_upgrade_plan_step SET evidence = evidence || CAST(:ev AS jsonb),"
                            + " updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource().addValue("id", stepId)
                            .addValue("ev", objectMapper.writeValueAsString(patch)));
        } catch (Exception e) {
            log.warn("升级步证据链写入失败（step={}）: {}", stepId, e.getMessage());
        }
    }

    private Long evidenceLong(String evidenceJson, String key) {
        JsonNode value = readJson(evidenceJson).path(key);
        return value.isNumber() ? value.asLong() : null;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    
    private LocalDateTime stepUpdatedAt(UpgradePlanStepView step) {
        LocalDateTime ts = jdbc.queryForObject("SELECT updated_at FROM tp_upgrade_plan_step"
                + " WHERE id = :id", new MapSqlParameterSource("id", step.id()), LocalDateTime.class);
        return ts == null ? LocalDateTime.now() : ts;
    }

    private int intConfig(String key, int fallback) {
        String raw = configCacheHolder.get(key);
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("配置键 {} 非法值「{}」，回退保守默认 {}", key, raw, fallback);
            }
        }
        return fallback;
    }

    
    public static boolean cooldownElapsed(LocalDateTime now, LocalDateTime lastFinish, int coolSeconds) {
        if (lastFinish == null) {
            return true;
        }
        return Duration.between(lastFinish, now).getSeconds() >= coolSeconds;
    }

    private static String textOf(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static long parseLongOr(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String scopeLabel(String scope) {
        if ("platform".equals(scope)) {
            return "平台一键升级";
        }
        return "engine".equals(scope) ? "引擎升级" : "节点 agent 升级";
    }
}
