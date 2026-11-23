package com.sixbean.testpike.core.cluster.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.ArtifactPrecheckItemView;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.ComponentVerdict;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.component.ComponentSupportRules;
import com.sixbean.testpike.core.cluster.domain.component.ReleaseArtifactProber;
import com.sixbean.testpike.core.cluster.domain.component.WavePlanGenerator;
import com.sixbean.testpike.core.cluster.domain.service.ReleaseManifestService;
import com.sixbean.testpike.core.cluster.domain.service.UpgradePlanService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;


@Service
public class UpgradePlanServiceImpl implements UpgradePlanService {

    private static final Logger log = LoggerFactory.getLogger(UpgradePlanServiceImpl.class);

 
    static final Set<String> SCOPES = Set.of("engine", "node", "platform");

 
    static final String PREVIEW = "PREVIEW";
    static final String RUNNING = "RUNNING";
    static final String PAUSED = "PAUSED";
    static final String COMPLETED = "COMPLETED";
    static final String ABORTED = "ABORTED";
    static final String BRAKED = "BRAKED";

 
    static final String COOL_KEY = "cluster.upgrade-cooldown-seconds";
    static final int DEFAULT_COOL_SECONDS = 300;

    
    static final String BATCH_TIMEOUT_KEY = "cluster.batch-timeout-seconds";
    static final int DEFAULT_BATCH_TIMEOUT_SECONDS = 3600;

    static final int BATCH_SIZE_MAX = 1000;

 
    static final String AGENT_FAMILY = "executor-agent";

    /** Production dual-review switch (decision E K8 key landed in V225; 1 = strict). */
    static final String DUAL_REVIEW_KEY = "release.production.dual-review";

    private static final String PLAN_COLUMNS = """
            id, scope, target_engine_codes::text, strategy::text, manifest_version,
            production_scope, maintenance_window, reviewed_by,
            reviewed_at, status, brake_reason, operator, created_at
            """;

    private static final RowMapper<UpgradePlanInfo> PLAN_MAPPER = (rs, rowNum) ->
            new UpgradePlanInfo(rs.getLong("id"), rs.getString("scope"),
                    rs.getString("target_engine_codes"), rs.getString("strategy"),
                    rs.getString("manifest_version"), rs.getBoolean("production_scope"),
                    rs.getString("maintenance_window"), rs.getString("reviewed_by"),
                    rs.getTimestamp("reviewed_at") == null ? null
                            : rs.getTimestamp("reviewed_at").toLocalDateTime(),
                    rs.getString("status"),
                    rs.getString("brake_reason"), rs.getString("operator"),
                    rs.getTimestamp("created_at").toLocalDateTime());

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigCacheHolder configCacheHolder;
    private final AppautoEngineService engineService;
    private final ReleaseManifestService manifestService;
    private final ReleaseArtifactProber artifactProber;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UpgradePlanServiceImpl(NamedParameterJdbcTemplate jdbc,
                                  ConfigCacheHolder configCacheHolder,
                                  AppautoEngineService engineService,
                                  ReleaseManifestService manifestService,
                                  ReleaseArtifactProber artifactProber) {
        this.jdbc = jdbc;
        this.configCacheHolder = configCacheHolder;
        this.engineService = engineService;
        this.manifestService = manifestService;
        this.artifactProber = artifactProber;
    }

