package com.sixbean.testpike.core.mock.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.mock.api.dto.MockRuleSnapshotView;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.component.MockRuleValidator;
import com.sixbean.testpike.core.mock.domain.component.MockSpaceReferenceGate;
import com.sixbean.testpike.core.mock.domain.service.MockHitQueryService;
import com.sixbean.testpike.core.mock.domain.service.MockScenarioService;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.infrastructure.dataobject.MockSpaceDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Mock space service implementation.
 * <p>Save-chain gates (validation single point reused from MockRuleValidator,
 * no second copy): structural shape first, then the production-environment ban
 * — env_type=4 is refused here because a table CHECK cannot join tp_project_env
 * (DB-comment posture on tp_mock_space.env_id says the same thing). An env that
 * belongs to another project is refused with the same plain-word 403 as a
 * missing one, so existence of other projects' environments never leaks.
 * <p>The snapshot version is a content pin (digest over the space row plus the
 * ordered enabled-rule rows), so the mock-server loader re-parses only on real
 * drift and can hold its last good pin while reporting staleness (fail-closed).
 */
@Service
public class MockSpaceServiceImpl implements MockSpaceService {

    private static final BeanPropertyRowMapper<MockSpaceDO> SPACE_MAPPER =
            new BeanPropertyRowMapper<>(MockSpaceDO.class);

    /** Lifecycle states (tp_mock_space.status CHECK: 0 draft / 1 published / 2 retired). */
    private static final int STATUS_DRAFT = 0;
    private static final int STATUS_PUBLISHED = 1;
    private static final int STATUS_RETIRED = 2;

    /**
     * The legal transition edge set, exactly {draft→publish, publish→retire}.
     * Unpublish (1→0) is refused: the space already served traffic, going back
     * to draft would silently re-open it later. Retired (2) is terminal —
     * reviving would resurrect an endpoint operators deliberately put out of
     * service (D-d: copy the space instead). Single source of the state machine
     * so the internal endpoint and the admin bridge can never disagree.
     */
    private static final java.util.Set<String> LEGAL_EDGES =
            java.util.Set.of(edgeKey(STATUS_DRAFT, STATUS_PUBLISHED),
                    edgeKey(STATUS_PUBLISHED, STATUS_RETIRED));

    private static String edgeKey(int from, int to) {
        return from + "->" + to;
    }

    /** Plain-word refusal naming the edge so operators learn the rule from the error. */
    private static String describeRefusal(int from, int target) {
        if (from == STATUS_PUBLISHED && target == STATUS_DRAFT) {
            return "已发布的空间不能撤销发布——它可能已对外提供仿真流量，如需停服请走退役";
        }
        if (from == STATUS_RETIRED) {
            return "已退役的空间不可复活（保留历史供追溯）——需要重新对外服务请复制该空间";
        }
        if (from == STATUS_DRAFT && target == STATUS_RETIRED) {
            return "草稿空间从未发布，无需退役；确认不再使用请直接删除";
        }
        return "不支持的空间状态转换：" + from + " → " + target
                + "（合法路径仅 草稿→发布、发布→退役）";
    }

    private static final String SPACE_COLUMNS = """
            id, project_id, name, space_key, alias, env_id, base_path_mode,
            status, auth_mode, proxy_target_url, rate_limit_rps, quota_daily, priority,
            chaos_percent, miss_policy, expires_at, version,
            created_at, updated_at
            """;

