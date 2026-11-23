package com.sixbean.testpike.core.linkage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.linkage.api.dto.LinkageUrgeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Downstream-impact urge scan tests .
 * <p>Covers the two-stage escalation of unhandled {@code tp_linkage_impact}
 * rows: past {@code linkage.urge-hours} the executor (the upstream object's
 * creator, the person who saved upstream and owns the grouped card) gets a
 * daily {@code LINKAGE_URGE}; past {@code linkage.escalate-hours} (7 days by
 * factory default) the project owners get a forced {@code LINKAGE_ESCALATE},
 * mirroring the Q4 review-reminder positions without a second notification
 * pipeline. Idempotency = one notification per impact row per calendar day,
 * enforced through the tp_notification dedup key, same as Q4.
 * <p>Thresholds are re-read from tp_sys_config every round (hot update) and
 * a zero value disables exactly that stage. A consumed impact row
 * (status 4 / consumed_at set) must never be notified again.
 * <p>Real dev-database fixtures only (strict testing: no mocks). Time
 * simulation rewinds {@code annotated_at} directly, same technique as the
 * Q4 tests and L4 smoke. Run-owned synthetic high ids only — factory rows
 * are never truncated (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class LinkageUrgeServiceTest {

    private static final String KEY_URGE = "linkage.urge-hours";
    private static final String KEY_ESCALATE = "linkage.escalate-hours";

    @Autowired
    private LinkageUrgeService urgeService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Named-parameter statements go through this wrapper (same posture as LinkageImpactQueryTest). */
    private NamedParameterJdbcTemplate named;

    private final List<String> ownedEdgeKeys = new ArrayList<>();
    private final List<Long> ownedRequirementIds = new ArrayList<>();
    private final List<Long> ownedProjectIds = new ArrayList<>();
    private final List<Long> ownedUserIds = new ArrayList<>();
    /** config_key -&gt; value before this test touched it; restored after each run. */
    private final java.util.Map<String, String> originalThresholds = new java.util.HashMap<>();

    @BeforeEach
    void captureThresholds() {
        named = new NamedParameterJdbcTemplate(jdbc);
        for (String key : List.of(KEY_URGE, KEY_ESCALATE)) {
            originalThresholds.put(key, jdbc.queryForObject(
                    "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                    String.class, key));
        }
    }

    @AfterEach
    void removeOwnedFixtures() {
        for (java.util.Map.Entry<String, String> back : originalThresholds.entrySet()) {
            setThreshold(back.getKey(), back.getValue());
        }
        originalThresholds.clear();
        jdbc.update("DELETE FROM tp_notification WHERE dedup_key LIKE 'linkage-urge:%' "
                + "OR dedup_key LIKE 'linkage-escalate:%'");
        if (!ownedEdgeKeys.isEmpty()) {
            named.update("DELETE FROM tp_linkage_impact WHERE edge_key IN (:keys)",
                    new MapSqlParameterSource("keys", ownedEdgeKeys));
        }
        if (!ownedRequirementIds.isEmpty()) {
            named.update("DELETE FROM tp_requirement WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ownedRequirementIds));
        }
        if (!ownedProjectIds.isEmpty()) {
            named.update("DELETE FROM tp_user_project WHERE project_id IN (:ids)",
                    new MapSqlParameterSource("ids", ownedProjectIds));
            named.update("DELETE FROM tp_project WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ownedProjectIds));
        }
        if (!ownedUserIds.isEmpty()) {
            named.update("DELETE FROM tp_user WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", ownedUserIds));
        }
        ownedEdgeKeys.clear();
        ownedRequirementIds.clear();
        ownedProjectIds.clear();
        ownedUserIds.clear();
    }

    /** A run-owned project whose creator is the seeded admin (id 1). */
    private long seedProject() {
        long id = 985_000_000L + System.nanoTime() % 100_000L;
        ownedProjectIds.add(id);
        named.update("INSERT INTO tp_project (id, name, code, created_by) OVERRIDING SYSTEM VALUE "
                        + "VALUES (:id, :name, :code, 1)",
                new MapSqlParameterSource("id", id)
                        .addValue("name", "联动催办夹具项目" + id)
                        .addValue("code", "LNKU" + id));
        return id;
    }

    /** A fresh test user (executor stand-in); cleaned with the run. */
    private long seedUser(String tag) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, NULL, ?, 1, 1) RETURNING id",
                Long.class, "lnku_" + tag + "_" + System.nanoTime() % 1_000_000L, "联动催办夹具用户");
        assertNotNull(id);
        ownedUserIds.add(id);
        return id;
    }

    /** Upstream requirement owned by the seeded executor (creator column). */
    private long seedRequirement(long projectId, long executorId) {
        long id = 987_000_000L + System.nanoTime() % 100_000L;
        ownedRequirementIds.add(id);
        named.update("INSERT INTO tp_requirement (id, project_id, source, title, created_by) "
                        + "OVERRIDING SYSTEM VALUE VALUES (:id, :pid, 3, '联动催办夹具需求', :by)",
                new MapSqlParameterSource("id", id)
                        .addValue("pid", projectId)
                        .addValue("by", executorId));
        return id;
    }

    /** Pending impact row aged {@code hoursAgo} (annotated_at rewound). */
    private long seedPendingImpact(String edgeSuffix, long upstreamId, int hoursAgo) {
        String edgeKey = "lnku:" + edgeSuffix;
        ownedEdgeKeys.add(edgeKey);
        Long id = named.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type, upstream_object_id,"
                        + " upstream_version, downstream_object_type, downstream_id,"
                        + " disposition_strategy, status, annotated_at)"
                        + " VALUES (:edge, 'requirement', :up, 1, 'case', 988000001,"
                        + " 'grouped_draft', 1,"
                        + " now() - make_interval(hours => :h::int)) RETURNING id",
                new MapSqlParameterSource("edge", edgeKey)
                        .addValue("up", upstreamId)
                        .addValue("h", hoursAgo),
                Long.class);
        assertNotNull(id);
        return id;
    }

    /** Same row shape but already consumed (accept/replay stamped status 4). */
    private long seedConsumedImpact(String edgeSuffix, long upstreamId, int hoursAgo) {
        String edgeKey = "lnku:" + edgeSuffix;
        ownedEdgeKeys.add(edgeKey);
        Long id = named.queryForObject(
                "INSERT INTO tp_linkage_impact (edge_key, upstream_object_type, upstream_object_id,"
                        + " upstream_version, downstream_object_type, downstream_id,"
                        + " disposition_strategy, status, annotated_at, consumed_at)"
                        + " VALUES (:edge, 'requirement', :up, 1, 'case', 988000002,"
                        + " 'grouped_draft', 4,"
                        + " now() - make_interval(hours => :h::int), now()) RETURNING id",
                new MapSqlParameterSource("edge", edgeKey)
                        .addValue("up", upstreamId)
                        .addValue("h", hoursAgo),
                Long.class);
        assertNotNull(id);
        return id;
    }

    /** Seed rows carry the two threshold keys; a missing key is a defect. */
    private void setThreshold(String key, String value) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, key);
        assertNotNull(id, "出厂种子键缺失: " + key);
        String group = jdbc.queryForObject(
                "SELECT group_name FROM tp_sys_config WHERE config_key = ?", String.class, key);
        String desc = jdbc.queryForObject(
                "SELECT description FROM tp_sys_config WHERE config_key = ?", String.class, key);
        configService.update(1L, id,
                new com.sixbean.testpike.core.config.api.dto.ConfigInput(key, value, group, "int", 0, desc));
    }

    private int countByDedupPrefix(String prefix, long impactId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE ?",
                Integer.class, prefix + ":" + impactId + ":%");
        return n == null ? 0 : n;
    }

    @Test
    void urgeHoursOldPendingImpactNotifiesTheExecutorOncePerDay() {
        long project = seedProject();
        long executor = seedUser("urge1");
        long requirement = seedRequirement(project, executor);
        long impact = seedPendingImpact("urge-basic-" + project, requirement, 25);

        LinkageUrgeResult first = urgeService.scan();
        assertTrue(first.urged() >= 1, "24h 以上的待处置项应产生催办: " + first);
        assertEquals(1, countByDedupPrefix("linkage-urge", impact),
                "同一自然日同一影响项只允许一条催办");

        LinkageUrgeResult second = urgeService.scan();
        assertEquals(1, countByDedupPrefix("linkage-urge", impact),
                "同日重扫必须命中 dedup_key 幂等，不得重复发送");
    }

    @Test
    void sevenDayOldPendingImpactAlsoEscalatesToProjectOwners() {
        long project = seedProject();
        long executor = seedUser("esc1");
        long requirement = seedRequirement(project, executor);
        long impact = seedPendingImpact("escalate-" + project, requirement, 24 * 8);

        LinkageUrgeResult result = urgeService.scan();
        assertTrue(result.escalated() >= 1, "7 日以上的待处置项应升级项目负责人: " + result);
        assertEquals(1, countByDedupPrefix("linkage-escalate", impact),
                "升级同样按自然日幂等，一条封顶");
        assertTrue(countByDedupPrefix("linkage-urge", impact) >= 1,
                "升级不豁免每日催办，两档并行（Q4 岗位形态）");
    }

    /**
     * Fresh and already-consumed rows stay silent. The assertions are pinned to
     * this fixture's own impact ids: other pending rows in the ledger are the
     * scanner's legitimate business once they age out, so a whole-table count
     * would turn a working feature into a time-bomb for this test.
     */
    @Test
    void freshAndConsumedImpactsReceiveNoNotification() {
        long project = seedProject();
        long executor = seedUser("quiet1");
        long requirement = seedRequirement(project, executor);
        long fresh = seedPendingImpact("fresh-" + project, requirement, 1);
        long consumed = seedConsumedImpact("consumed-" + project, requirement, 200);

        urgeService.scan();
        assertEquals(0, countByDedupPrefix("linkage-urge", fresh), "新鲜未超阈值不得催办");
        assertEquals(0, countByDedupPrefix("linkage-escalate", fresh), "新鲜未超阈值不得升级");
        assertEquals(0, countByDedupPrefix("linkage-urge", consumed), "已处置行不得再催办");
        assertEquals(0, countByDedupPrefix("linkage-escalate", consumed),
                "已处置（status 4 / consumed_at 非空）行永不再打扰");
    }

    @Test
    void zeroThresholdsDisableExactlyTheirOwnStage() {
        long project = seedProject();
        long executor = seedUser("disable1");
        long requirement = seedRequirement(project, executor);
        seedPendingImpact("disable-old-" + project, requirement, 24 * 8);
        seedPendingImpact("disable-mid-" + project, requirement, 25);

        setThreshold(KEY_URGE, "0");
        urgeService.scan();
        Integer urges = jdbc.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE 'linkage-urge:%'",
                Integer.class);
        assertEquals(0, urges, "urge 级关闭时不得发送催办");
        Integer escalations = jdbc.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE 'linkage-escalate:%'",
                Integer.class);
        assertTrue(escalations != null && escalations >= 1, "关闭 urge 级不得连带关闭升级级");

        setThreshold(KEY_ESCALATE, "0");
        setThreshold(KEY_URGE, "24");
        jdbc.update("DELETE FROM tp_notification WHERE dedup_key LIKE 'linkage-%'");
        urgeService.scan();
        Integer urgesAfter = jdbc.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE 'linkage-urge:%'",
                Integer.class);
        assertTrue(urgesAfter != null && urgesAfter >= 1, "urge 级恢复后热更立即生效");
        Integer escalationsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE 'linkage-escalate:%'",
                Integer.class);
        assertEquals(0, escalationsAfter, "escalate 级关闭时不得发送升级");
    }

    @Test
    void factoryDefaultsAreTwentyFourHoursAndSevenDays() {
        String urge = jdbc.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, KEY_URGE);
        String escalate = jdbc.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, KEY_ESCALATE);
        assertEquals("24", urge, "出厂催办阈值=24 小时");
        assertEquals("168", escalate, "出厂升级阈值=7 日（168 小时）");
    }
}