    @Override
    @Transactional
    public UpgradePlanInfo create(UpgradePlanCreateInput input) {
        String scope = validatedScope(input);
        List<String> engineCodes = normalizeEngineCodes(input.engineCodes());
        gateCodesAgainstScope(scope, engineCodes);
        int batchSize = validatedBatchSize(input);
        String manifestVersion = validatedManifestRef(input);
        Map<String, String> pinnedTargets = platformPinnedTargets(scope, manifestVersion);

        // Preview computation = ledger × judgement spectrum (no second
        // spectrum; GREEN/grey rows never become steps)
        List<UpgradePlanStepView> preview = requiredPreview(scope, engineCodes, pinnedTargets);
        List<String> canary = validatedCanaries(input, preview);

        String strategyJson = buildStrategy(canary, batchSize, input.baselineRef());
        String engineCodesJson = writeJson(engineCodes);

        jdbc.update("""
                INSERT INTO tp_upgrade_plan
                    (scope, target_engine_codes, strategy, manifest_version,
                     production_scope, maintenance_window, operator)
                VALUES (:scope, :codes::jsonb, :strategy::jsonb, :manifest,
                        :production, :window, :operator)
                """, new MapSqlParameterSource()
                .addValue("scope", scope)
                .addValue("codes", engineCodesJson)
                .addValue("strategy", strategyJson)
                .addValue("manifest", manifestVersion)
                .addValue("production", Boolean.TRUE.equals(input.productionScope()))
                .addValue("window", validatedWindowLength(input.maintenanceWindow()))
                .addValue("operator", trimToNull(input.operator())));

        Long planId = jdbc.queryForObject("SELECT currval(pg_get_serial_sequence('tp_upgrade_plan','id'))",
                new MapSqlParameterSource(), Long.class);

        Set<String> canarySet = new LinkedHashSet<>(canary);
        for (UpgradePlanStepView step : preview) {
            String phase = canarySet.contains(step.nodeId()) ? "canary" : "batch";
            jdbc.update("""
                    INSERT INTO tp_upgrade_plan_step
                        (plan_id, node_id, kind, engine_code, phase,
                         current_version, target_version, evidence,
                         component_family, wave_seq)
                    VALUES (:planId, :node, :kind, :engine, :phase,
                            :cur, :target, '{}'::jsonb, :family, :waveSeq)
                    """, new MapSqlParameterSource()
                    .addValue("planId", planId)
                    .addValue("node", step.nodeId())
                    .addValue("kind", step.kind())
                    .addValue("engine", step.engineCode())
                    .addValue("phase", phase)
                    .addValue("cur", step.currentVersion())
                    .addValue("target", step.targetVersion())
                    .addValue("family", step.componentFamily())
                    .addValue("waveSeq", step.waveSeq()));
        }

        return get(planId);
    }

    @Override
    public UpgradePlanImpactView impact(UpgradePlanCreateInput input) {
        String scope = validatedScope(input);
        List<String> engineCodes = normalizeEngineCodes(input.engineCodes());
        gateCodesAgainstScope(scope, engineCodes);
        int batchSize = validatedBatchSize(input);
        String manifestVersion = validatedManifestRef(input);
        Map<String, String> pinnedTargets = platformPinnedTargets(scope, manifestVersion);
        List<UpgradePlanStepView> preview = requiredPreview(scope, engineCodes, pinnedTargets);
        List<String> canary = validatedCanaries(input, preview);
        List<ArtifactPrecheckItemView> precheck = artifactPrecheck(manifestVersion);

        Set<String> nodes = new LinkedHashSet<>();
        preview.forEach(s -> nodes.add(s.nodeId()));
        int waves = (nodes.size() + batchSize - 1) / batchSize;
        int cool = cooldownSeconds();
        long seconds = (long) nodes.size() * intConfig(BATCH_TIMEOUT_KEY, DEFAULT_BATCH_TIMEOUT_SECONDS);
        int minutes = (int) Math.max(1, Math.ceil((seconds + (long) (waves - 1) * cool) / 60.0));
        Integer active = jdbc.queryForObject("SELECT count(*) FROM tp_upgrade_plan"
                + " WHERE status IN (:sts)",
                new MapSqlParameterSource("sts", List.of(RUNNING, PAUSED, PREVIEW)), Integer.class);
        return new UpgradePlanImpactView(scope, engineCodes, canary, nodes.size(),
                preview.size(), waves, batchSize, cool, minutes, active == null ? 0 : active,
                precheck);
    }