    private static final String STUB_SNAPSHOT_COLUMNS = """
            id, space_id, name, priority, method, path_match_type, path_pattern,
            matchers_json::text AS matchersJson, response_json::text AS responseJson,
            scenario_name, required_state, new_state, source, source_ref, enabled,
            version, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MockRuleValidator validator;
    private final MockScenarioService scenarioService;
    private final MockSpaceReferenceGate referenceGate;
    private final MockHitQueryService hitQueryService;

    public MockSpaceServiceImpl(NamedParameterJdbcTemplate jdbc, MockRuleValidator validator,
                                MockScenarioService scenarioService,
                                MockSpaceReferenceGate referenceGate,
                                MockHitQueryService hitQueryService) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.scenarioService = scenarioService;
        this.referenceGate = referenceGate;
        this.hitQueryService = hitQueryService;
    }

    @Override
    @Transactional
    public long create(long projectId, MockSpaceInput input) {
        return createWithTtl(projectId, input, null);
    }

    @Override
    @Transactional
    public long createWithTtl(long projectId, MockSpaceInput input, Integer ttlHours) {
        // TTL range gate: 0 or negative would expire the space at/before birth,
        // and a huge one is a CI leftover squatting under a fake short-lived
        // label. One year is the honest ceiling for "temporary".
        if (ttlHours != null && (ttlHours < 1 || ttlHours > 8760)) {
            throw new ServiceException("TTL 小时数必须在 1-8760（一年）之间，当前传入 "
                    + ttlHours + "，请在创建参数 ttlHours 改传该区间内的整数，示例：24（一天后自动到期）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        validator.validateSpace(input);
        requireProject(projectId);
        requireEnvAllowed(projectId, input.envId());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("name", input.name().trim())
                .addValue("spaceKey", input.spaceKey().trim())
                .addValue("alias", trimToNull(input.alias()))
                .addValue("envId", input.envId())
                .addValue("basePathMode", input.basePathMode() == null ? "prefix" : input.basePathMode())
                .addValue("authMode", input.authMode() == null ? "none" : input.authMode())
                .addValue("rateLimitRps", input.rateLimitRps())
                .addValue("quotaDaily", input.quotaDaily())
                .addValue("priority", input.priority() == null ? 100 : input.priority())
                // An omitted dial means "no injection", never a guessed amount.
                .addValue("chaosPercent", input.chaosPercent() == null ? 0 : input.chaosPercent())
                .addValue("missPolicy", com.sixbean.testpike.common.mock.MockMissPolicies
                        .stored(input.missPolicy()))
                // NULL expiry = never expires, written explicitly (never a
                // silent swallow of the TTL parameter at this save-chain door).
                .addValue("expiresAt", ttlHours == null ? null
                        : LocalDateTime.now().plusHours(ttlHours), Types.TIMESTAMP);
        try {
            return jdbc.queryForObject("""
                    INSERT INTO tp_mock_space
                        (project_id, name, space_key, alias, env_id, base_path_mode,
                         status, auth_mode, rate_limit_rps, quota_daily, priority, chaos_percent,
                         miss_policy, expires_at)
                    VALUES
                        (:projectId, :name, :spaceKey, :alias, :envId, :basePathMode,
                         0, :authMode, :rateLimitRps, :quotaDaily, :priority, :chaosPercent,
                         :missPolicy, :expiresAt)
                    RETURNING id
                    """, params, Long.class);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("路由标识「" + input.spaceKey().trim()
                    + "」已被占用——它是对外访问路径的一部分，需全站唯一；"
                    + "请换一个未被占用的标识，或点「刷新」在「仿真空间」列表确认现有空间，"
                    + "命名格式：小写字母/数字开头，可用字母、数字、下划线、连字符",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    public MockSpaceInfo get(long id) {
        MockSpaceDO row = findSpace(id);
        return toInfo(row);
    }

    @Override
    public List<MockSpaceInfo> listByProject(long projectId) {
        List<MockSpaceDO> rows = jdbc.query("SELECT " + SPACE_COLUMNS
                        + " FROM tp_mock_space WHERE project_id = :pid ORDER BY priority, id",
                new MapSqlParameterSource("pid", projectId), SPACE_MAPPER);
        if (rows.isEmpty()) {
            return List.of();
        }
        // Configuration inventory per space in one grouped pass (enabled and
        // disabled both counted — this is what the editor holds, not what the
        // traffic plane serves). Spaces missing from the map simply have none.
        Map<Long, Integer> ruleCounts = new HashMap<>();
        jdbc.query("SELECT space_id, COUNT(*) AS cnt FROM tp_mock_stub WHERE space_id IN"
                        + " (SELECT id FROM tp_mock_space WHERE project_id = :pid)"
                        + " GROUP BY space_id",
                new MapSqlParameterSource("pid", projectId),
                rs -> {
                    ruleCounts.put(rs.getLong("space_id"), rs.getInt("cnt"));
                });
        return rows.stream().map(row -> {
            // Hit/miss spectrum comes from the ledger query service itself —
            // one definition, no second copy here (red line 6). No rows yet =
            // no sample, rendered as null, never as a friendly 0% miss.
            long total = hitQueryService.countBySpace(row.getId(), null, null);
            long hits = total == 0 ? 0 : hitQueryService.countBySpace(row.getId(), null, Boolean.TRUE);
            Double hitRate = total == 0 ? null : (double) hits / total;
            return withThumbnails(toInfo(row),
                    ruleCounts.getOrDefault(row.getId(), 0), hitRate);
        }).toList();
    }

    @Override
    public MockRuleSnapshotView snapshot(String spaceKey) {
        String key = spaceKey == null ? null : spaceKey.trim();
        if (key == null || key.isBlank()) {
            throw new ServiceException("缺少空间路由标识（spaceKey）——快照请求必须指名空间；"
                    + "可在「仿真空间」列表的「路由标识」列查看取值后重试", BizErrorCode.VALIDATION_ERROR);
        }
        List<MockSpaceDO> spaces = jdbc.query("SELECT " + SPACE_COLUMNS
                + " FROM tp_mock_space WHERE space_key = :key",
                new MapSqlParameterSource("key", key), SPACE_MAPPER);
        if (spaces.isEmpty()) {
            throw new ServiceException("Mock 空间「" + key + "」不存在，"
                    + "请点「刷新」回到「仿真空间」列表核对「路由标识」列拼写后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        MockSpaceDO space = spaces.get(0);
        // Three-state serving gate: only a published space hands rules to the
        // traffic plane. Draft/retired snapshots keep the space header and pin
        // (the status is already inside pinOf, so a transition moves the version
        // and the loader swaps to the empty index — retiring stops serving
        // instantly), but never leak stubs or scenario machines.
        boolean published = space.getStatus() != null && space.getStatus() == 1;
        List<MockStubInfo> stubs = published
                ? jdbc.query("SELECT " + STUB_SNAPSHOT_COLUMNS
                        + " FROM tp_mock_stub WHERE space_id = :sid AND enabled = TRUE"
                        + " ORDER BY priority, id",
                new MapSqlParameterSource("sid", space.getId()),
                new BeanPropertyRowMapper<>(com.sixbean.testpike.core.mock.infrastructure.dataobject.MockStubDO.class))
                        .stream().map(this::toStubInfo).toList()
                : List.of();
        List<MockScenarioInfo> scenarios = published
                ? scenarioService.listBySpace(space.getId())
                : List.of();
        // One open cassette box per space is enforced by the recording service,
        // so this lookup is a single-valued read: null (idle) or the open box id.
        // It belongs INSIDE the pin — sealing changes what the forwarder does,
        // so the loader must see that drift .
        Long activeRecordingId = published
                ? jdbc.query("""
                        SELECT id FROM tp_mock_recording
                         WHERE space_id = :sid AND status = 'recording'
                         ORDER BY id DESC LIMIT 1
                        """, new MapSqlParameterSource("sid", space.getId()),
                        (rs, rowNum) -> rs.getLong(1))
                        .stream().findFirst().orElse(null)
                : null;
        return new MockRuleSnapshotView(pinOf(space, stubs, scenarios, activeRecordingId),
                toInfo(space), stubs, scenarios, activeRecordingId, LocalDateTime.now());
    }

    @Override
    @Audit(objectType = "mock_space", action = "mock_space_transition",
            objectId = "#spaceId", before = "{expectedVersion: #expectedVersion}", after = "#result")
    @Transactional
    public MockSpaceInfo transition(long spaceId, int targetStatus, int expectedVersion) {
        MockSpaceDO row = findSpace(spaceId);
        int from = row.getStatus() == null ? 0 : row.getStatus();
        if (!LEGAL_EDGES.contains(edgeKey(from, targetStatus))) {
            throw new ServiceException(describeRefusal(from, targetStatus), BizErrorCode.VALIDATION_ERROR);
        }
        if (targetStatus == STATUS_PUBLISHED) {
            // Re-run the production-env gate at publish time: an env that turned
            // production after the draft landed must never go live (fail-closed).
            requireEnvAllowed(row.getProjectId(), row.getEnvId());
        }
        if (targetStatus == STATUS_RETIRED) {
            // Reference gate , one single source shared with the
            // expiry sweep): retiring a space whose cassette box is still open
            // would silently drop a running recording — 409 with plain words.
            List<String> blockers = referenceGate.blockingReferences(spaceId);
            if (!blockers.isEmpty()) {
                throw new ServiceException("空间暂不能退役：" + String.join("；", blockers)
                        + "；处理完成后请回到「仿真空间」列表重试「退役空间」",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
        }
        int moved = jdbc.update("""
                UPDATE tp_mock_space
                   SET status = :target, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """,
                new MapSqlParameterSource("id", spaceId)
                        .addValue("target", targetStatus)
                        .addValue("expected", expectedVersion));
        if (moved == 0) {
            throw new ServiceException("空间已被其他人修改（期望版本 " + expectedVersion
                    + " 已过期），请点「刷新」获取最新版本后重试", BizErrorCode.VERSION_CONFLICT);
        }
        return toInfo(findSpace(spaceId));
    }

    @Override
    @Transactional
    public MockSpaceInfo updateMissPolicy(long spaceId, String missPolicy, int expectedVersion) {
        findSpace(spaceId);
        // Single spectrum with the save chain — the policy door never invents a
        // second validation list (red line 6).
        validator.validateMissPolicy(missPolicy);
        String stored = com.sixbean.testpike.common.mock.MockMissPolicies.stored(missPolicy);
        int moved = jdbc.update("""
                UPDATE tp_mock_space
                   SET miss_policy = :policy, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """,
                new MapSqlParameterSource("id", spaceId)
                        .addValue("policy", stored)
                        .addValue("expected", expectedVersion));
        if (moved == 0) {
            throw new ServiceException("空间已被其他人修改（期望版本 " + expectedVersion
                    + " 已过期），请点「刷新」获取最新版本后重试", BizErrorCode.VERSION_CONFLICT);
        }
        return toInfo(findSpace(spaceId));
    }

    @Override
    public List<MockSpaceInfo> listExpiredForSweep() {
        List<MockSpaceDO> rows = jdbc.query("SELECT " + SPACE_COLUMNS
                + " FROM tp_mock_space"
                + " WHERE expires_at <= now() AND status IN (0, 1)"
                + " ORDER BY id",
                new MapSqlParameterSource(), SPACE_MAPPER);
        return rows.stream().map(this::toInfo).toList();
    }

    @Override
    @Audit(objectType = "mock_space", action = "mock_space_delete", objectId = "#spaceId")
    @Transactional
    public void deleteDraft(long spaceId) {
        MockSpaceDO row = findSpace(spaceId);
        if (row.getStatus() == null || row.getStatus() != STATUS_DRAFT) {
            throw new ServiceException("仅草稿空间可以删除；已对外服务的空间请走「退役空间」保留追溯，"
                    + "入口在「仿真空间」列表行内操作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Same gate as the manual retire edge — a draft holding an open
        // recording box is not deletable either (fail-closed, one source).
        List<String> blockers = referenceGate.blockingReferences(spaceId);
        if (!blockers.isEmpty()) {
            throw new ServiceException("空间暂不能删除：" + String.join("；", blockers)
                    + "；处理完成后请回到「仿真空间」列表重试删除",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        // Child tables carry FK NO ACTION to the space (live schema scan):
        // delete in child-first order so the parent DELETE can never 500.
        // tp_mock_hit_log intentionally survives — the ledger is traffic
        // accounting history, addressed by space_id without an FK.
        for (String child : List.of("tp_mock_stub", "tp_mock_scenario",
                "tp_mock_recording", "tp_mock_ai_job")) {
            jdbc.update("DELETE FROM " + child + " WHERE space_id = :sid",
                    new MapSqlParameterSource("sid", spaceId));
        }
        jdbc.update("DELETE FROM tp_mock_space WHERE id = :id",
                new MapSqlParameterSource("id", spaceId));
    }

    // ---- internals -------------------------------------------------------

    private MockSpaceDO findSpace(long id) {
        List<MockSpaceDO> rows = jdbc.query("SELECT " + SPACE_COLUMNS
                + " FROM tp_mock_space WHERE id = :id",
                new MapSqlParameterSource("id", id), SPACE_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("Mock 空间不存在或已被删除，"
                    + "请点「刷新」回到「仿真空间」列表重新选择", BizErrorCode.VALIDATION_ERROR);
        }
        return rows.get(0);
    }

    private void requireProject(long projectId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tp_project WHERE id = :pid",
                new MapSqlParameterSource("pid", projectId), Integer.class);
        if (count == null || count == 0) {
            throw new ServiceException("项目不存在，无法创建 Mock 空间——项目可能已被删除；"
                    + "请先到「项目管理」确认项目在列，或在顶栏切换到有效项目后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Environment gate: NULL = unbound (allowed at the shell); otherwise the env
     * must belong to this project (missing/foreign answer identically so no
     * existence leaks) and must not be the production environment (env_type=4).
     */
    private void requireEnvAllowed(long projectId, Long envId) {
        if (envId == null) {
            return;
        }
        List<Integer> envTypes = jdbc.queryForList(
                "SELECT env_type FROM tp_project_env WHERE id = :eid AND project_id = :pid",
                new MapSqlParameterSource("eid", envId).addValue("pid", projectId), Integer.class);
        if (envTypes.isEmpty()) {
            throw new ServiceException("所选环境不存在或不属于该项目，"
                    + "请到「项目管理」该项目的「环境配置」页重新选择，或「新增环境」后再绑定",
                    BizErrorCode.FORBIDDEN);
        }
        if (envTypes.get(0) == 4) {
            throw new ServiceException("禁止将 Mock 空间绑定到生产环境——仿真流量不得充当生产链路的替身，"
                    + "请改绑测试/预发环境（环境类型可在「项目管理」该项目的「环境配置」页核对）",
                    BizErrorCode.FORBIDDEN);
        }
    }

    private MockSpaceInfo toInfo(MockSpaceDO row) {
        return new MockSpaceInfo(row.getId(), row.getProjectId(), row.getName(), row.getSpaceKey(),
                row.getAlias(), row.getEnvId(), row.getBasePathMode(), row.getStatus(), row.getAuthMode(),
                row.getProxyTargetUrl(),
                row.getRateLimitRps(), row.getQuotaDaily(), row.getPriority(), row.getChaosPercent(),
                row.getMissPolicy(),
                row.getExpiresAt(),
                row.getVersion(), row.getCreatedAt(), row.getUpdatedAt(), null, null);
    }

    /** Re-arms a base info with its list-only thumbnail columns (records are immutable). */
    private MockSpaceInfo withThumbnails(MockSpaceInfo base, Integer ruleCount, Double hitRate) {
        return new MockSpaceInfo(base.id(), base.projectId(), base.name(), base.spaceKey(),
                base.alias(), base.envId(), base.basePathMode(), base.status(), base.authMode(),
                base.proxyTargetUrl(), base.rateLimitRps(), base.quotaDaily(), base.priority(),
                base.chaosPercent(), base.missPolicy(), base.expiresAt(), base.version(),
                base.createdAt(), base.updatedAt(), ruleCount, hitRate);
    }

    private MockStubInfo toStubInfo(com.sixbean.testpike.core.mock.infrastructure.dataobject.MockStubDO row) {
        return new MockStubInfo(row.getId(), row.getSpaceId(), row.getName(), row.getPriority(),
                row.getMethod(), row.getPathMatchType(), row.getPathPattern(), row.getMatchersJson(),
                row.getResponseJson(), row.getScenarioName(), row.getRequiredState(), row.getNewState(),
                row.getSource(), row.getSourceRef(), row.getEnabled(), row.getVersion(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    /** Content pin = digest over the ordered rule payload plus the space row's
     * own updated_at — equality compare only, never ordering semantics. */
    private String pinOf(MockSpaceDO space, List<MockStubInfo> stubs,
                         List<MockScenarioInfo> scenarios, Long activeRecordingId) {
        StringBuilder sb = new StringBuilder();
        sb.append(space.getId()).append('|').append(space.getUpdatedAt()).append('|')
                .append(space.getBasePathMode()).append('|').append(space.getStatus()).append('|')
                .append(space.getAuthMode()).append('|').append(space.getRateLimitRps()).append('|')
                .append(space.getQuotaDaily()).append('|').append(space.getProxyTargetUrl())
                // The chaos dial is served content: turning the fault knob up
                // or down changes what traffic experiences, so the pin must
                // move with it (same posture as the proxy target).
                .append('|').append(space.getChaosPercent())
                // The miss policy is served content too: choosing another rung
                // changes what an unmatched request experiences, so the pin
                // moves with it .
                .append('|').append(space.getMissPolicy())
                // Recording drift is served content: opening or sealing a box
                // changes forwarder behaviour, so the pin must move with it.
                .append('|').append(activeRecordingId)
                .append('\n');
        for (MockStubInfo s : stubs) {
            sb.append(s.id()).append('|').append(s.priority()).append('|').append(s.method())
                    .append('|').append(s.pathMatchType()).append('|').append(s.pathPattern())
                    .append('|').append(s.matchersJson()).append('|').append(s.responseJson())
                    .append('|').append(s.scenarioName()).append('|').append(s.requiredState())
                    .append('|').append(s.newState()).append('|').append(s.updatedAt()).append('\n');
        }
        // Machines are part of the served content: filing or editing a scenario
        // must move the pin so the loader swaps its parsed set on real drift.
        for (com.sixbean.testpike.core.mock.api.dto.MockScenarioInfo sc : scenarios) {
            sb.append("SCN|").append(sc.id()).append('|').append(sc.name())
                    .append('|').append(sc.definitionJson()).append('|').append(sc.updatedAt())
                    .append('\n');
        }
        return sha256(sb.toString());
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new ServiceException("快照版本计算失败（摘要算法不可用）——运行 JVM 缺少 SHA-256，"
                    + "请更换标准 JDK 后重启 core 服务再试，"
                    + "示例：标准 OpenJDK 21（java -version 可查），精简/定制 JVM 需自带 SHA-256 实现",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
