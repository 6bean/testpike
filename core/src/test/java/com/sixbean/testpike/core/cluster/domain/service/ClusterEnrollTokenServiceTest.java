package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.EnrollAcceptedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ClusterEnrollTokenServiceTest {

    @Autowired
    private ClusterEnrollTokenService enrollTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @BeforeEach
    @AfterEach
    void cleanTokens() {
        jdbcTemplate.execute("DELETE FROM tp_cluster_enroll_token");
    }

    @AfterEach
    void resetInstallKitKeys() {
        configCacheHolder.remove("installkit.token-ttl-seconds");
        configCacheHolder.remove("installkit.allow-roles");
        configCacheHolder.remove("installkit.mirror-base-url");
    }


    @Test
    void issue_plaintextOnlyInResponse_dbHoldsDigestOnly_ttlFromK8Key() {
        configCacheHolder.put("installkit.token-ttl-seconds", "600");
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", "demo-project", 1, "S2 扩容演练机", "admin"));

        assertNotNull(issued.token(), "签发响应必须带一次性明文");
        assertNotNull(issued.id());
        assertEquals("executor", issued.role());
        assertEquals(600L, ttlWindowSeconds(issued.id()), "TTL 应现读 K8 键 600");

        Integer plaintextHits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_enroll_token WHERE token_digest = ?",
                Integer.class, issued.token());
        assertEquals(0, plaintextHits, "明文禁入 token_digest 列");
        String digest = jdbcTemplate.queryForObject(
                "SELECT token_digest FROM tp_cluster_enroll_token WHERE id = ?",
                String.class, issued.id());
        assertEquals(64, digest.length(), "token_digest 应为 sha256 hex(64)");
        assertTrue(digest.matches("[0-9a-f]{64}"), "摘要应为小写 hex");
    }

    @Test
    void issue_missingOrInvalidTtlKey_fallsBackConservativeDefault() {
        EnrollTokenIssuedView missing = enrollTokenService.issue(
                new EnrollTokenIssueInput("ai-node", null, null, null, "admin"));
        assertEquals(3600L, ttlWindowSeconds(missing.id()), "缺键应回退默认 3600");

        configCacheHolder.put("installkit.token-ttl-seconds", "not-a-number");
        EnrollTokenIssuedView invalid = enrollTokenService.issue(
                new EnrollTokenIssueInput("ai-node", null, 1, null, "admin"));
        assertEquals(3600L, ttlWindowSeconds(invalid.id()), "非法值应回退默认 3600");

        configCacheHolder.put("installkit.token-ttl-seconds", "120");
        EnrollTokenIssuedView hot = enrollTokenService.issue(
                new EnrollTokenIssueInput("platform", null, 1, null, "admin"));
        assertEquals(120L, ttlWindowSeconds(hot.id()), "改键后新签发应即读 120（热更）");
    }

    @Test
    void issue_rejectsUnknownRoleAndNonPositiveMaxUses() {
        ServiceException role = assertThrows(ServiceException.class,
                () -> enrollTokenService.issue(new EnrollTokenIssueInput("ssh-master", null, 1, null, "admin")));
        assertTrue(role.getMessage().contains("executor") && role.getMessage().contains("ai-node")
                        && role.getMessage().contains("platform"),
                "未知角色应人话列值域: " + role.getMessage());

        assertThrows(ServiceException.class,
                () -> enrollTokenService.issue(new EnrollTokenIssueInput("executor", null, 0, null, "admin")));
    }


    @Test
    void issue_actorRoleNotInAllowList_rejected_hotTightenTakesEffect() {
        configCacheHolder.put("installkit.allow-roles", "admin, test_lead");
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> enrollTokenService.issue(new EnrollTokenIssueInput("executor", null, 1, null, "tester")));
        assertTrue(blocked.getMessage().contains("tester") && blocked.getMessage().contains("允许签发"),
                "非清单角色签发应人话拒（含当前角色）: " + blocked.getMessage());

        assertNotNull(enrollTokenService.issue(
                        new EnrollTokenIssueInput("executor", null, 1, null, "admin")).token(),
                "清单内 admin 应可签发");

        assertNotNull(enrollTokenService.issue(
                        new EnrollTokenIssueInput("ai-node", null, 1, null, " test_lead ")).token(),
                "CSV 项首尾空格应修剪（角色 test_lead 在清单内）");

        configCacheHolder.put("installkit.allow-roles", "admin");
        assertThrows(ServiceException.class,
                () -> enrollTokenService.issue(new EnrollTokenIssueInput("executor", null, 1, null, "test_lead")));
    }

    @Test
    void issue_allowKeyMissingOrBlank_allowsAnyActor() {
        configCacheHolder.remove("installkit.allow-roles");
        assertNotNull(enrollTokenService.issue(
                        new EnrollTokenIssueInput("executor", null, 1, null, "guest")).token(),
                "键缺失应全放（不限操作人角色）");
        configCacheHolder.put("installkit.allow-roles", "   ");
        assertNotNull(enrollTokenService.issue(
                        new EnrollTokenIssueInput("executor", null, 1, null, null)).token(),
                "键留空应全放");
    }

    @Test
    void issue_actorRoleCasedDifferentlyThanSeed_matchesCaseInsensitively() {
        // Sessions may present the identity provider's cased code (e.g. "ADMIN")
        // while the shipped whitelist seed is lowercase; the gate must not
        // refuse a whitelisted role over letter case alone.
        configCacheHolder.put("installkit.allow-roles", "admin,tester,test_lead,developer");
        assertNotNull(enrollTokenService.issue(
                        new EnrollTokenIssueInput("executor", null, 1, null, "ADMIN")).token(),
                "uppercase ADMIN must pass the lowercase seed");
        assertNotNull(enrollTokenService.issue(
                        new EnrollTokenIssueInput("executor", null, 1, null, "Test_Lead")).token(),
                "mixed-case role must pass regardless of seed casing");
        // Non-whitelisted roles still refused, and the refusal echoes the input as sent.
        configCacheHolder.put("installkit.allow-roles", "admin");
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> enrollTokenService.issue(new EnrollTokenIssueInput("executor", null, 1, null, "OPS")));
        assertTrue(blocked.getMessage().contains("OPS"),
                "refusal must name the role exactly as presented: " + blocked.getMessage());
    }

    @Test
    void issue_blankActorRoleWithConfiguredAllowList_failClosed() {
        configCacheHolder.put("installkit.allow-roles", "admin");
        ServiceException e = assertThrows(ServiceException.class,
                () -> enrollTokenService.issue(new EnrollTokenIssueInput("executor", null, 1, null, "  ")));
        assertTrue(e.getMessage().contains("角色"), "未提供角色应人话拒: " + e.getMessage());
    }


    @Test
    void mirrorBaseUrl_liveReadsK8Key_hotUpdateTakesEffect_missingFallsBackBlank() {
        configCacheHolder.remove("installkit.mirror-base-url");
        assertEquals("", enrollTokenService.mirrorBaseUrl(), "缺键应回退空串（官方源语义）");

        configCacheHolder.put("installkit.mirror-base-url", " http://mirror.example.net/m ");
        assertEquals("http://mirror.example.net/m", enrollTokenService.mirrorBaseUrl(),
                "键值应修剪后现读");

        configCacheHolder.put("installkit.mirror-base-url", "http://mirror2.example.net");
        assertEquals("http://mirror2.example.net", enrollTokenService.mirrorBaseUrl(),
                "改键后应立即可读新值（热更）");

        configCacheHolder.put("installkit.mirror-base-url", "   ");
        assertEquals("", enrollTokenService.mirrorBaseUrl(), "留空键=官方源空串");
    }


    @Test
    void enroll_bindsFingerprint_exhaustedToUsed_secondEnrollRejected() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", "p-demo", 1, null, "admin"));

        EnrollAcceptedView accepted = enrollTokenService.enroll(issued.token(), "fp-machine-A");
        assertEquals(issued.id(), accepted.tokenId());
        assertEquals("executor", accepted.role());
        assertEquals("p-demo", accepted.projectScope());

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_cluster_enroll_token WHERE id = ?", Integer.class, issued.id());
        assertEquals(1, status, "maxUses=1 核销后应翻 USED(1)");
        String bound = jdbcTemplate.queryForObject(
                "SELECT bound_fingerprint FROM tp_cluster_enroll_token WHERE id = ?",
                String.class, issued.id());
        assertEquals("fp-machine-A", bound, "首用应绑定机器指纹");

        ServiceException reuse = assertThrows(ServiceException.class,
                () -> enrollTokenService.enroll(issued.token(), "fp-machine-A"));
        assertTrue(reuse.getMessage().contains("已核销") || reuse.getMessage().contains("用过"),
                "重复核销应人话拒: " + reuse.getMessage());
    }

    @Test
    void enroll_multiUse_secondMachineDifferentFingerprintRejected() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("ai-node", null, 2, "双机令牌", "admin"));

        enrollTokenService.enroll(issued.token(), "fp-machine-A");
        Integer midStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_cluster_enroll_token WHERE id = ?", Integer.class, issued.id());
        assertEquals(0, midStatus, "maxUses=2 首次核销后仍待用");

        ServiceException mismatch = assertThrows(ServiceException.class,
                () -> enrollTokenService.enroll(issued.token(), "fp-machine-B"));
        assertTrue(mismatch.getMessage().contains("指纹"),
                "异机核销应 fail-closed 人话含指纹: " + mismatch.getMessage());

        enrollTokenService.enroll(issued.token(), "fp-machine-A");
        Integer usedCount = jdbcTemplate.queryForObject(
                "SELECT used_count FROM tp_cluster_enroll_token WHERE id = ?",
                Integer.class, issued.id());
        assertEquals(2, usedCount);
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_cluster_enroll_token WHERE id = ?", Integer.class, issued.id());
        assertEquals(1, status);
    }

    @Test
    void enroll_expiredToken_markedExpiredAndRejected() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, null, "admin"));
        jdbcTemplate.update("UPDATE tp_cluster_enroll_token SET expires_at = now() - interval '1 hour' WHERE id = ?",
                issued.id());

        ServiceException e = assertThrows(ServiceException.class,
                () -> enrollTokenService.enroll(issued.token(), "fp-machine-A"));
        assertTrue(e.getMessage().contains("过期"), "过期核销应人话含过期: " + e.getMessage());
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_cluster_enroll_token WHERE id = ?", Integer.class, issued.id());
        assertEquals(2, status, "过期命中应翻 EXPIRED(2) 留痕");
    }

    @Test
    void enroll_unknownOrBlankTokenOrFingerprint_rejected() {
        ServiceException unknown = assertThrows(ServiceException.class,
                () -> enrollTokenService.enroll("no-such-token-value", "fp-x"));
        assertTrue(unknown.getMessage().contains("令牌"), "未知令牌应人话拒: " + unknown.getMessage());

        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, null, "admin"));
        assertThrows(ServiceException.class, () -> enrollTokenService.enroll("   ", "fp-x"));
        assertThrows(ServiceException.class, () -> enrollTokenService.enroll(issued.token(), "  "));
    }


    @Test
    void revoke_pendingFlipsRevoked_usedRejected_absentRejected() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, null, "admin"));
        enrollTokenService.revoke(issued.id());
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_cluster_enroll_token WHERE id = ?", Integer.class, issued.id());
        assertEquals(3, status, "撤销应翻 REVOKED(3)");

        ServiceException revokedUse = assertThrows(ServiceException.class,
                () -> enrollTokenService.enroll(issued.token(), "fp-machine-A"));
        assertTrue(revokedUse.getMessage().contains("撤销"), "已撤销令牌核销拒: " + revokedUse.getMessage());

        EnrollTokenIssuedView used = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, null, "admin"));
        enrollTokenService.enroll(used.token(), "fp-machine-A");
        assertThrows(ServiceException.class, () -> enrollTokenService.revoke(used.id()));

        ServiceException absent = assertThrows(ServiceException.class,
                () -> enrollTokenService.revoke(999_999_999L));
        assertTrue(absent.getMessage().contains("不存在"), "撤销不存在应 404 人话: " + absent.getMessage());
    }


    @Test
    void heartbeat_boundFingerprint_touchesRow_othersRejected() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, null, "admin"));
        enrollTokenService.heartbeat(issued.token(), "fp-machine-A");
        Long touchedAgo = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - updated_at))::bigint"
                        + " FROM tp_cluster_enroll_token WHERE id = ?",
                Long.class, issued.id());
        assertNotNull(touchedAgo);
        assertTrue(touchedAgo >= 0 && touchedAgo < 60, "心跳应刷新 updated_at 打点，实得秒前=" + touchedAgo);

        enrollTokenService.enroll(issued.token(), "fp-machine-A");
        assertThrows(ServiceException.class,
                () -> enrollTokenService.heartbeat(issued.token(), "fp-machine-X"));
        assertThrows(ServiceException.class, () -> enrollTokenService.heartbeat("bogus", "fp"));
    }

    // ── Heartbeat components trailing key ( pack C: agent heartbeat →
    // platform-family report lands on the ONE component ledger channel) ──

    /**
     * Trailing components key = host service-agent platform-family report. The
     * ledger row must land on the SAME ClusterComponentIngestor channel as
     * executor heartbeats (one ledger, two sources), addressed as "token:<id>"
     * so the upgrade wave can resolve the host through the token row.
     */
    @Test
    void heartbeat_componentsTrailingKey_landsPlatformLedgerUnderTokenAddress() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("platform", null, 2, "T354 包C 切片", "admin"));
        String node = "token:" + issued.id();
        try {
            enrollTokenService.heartbeat(issued.token(), "fp-agent-host", "healthcheck", "running",
                    "自检上报", java.util.List.of(
                            java.util.Map.of("family", "core", "currentVersion", "1.0.0"),
                            java.util.Map.of("family", "frontend", "currentVersion", "1.0.1")));

            Integer landed = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_cluster_component WHERE node_id = ?"
                            + " AND component_family IN ('core','frontend') AND source = 'heartbeat'",
                    Integer.class, node);
            assertEquals(2, landed, "platform 族两行应经 ingestor 单通道落台账");
            String current = jdbcTemplate.queryForObject(
                    "SELECT current_version FROM tp_cluster_component"
                            + " WHERE node_id = ? AND component_family = 'core'",
                    String.class, node);
            assertEquals("1.0.0", current, "现值原样入账（心跳不传基准=粘滞保留）");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", node);
        }
    }

    /** No components key = old heartbeat: zero ledger writes, zero residue. */
    @Test
    void heartbeat_withoutComponentsKey_zeroLedgerWrites() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("platform", null, 2, "T354 包C 切片", "admin"));
        String node = "token:" + issued.id();
        try {
            enrollTokenService.heartbeat(issued.token(), "fp-agent-host");
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_cluster_component WHERE node_id = ?", Integer.class, node);
            assertEquals(0, rows, "旧口径打点禁建台账行（缺键=零调用零漂移）");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", node);
        }
    }

    /**
     * Dirty rows are skipped row-by-row (never swallow the good rows of the
     * same batch, never break the heartbeat main chain — 15s storm safety).
     */
    @Test
    void heartbeat_componentsDirtyRows_honestRowSkipGoodRowsStillLand() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("platform", null, 2, "T354 包C 切片", "admin"));
        String node = "token:" + issued.id();
        try {
            enrollTokenService.heartbeat(issued.token(), "fp-agent-host", null, null, null,
                    java.util.List.of(
                            "not-a-map-row",
                            java.util.Map.of("family", "  ", "currentVersion", "9.9.9"),
                            java.util.Map.of("family", "admin"),
                            java.util.Map.of("family", "api", "currentVersion", "1.0.2")));
            Integer landed = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_cluster_component WHERE node_id = ? AND source = 'heartbeat'",
                    Integer.class, node);
            assertEquals(1, landed, "同批好行照常入账，坏行逐跳不连坐");
            String family = jdbcTemplate.queryForObject(
                    "SELECT component_family FROM tp_cluster_component WHERE node_id = ?",
                    String.class, node);
            assertEquals("api", family);
        } finally {
            jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", node);
        }
    }


    @Test
    void list_neverExposesPlaintextOrDigest() {
        EnrollTokenIssuedView issued = enrollTokenService.issue(
                new EnrollTokenIssueInput("platform", "scope-1", 1, "备注一", "admin"));
        List<EnrollTokenView> rows = enrollTokenService.list();
        assertTrue(rows.stream().anyMatch(v -> issued.id().equals(v.id())));
        EnrollTokenView row = rows.stream().filter(v -> issued.id().equals(v.id())).findFirst().orElseThrow();
        assertEquals("platform", row.role());
        assertEquals("scope-1", row.projectScope());
        assertEquals(0, row.status());
        assertNotNull(row.expiresAt());
        assertTrue(List.of(row.getClass().getRecordComponents()).stream()
                        .noneMatch(c -> c.getName().toLowerCase().contains("token")
                                || c.getName().toLowerCase().contains("digest")),
                "EnrollTokenView 禁含明文/摘要字段");
    }

    @Test
    void list_keepsUsableAndRedeemed_dropsRevokedAndExpired() {
        // The wizard ledger is an ACTION view: rows a user can still use or
        // that record a completed enrollment stay listed; dead rows (revoked,
        // expired — even before the sweeper flips the status) leave silently,
        // matching the e2e contract "revoke -> this run's row is gone".
        EnrollTokenIssuedView pending = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 2, "ledger-pending", "admin"));
        EnrollTokenIssuedView revoked = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 2, "ledger-revoked", "admin"));
        EnrollTokenIssuedView redeemed = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, "ledger-redeemed", "admin"));
        EnrollTokenIssuedView expired = enrollTokenService.issue(
                new EnrollTokenIssueInput("executor", null, 1, "ledger-expired", "admin"));

        enrollTokenService.revoke(revoked.id());
        enrollTokenService.enroll(redeemed.token(), "fp-ledger-A");
        jdbcTemplate.update(
                "UPDATE tp_cluster_enroll_token SET expires_at = now() - interval '1 hour' WHERE id = ?",
                expired.id());

        List<Long> ids = enrollTokenService.list().stream().map(EnrollTokenView::id).toList();
        assertTrue(ids.contains(pending.id()), "unconsumed token must stay listed");
        assertTrue(ids.contains(redeemed.id()), "redeemed token remains as enrollment history");
        assertFalse(ids.contains(revoked.id()), "revoked token leaves the ledger view");
        assertFalse(ids.contains(expired.id()), "expired token leaves the ledger view even pre-sweep");
    }


    
    private long ttlWindowSeconds(Long id) {
        Long window = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (expires_at - created_at))::bigint"
                        + " FROM tp_cluster_enroll_token WHERE id = ?",
                Long.class, id);
        return window == null ? -1 : window;
    }

    @Test
    void appautoEngineRecipeSeedStillInPlace_afterClusterDomainLanded() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM tp_appauto_engine WHERE install_recipe <> '{}'::jsonb",
                String.class);
        assertTrue(codes.contains("MOBILEWRIGHT") && codes.contains("MINIUM"),
                "V165 两配方种子应在位: " + codes);
    }
}