    @Override
    public UpgradePlanInfo get(long planId) {
        List<UpgradePlanInfo> rows = jdbc.query(
                "SELECT " + PLAN_COLUMNS + " FROM tp_upgrade_plan WHERE id = :id",
                new MapSqlParameterSource("id", planId), PLAN_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("升级计划 #" + planId + " 不存在（可能已被清理）——请前往「升级中心」"
                    + "的「升级计划」页签从列表确认现存计划，或点「新建计划」重建",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    @Override
    public List<UpgradePlanInfo> listAll() {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM tp_upgrade_plan"
                + " ORDER BY created_at DESC, id DESC", new MapSqlParameterSource(), PLAN_MAPPER);
    }

    @Override
    public List<UpgradePlanStepView> steps(long planId) {
        UpgradePlanInfo plan = get(planId); // absent plan = human-worded reject
        List<UpgradePlanStepView> steps = jdbc.query("""
                SELECT id, plan_id, node_id, kind, engine_code, phase,
                       current_version, target_version, status, command_id, evidence::text,
                       component_family, wave_seq
                FROM tp_upgrade_plan_step
                WHERE plan_id = :planId
                ORDER BY CASE phase WHEN 'canary' THEN 0 WHEN 'batch' THEN 1 ELSE 2 END,
                         COALESCE(wave_seq, -1), node_id, engine_code
                """, new MapSqlParameterSource("planId", planId),
                (rs, rowNum) -> new UpgradePlanStepView(rs.getLong("id"), rs.getLong("plan_id"),
                        rs.getString("node_id"), rs.getString("kind"), rs.getString("engine_code"),
                        rs.getString("phase"), rs.getString("current_version"),
                        rs.getString("target_version"), rs.getString("status"),
                        rs.getObject("command_id") == null ? null : rs.getLong("command_id"),
                        rs.getString("evidence"), rs.getString("component_family"),
                        rs.getObject("wave_seq") == null ? null : rs.getInt("wave_seq")));
        // A rollback plan walks the persisted wave order backwards (core back
        // first, agent group last). The direction lives in the strategy
        // snapshot; on-disk wave_seq stays the single forward spectrum.
        return rollbackDirection(plan) ? steps.reversed() : steps;
    }

    /** Whether one plan's strategy snapshot carries the rollback direction flag. */
    private boolean rollbackDirection(UpgradePlanInfo plan) {
        // Direction parsing is single-sourced in WavePlanGenerator (the advance
        // engine consumes the same verdict — no second copy of the flag rule).
        return WavePlanGenerator.isRollbackDirection(plan.strategy());
    }

    @Override
    @Transactional
    public UpgradePlanInfo createRollbackPlan(String operator) {
        // Rollback authority = ledger last-known-good per node x family —
        // never a typed or guessed version (the engine-rollback "no guessing"
        // discipline extended to the platform waves). A row without a recorded
        // good version never landed a successful upgrade receipt through this
        // pipeline (fresh agent enrollment, manual install), so rolling it back
        // is not applicable — skipping it is honest; fabricating a target is
        // not. An empty rolled-back set = human-worded rejection, no empty plan.
        List<Map<String, Object>> rollbackRows = new ArrayList<>();
        List<Map<String, Object>> ledger = jdbc.queryForList(
                "SELECT node_id, component_family, current_version, last_known_good"
                        + " FROM tp_cluster_component ORDER BY node_id, component_family",
                new MapSqlParameterSource());
        for (Map<String, Object> row : ledger) {
            String family = lower(String.valueOf(row.get("component_family")));
            if (!WavePlanGenerator.platformFamily(family)) {
                continue; // engine runtimes ride the existing engine-upgrade chain
            }
            String good = trimToNull((String) row.get("last_known_good"));
            if (good != null && !good.equals(trimToNull((String) row.get("current_version")))) {
                rollbackRows.add(row);
            }
        }
        if (rollbackRows.isEmpty()) {
            throw new ServiceException("当前无可回退部件——台账里没有「现版 ≠ 最后健康版」的"
                    + "平台族行（刚升级成功并落健康回执的节点才会被排入回滚；从未走过升级链的"
                    + "行没有可回退的目标，不冒充）；请在「升级中心」完成升级并落健康回执后再发起回退",
                    BizErrorCode.VALIDATION_ERROR);
        }

        Map<String, Object> strategy = new java.util.LinkedHashMap<>();
        strategy.put("direction", "rollback");
        strategy.put("canaryNodeIds", List.of());
        strategy.put("batchSize", 1);
        strategy.put("cool", cooldownSeconds());
        strategy.put("baselineRef", null);
        jdbc.update("""
                INSERT INTO tp_upgrade_plan
                    (scope, target_engine_codes, strategy, manifest_version, operator)
                VALUES ('platform', '[]'::jsonb, :strategy::jsonb, NULL, :operator)
                """, new MapSqlParameterSource()
                .addValue("strategy", writeJson(strategy))
                .addValue("operator", trimToNull(operator)));
        Long planId = jdbc.queryForObject(
                "SELECT currval(pg_get_serial_sequence('tp_upgrade_plan','id'))",
                new MapSqlParameterSource(), Long.class);

        // Persistence keeps the FORWARD wave_seq (single ordering spectrum on
        // disk); the rollback direction is consumed at the read layer only.
        for (Map<String, Object> row : rollbackRows) {
            String family = lower(String.valueOf(row.get("component_family")));
            jdbc.update("""
                    INSERT INTO tp_upgrade_plan_step
                        (plan_id, node_id, kind, engine_code, phase,
                         current_version, target_version, evidence,
                         component_family, wave_seq)
                    VALUES (:planId, :node, 'platform', NULL, 'batch',
                            :cur, :target, '{}'::jsonb, :family, :waveSeq)
                    """, new MapSqlParameterSource()
                    .addValue("planId", planId)
                    .addValue("node", row.get("node_id"))
                    .addValue("cur", trimToNull((String) row.get("current_version")))
                    .addValue("target", trimToNull((String) row.get("last_known_good")))
                    .addValue("family", family)
                    .addValue("waveSeq", WavePlanGenerator.waveSeq(family)));
        }
        return get(planId);
    }

    @Override
    public UpgradePlanInfo start(long planId) {
        UpgradePlanInfo plan = get(planId);
        // Decision E gate (hot K8 key, default 0 = permissive): a plan that
        // DECLARED production scope may not leave PREVIEW while the switch is
        // strict unless it carries a maintenance window AND a second person has
        // confirmed the review. Historical/non-production plans are untouched —
        // the statement lives on the row, we never guess the environment.
        if (Boolean.TRUE.equals(plan.productionScope())
                && intConfig(DUAL_REVIEW_KEY, 0) == 1) {
            if (PREVIEW.equals(plan.status())) {
                if (trimToNull(plan.maintenanceWindow()) == null) {
                    throw new ServiceException("升级计划 #" + planId + " 申报了生产范围，"
                            + "双人复核开严中（配置项「" + DUAL_REVIEW_KEY + "」=1）：启动前请先申报维护窗口"
                            + "（时间范围，如「2026-10-14 02:00–02:30」——告诉所有人什么时候动、"
                            + "动了影响多大，窗口外不启动）", BizErrorCode.VALIDATION_ERROR);
                }
                if (trimToNull(plan.reviewedBy()) == null) {
                    throw new ServiceException("升级计划 #" + planId + " 申报了生产范围，"
                            + "双人复核开严中：还缺第二人复核确认（发起人不能自审自批——"
                            + "请另一位持升级权限的同事在「升级中心」的「升级计划」页签对本计划点「双人复核」确认后再启动）",
                            BizErrorCode.VALIDATION_ERROR);
                }
            }
        }
        transition(planId, PREVIEW, RUNNING, null);
        Integer canaries = jdbc.queryForObject("""
                SELECT count(*) FROM tp_upgrade_plan_step
                WHERE plan_id = :planId AND phase = 'canary'
                """, new MapSqlParameterSource("planId", planId), Integer.class);
        if (canaries == null || canaries == 0) {
            jdbc.update("""
                    UPDATE tp_upgrade_plan_step SET phase = 'canary', updated_at = now()
                    WHERE id = (SELECT id FROM tp_upgrade_plan_step
                                WHERE plan_id = :planId AND phase = 'batch'
                                ORDER BY node_id, engine_code LIMIT 1)
                    """, new MapSqlParameterSource("planId", planId));
        }
        return get(planId);
    }

    @Override
    @Transactional
    public UpgradePlanInfo confirmDualReview(long planId, String reviewer) {
        // Second-person review for production-declared plans (decision E,
        // approval-flow pattern reused from the reservation gate: the reviewer
        // identity arrives as a parameter bridged from the login context —
        // never scraped from the request). CAS-shaped single UPDATE: only a
        // PREVIEW plan can be reviewed; self-review is refused because
        // "two people" is the entire meaning of the gate.
        String trimReviewer = trimToNull(reviewer);
        if (trimReviewer == null) {
            throw new ServiceException("复核确认必须携带复核人身份（登录态透传，禁空复核留痕）——"
                    + "请用已登录账号操作「双人复核」，或先在顶栏「选择项目」确认登录态后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        UpgradePlanInfo plan = get(planId);
        if (!PREVIEW.equals(plan.status())) {
            throw new ServiceException("升级计划 #" + planId + " 当前状态「"
                    + statusLabel(plan.status()) + "」不能复核确认——双人复核只对「预览」态计划开放"
                    + "（启动后改判请在「升级计划」页签点「终止」后重建计划，复核记录不覆写在途计划）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (trimReviewer.equals(trimToNull(plan.operator()))) {
            throw new ServiceException("复核人不能是发起人本人（" + trimReviewer
                    + " 已发起该计划）——双人复核的意义就是两个不同的人，请换另一位同事"
                    + "登录账号后在「双人复核」入口确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbc.update("UPDATE tp_upgrade_plan SET reviewed_by = :reviewer,"
                + " reviewed_at = now(), updated_at = now()"
                + " WHERE id = :id AND status = :preview AND reviewed_by IS NULL",
                new MapSqlParameterSource("id", planId).addValue("reviewer", trimReviewer)
                        .addValue("preview", PREVIEW));
        if (updated == 0) {
            // Lost the CAS race: someone reviewed or moved the plan between the
            // read and the write — report the fresh state instead of pretending.
            UpgradePlanInfo fresh = get(planId);
            throw new ServiceException("升级计划 #" + planId + " 复核状态已变化（现复核人「"
                    + (fresh.reviewedBy() == null ? "未复核" : fresh.reviewedBy())
                    + "」，状态「" + statusLabel(fresh.status())
                    + "」）——请在「升级中心」的「升级计划」页签刷新列表确认最新复核态后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return get(planId);
    }

    @Override
    public UpgradePlanInfo pause(long planId) {
        return transition(planId, RUNNING, PAUSED, null);
    }

    @Override
    public UpgradePlanInfo resume(long planId) {
        return transition(planId, PAUSED, RUNNING, null);
    }

    @Override
    public UpgradePlanInfo abort(long planId) {
        List<String> froms = List.of(RUNNING, PAUSED);
        int updated = jdbc.update("UPDATE tp_upgrade_plan SET status = :to, updated_at = now()"
                + " WHERE id = :id AND status IN (:froms)",
                new MapSqlParameterSource("id", planId).addValue("to", ABORTED)
                        .addValue("froms", froms));
        if (updated == 0) {
            UpgradePlanInfo current = get(planId);
            throw new ServiceException("升级计划 #" + planId + " 当前状态「" + statusLabel(current.status())
                    + "」不能终止——终止只对「运行中/已暂停」的计划开放（在跑批次自然收尾，不掐已完成计划）；"
                    + "如需再操作请在「升级计划」页签查看该计划当前状态",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return get(planId);
    }

    @Override
    public UpgradePlanInfo complete(long planId) {
        return transition(planId, RUNNING, COMPLETED, null);
    }

    @Override
    public UpgradePlanInfo brake(long planId, String reason) {
        String text = trimToNull(reason);
        if (text == null) {
            throw new ServiceException("自动刹车必须写明原因（供通知与证据链展示，禁留空）——"
                    + "请在触发刹车的告警详情或调用参数「reason」里补填一句话原因（如「探针失败率超限」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        return transition(planId, RUNNING, BRAKED, text);
    }

    
    private static String validatedScope(UpgradePlanCreateInput input) {
        String scope = trimToEmpty(input.scope());
        if (!SCOPES.contains(scope)) {
            throw new ServiceException("升级范围必须是 engine（引擎升级）、node（节点 agent 升级）"
                    + " 或 platform（平台一键升级）之一（当前："
                    + (scope.isEmpty() ? "未提供" : scope) + "）——请改成上述三个取值之一，"
                    + "在「新建计划」第一步的「升级范围」中选择",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return scope;
    }

    private static void gateCodesAgainstScope(String scope, List<String> engineCodes) {
        if ("engine".equals(scope) && engineCodes.isEmpty()) {
            throw new ServiceException("引擎升级请至少选择一个引擎（引擎中心的黄徽章清单可多选）——"
                    + "请在「新建计划」的引擎清单至少勾选一个，或先到「APP 自动化 → 引擎中心」确认引擎已上架",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if ("node".equals(scope) && !engineCodes.isEmpty()) {
            throw new ServiceException("节点 agent 升级不需要选引擎（「引擎 code」应留空；"
                    + "要升引擎请在「新建计划」改选引擎范围）", BizErrorCode.VALIDATION_ERROR);
        }
        if ("platform".equals(scope) && !engineCodes.isEmpty()) {
            throw new ServiceException("平台一键升级按发布清单波次推进，不需要选引擎"
                    + "（「引擎 code」应留空；要升引擎请在「新建计划」改选引擎范围）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Window length guard at creation (column is VARCHAR(120); over-long = human reject, no silent cut). */
    private static String validatedWindowLength(String window) {
        String trimmed = trimToNull(window);
        if (trimmed != null && trimmed.length() > 120) {
            throw new ServiceException("维护窗口申报太长（" + trimmed.length()
                    + " 字符，上限 120）——请把「维护窗口」改短成一句话讲清时段的写法，"
                    + "如「2026-10-14 02:00–02:30」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    private static int validatedBatchSize(UpgradePlanCreateInput input) {
        int batchSize = input.batchSize() == null ? 1 : input.batchSize();
        if (batchSize < 1 || batchSize > BATCH_SIZE_MAX) {
            throw new ServiceException("分批大小必须在 1-" + BATCH_SIZE_MAX
                    + " 之间（当前：" + input.batchSize() + "）——请把「分批大小」改成该区间内的整数",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return batchSize;
    }

    private String validatedManifestRef(UpgradePlanCreateInput input) {
        String manifestVersion = trimToNull(input.manifestVersion());
        if (manifestVersion != null) {
            Integer rows = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_release_manifest WHERE version = :v",
                    new MapSqlParameterSource("v", manifestVersion), Integer.class);
            if (rows == null || rows == 0) {
                throw new ServiceException("发布清单里没有版本「" + manifestVersion
                        + "」——升级权威缺失禁建计划，请到「升级中心」的「版本清单」页签登记该版本后重试",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        return manifestVersion;
    }

    /**
     * Artifact reachability pre-check for the wizard step-2 gate: pinned manifest
     * version → its artifact array → probe rows. No version pinned, no manifest, or
     * empty artifacts = empty list (the gate never fabricates verdicts — existing
     * green impact views stay green).
     */
    private List<ArtifactPrecheckItemView> artifactPrecheck(String manifestVersion) {
        if (manifestVersion == null) {
            return List.of();
        }
        ReleaseManifestInfo manifest = manifestService.findByVersion(manifestVersion);
        if (manifest == null || manifest.artifacts() == null || manifest.artifacts().isBlank()) {
            return List.of();
        }
        List<JsonNode> artifacts = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(manifest.artifacts());
            node.forEach(artifacts::add);
        } catch (Exception e) {
            // storage guarantees a jsonb array via CHECK; unparseable = honest empty
            // (a broken row must not 500 the whole impact view)
            log.warn("artifact json unparseable for manifest {}: {}", manifestVersion, e.toString());
            return List.of();
        }
        return artifactProber.precheck(artifacts);
    }

    /**
     * Preview computation (ledger × judgement spectrum) + human-worded rejection
     * on an empty set (create/impact share this single computation source).
     * {@code pinnedTargets} carries the manifest pin per family for platform
     * scope (empty map for engine/node).
     */
    private List<UpgradePlanStepView> requiredPreview(String scope, List<String> engineCodes,
                                                      Map<String, String> pinnedTargets) {
        List<UpgradePlanStepView> preview = previewSteps(scope, engineCodes, pinnedTargets);
        if (preview.isEmpty()) {
            throw new ServiceException("当前无可升级部件——台账里没有落在「可升级（黄）」态的目标节点×部件"
                    + "（已是最新或无金版本基准的行不冒充可升）；请到「升级中心」确认节点×部件"
                    + "处于黄徽章可升级态后再新建计划", BizErrorCode.VALIDATION_ERROR);
        }
        return preview;
    }

    
    private static List<String> validatedCanaries(UpgradePlanCreateInput input,
                                                  List<UpgradePlanStepView> preview) {
        Set<String> upgradableNodes = new LinkedHashSet<>();
        preview.forEach(s -> upgradableNodes.add(s.nodeId()));
        List<String> canary = dedupeTrim(input.canaryNodeIds());
        for (String node : canary) {
            if (!upgradableNodes.contains(node)) {
                throw new ServiceException("灰度节点「" + node + "」不在本次可升级节点集内"
                        + "（可选：" + String.join(" / ", upgradableNodes)
                        + "）——灰度机必须是本轮黄徽章行，请把「灰度节点」改成上述可选值之一",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        return canary;
    }

    
    private UpgradePlanInfo transition(long planId, String from, String to, String brakeReason) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", planId).addValue("from", from).addValue("to", to)
                .addValue("brakeReason", brakeReason);
        int updated = brakeReason == null
                ? jdbc.update("UPDATE tp_upgrade_plan SET status = :to, updated_at = now()"
                        + " WHERE id = :id AND status = :from", params)
                : jdbc.update("UPDATE tp_upgrade_plan SET status = :to, brake_reason = :brakeReason,"
                        + " updated_at = now() WHERE id = :id AND status = :from", params);
        if (updated == 0) {
            UpgradePlanInfo current = get(planId);
            throw new ServiceException("升级计划 #" + planId + " 当前状态「"
                    + statusLabel(current.status()) + "」不能" + actionLabel(to)
                    + "（" + statusLabel(from) + "态才能" + actionLabel(to)
                    + "，禁跳步）——请到「升级计划」页签刷新确认当前状态后再操作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return get(planId);
    }

    /** Preview computation: ledger rows × judgement spectrum -> YELLOW step set (engine=selected engine req families; node=executor-agent family; platform=pinned manifest families in wave order). */
    private List<UpgradePlanStepView> previewSteps(String scope, List<String> engineCodes,
                                                   Map<String, String> pinnedTargets) {
        List<ClusterComponentInfo> ledger = jdbc.query("""
                SELECT id, node_id, component_family, current_version, target_version,
                       requirement, support_state, state_reason, source, reported_at
                FROM tp_cluster_component ORDER BY node_id, component_family
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new ClusterComponentInfo(rs.getLong("id"), rs.getString("node_id"),
                        rs.getString("component_family"), rs.getString("current_version"),
                        rs.getString("target_version"), rs.getString("requirement"),
                        rs.getString("support_state"), rs.getString("state_reason"),
                        rs.getString("source"),
                        rs.getTimestamp("reported_at") == null ? null
                                : rs.getTimestamp("reported_at").toLocalDateTime(),
                        null)); // preview carrier rows feed step building only; homepage is a view-side field

        List<UpgradePlanStepView> steps = new ArrayList<>();
        if ("platform".equals(scope)) {
            // One-click wave plan: ledger YELLOW rows restricted to the pinned
            // wave families; target version = the manifest pin (the release
            // authority), never whatever the probe last wrote into the ledger.
            // The per-node × family uniqueness lives in the DB index — several
            // families on one host are distinct rows by design (V225).
            List<UpgradePlanStepView> pinnedSteps = new ArrayList<>();
            for (ClusterComponentInfo row : ledger) {
                String family = lower(row.componentFamily());
                if (!WavePlanGenerator.platformFamily(family) || !pinnedTargets.containsKey(family)) {
                    continue;
                }
                if (isYellow(row)) {
                    pinnedSteps.add(WavePlanGenerator.platformStep(withTarget(row,
                            pinnedTargets.get(family))));
                }
            }
            pinnedSteps.sort(java.util.Comparator.comparingInt(UpgradePlanStepView::waveSeq)
                    .thenComparing(UpgradePlanStepView::nodeId));
            return pinnedSteps;
        }
        if ("node".equals(scope)) {
            for (ClusterComponentInfo row : ledger) {
                if (!AGENT_FAMILY.equals(row.componentFamily())) {
                    continue;
                }
                if (isYellow(row)) {
                    steps.add(step(null, "node", row));
                }
            }
            return steps;
        }
        Map<String, Set<String>> familiesByEngine = familiesForEngines(engineCodes);
        for (String code : engineCodes) {
            Set<String> families = familiesByEngine.getOrDefault(code, Set.of());
            boolean firstFamilyPerNode = true;
            for (String family : families) {
                if (!firstFamilyPerNode) {
                    break;
                }
                firstFamilyPerNode = false;
                for (ClusterComponentInfo row : ledger) {
                    if (family.equals(row.componentFamily()) && isYellow(row)) {
                        steps.add(step(code, "engine", row));
                    }
                }
            }
        }
        return steps;
    }

    /** Ledger row re-pointed at the manifest pin (target authority for platform waves). */
    private static ClusterComponentInfo withTarget(ClusterComponentInfo row, String pinned) {
        return new ClusterComponentInfo(row.id(), row.nodeId(), row.componentFamily(),
                row.currentVersion(), pinned, row.requirement(), row.supportState(),
                row.stateReason(), row.source(), row.reportedAt(), row.homepageUrl());
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Platform-scope manifest gate + pin table (create/impact share this single
     * point). Non-platform scope = empty map (zero cost, zero behaviour change).
     * A platform plan without a pinned manifest version has no upgrade
     * authority: reject with a plain sentence instead of guessing targets.
     */
    private Map<String, String> platformPinnedTargets(String scope, String manifestVersion) {
        if (!"platform".equals(scope)) {
            return Map.of();
        }
        if (manifestVersion == null) {
            throw new ServiceException("平台一键升级必须指定发布清单版本（升级权威：波次计划"
                    + "的目标版本、工件齐备、协议区间都以清单钉定为准——没有权威就不知道要升到哪）；"
                    + "请在「新建计划」的「关联发布清单版本」选择要钉定的版本",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ReleaseManifestInfo manifest = manifestService.findByVersion(manifestVersion);
        // validatedManifestRef already guaranteed the row exists.
        try {
            JsonNode matrix = objectMapper.readTree(
                    manifest.componentMatrix() == null ? "[]" : manifest.componentMatrix());
            JsonNode artifacts = objectMapper.readTree(
                    manifest.artifacts() == null ? "[]" : manifest.artifacts());
            String violation = WavePlanGenerator.manifestViolation(matrix, artifacts);
            if (violation != null) {
                throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
            }
            return WavePlanGenerator.pinnedTargets(matrix);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("发布清单「" + manifestVersion
                    + "」的部件矩阵/工件清单解析失败（" + e.getMessage()
                    + "）——无法核对波次权威，请到「升级中心」的「版本清单」页签重新登记该版本",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private boolean isYellow(ClusterComponentInfo row) {
        ComponentVerdict verdict = ComponentSupportRules.judge(row.componentFamily(),
                row.currentVersion(), row.requirement(), row.targetVersion());
        return ComponentVerdict.YELLOW.equals(verdict.supportState());
    }

    
    private Map<String, Set<String>> familiesForEngines(List<String> engineCodes) {
        Map<String, Set<String>> out = new TreeMap<>();
        Set<String> wanted = new LinkedHashSet<>(engineCodes);
        for (var engine : engineService.listEngines(true)) {
            String code = engine.code().toUpperCase(Locale.ROOT);
            if (!wanted.contains(code)) {
                continue;
            }
            Set<String> families = ComponentSupportRules.familiesForRuntimeReq(engine.runtimeVersionReq());
            out.put(code, families);
        }
        return out;
    }

    private static UpgradePlanStepView step(String engineCode, String kind, ClusterComponentInfo row) {
        return new UpgradePlanStepView(null, null, row.nodeId(), kind, engineCode, "batch",
                row.currentVersion(), row.targetVersion(), "PENDING", null, "{}", null, null);
    }

    
    private String buildStrategy(List<String> canaryNodeIds, int batchSize, String baselineRef) {
        Map<String, Object> strategy = new java.util.LinkedHashMap<>();
        strategy.put("canaryNodeIds", canaryNodeIds);
        strategy.put("batchSize", batchSize);
        strategy.put("cool", cooldownSeconds());
        strategy.put("baselineRef", trimToNull(baselineRef));
        return writeJson(strategy);
    }

    int cooldownSeconds() {
        return intConfig(COOL_KEY, DEFAULT_COOL_SECONDS);
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

    private List<String> normalizeEngineCodes(List<String> codes) {
        List<String> out = new ArrayList<>();
        if (codes == null) {
            return out;
        }
        for (String code : codes) {
            String trimmed = trimToNull(code);
            if (trimmed == null) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            Integer known = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_appauto_engine WHERE code = :code",
                    new MapSqlParameterSource("code", upper), Integer.class);
            if (known == null || known == 0) {
                throw new ServiceException("引擎「" + trimmed + "」不在引擎描述符目录"
                        + "（tp_appauto_engine）——请先到「APP 自动化 → 引擎中心」确认该 code 已上架，"
                        + "在架则照抄「引擎 code」原文再建计划",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!out.contains(upper)) {
                out.add(upper);
            }
        }
        return out;
    }

    private static List<String> dedupeTrim(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            String trimmed = trimToNull(v);
            if (trimmed != null && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("升级计划策略序列化失败：" + e.getMessage()
                    + "——请重试保存；持续失败请到「升级中心」的「升级计划」页签新建计划并简化策略字段",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case PREVIEW -> "预览";
            case RUNNING -> "进行中";
            case PAUSED -> "已暂停";
            case COMPLETED -> "已完成";
            case ABORTED -> "已终止";
            case BRAKED -> "已刹车";
            default -> status;
        };
    }

    private static String actionLabel(String to) {
        return switch (to) {
            case RUNNING -> "启动/恢复";
            case PAUSED -> "暂停";
            case COMPLETED -> "完成";
            case BRAKED -> "刹车";
            default -> "变更";
        };
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
