package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateDetectedInput;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release candidate service segment test (AF batch two-state model: detection
 * lands in the candidate table, publishing stays a human-authorised act).
 * <p>Verified surface: (1) recordDetected persists + pending read-back keeps every
 * field (raw payload passed through verbatim); (2) re-detecting the same version =
 * idempotent upsert, one row only; (3) confirm routes exclusively through the
 * existing ReleaseManifestService.register single point (source forced to import,
 * authoritative latest changes) and stamps confirmed + confirmed_manifest_id;
 * (4) the status spectrum is monotonic — confirmed/dismissed/replaced rows never
 * roll back on re-detection (no banner revival), double confirm / confirm-after-
 * dismiss / double dismiss all rejected in plain words; (5) dismiss removes the
 * row from pending (keeps banners from lingering) and keeps the reason on record;
 * (6) after confirmation, lower detected candidates become replaced; (7) a version
 * already present in the authoritative table lands replaced, not pending;
 * (8) input validation rejections (blank version / non-object payload).
 * <p>Runs against the real dev database (SpringBootTest, no mocks; data access on
 * the NamedParameterJdbcTemplate baseline).
 */
@SpringBootTest(classes = CoreApplication.class)
class ReleaseCandidateServiceTest {

    @Autowired
    private ReleaseCandidateService candidateService;

    @Autowired
    private ReleaseManifestService manifestService;

    @Autowired
    private ClusterComponentService componentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Dedicated ledger node for confirmation write-back assertions (never a real executor). */
    private static final String LEDGER_NODE = "t352-confirmation-node";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM tp_release_candidate");
        jdbcTemplate.execute("DELETE FROM tp_release_manifest");
        jdbcTemplate.execute("DELETE FROM tp_cluster_component WHERE node_id = '" + LEDGER_NODE + "'");
    }

    private ClusterComponentInfo ledgerRow(String family) {
        return componentService.listByNode(LEDGER_NODE).stream()
                .filter(r -> r.componentFamily().equals(family)).findFirst().orElseThrow();
    }

    private static ReleaseCandidateDetectedInput detected(String version) {
        return new ReleaseCandidateDetectedInput(version, "released", "poll",
                "{\"release_name\":\"testPike " + version + "\","
                        + "\"release_notes_md\":\"候选检出人话原文：升级中心待确认样例\",\"assets\":[]}");
    }

    private static ReleaseRegisterInput registerInput(String version) {
        return new ReleaseRegisterInput(version,
                "[{\"component\":\"executor-agent\",\"support\":\">=1.3\"}]",
                "[\"候选确认样例：定位语义变更需重录校准\"]",
                "1.3.0", "1.5.0", null, "import", null, null);
    }

    @Test
    void recordDetected_thenPendingRoundTrip() {
        ReleaseCandidateInfo saved = candidateService.recordDetected(detected("1.9.0"));
        assertNotNull(saved.id(), "检出必须落库回读");
        assertEquals("detected", saved.status());
        assertEquals("poll", saved.detectedSource());
        assertEquals("released", saved.channel());
        assertNotNull(saved.detectedAt(), "检出时间落库");

        List<ReleaseCandidateInfo> pending = candidateService.listPending();
        assertEquals(1, pending.size(), "detected 候选进待确认清单");
        assertTrue(pending.get(0).payloadJson().contains("升级中心待确认样例"),
                "payload 人话原文直透（changelog 草稿态消费源）");
        assertEquals(1, candidateService.pendingCount(), "角标计数=pending 数");
    }

    @Test
    void reDetect_sameVersion_overwritesSingleRow() {
        candidateService.recordDetected(detected("1.9.0"));
        candidateService.recordDetected(new ReleaseCandidateDetectedInput("1.9.0", "released",
                "poll", "{\"release_name\":\"刷新后的名称\",\"assets\":[]}"));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_release_candidate WHERE version = '1.9.0'", Integer.class);
        assertEquals(1, rows, "版本唯一行，重探=覆盖禁第二行（幂等）");
        assertTrue(candidateService.listPending().get(0).payloadJson().contains("刷新后的名称"),
                "payload 覆盖为最新检出帧");
    }

    @Test
    void confirm_routesThroughRegisterSinglePoint() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));
        ReleaseManifestInfo published = candidateService.confirm(candidate.id(), registerInput("1.9.0"));

        assertEquals("1.9.0", published.version());
        assertEquals("import", published.source(), "机器摄入候选确认=import 类（source 枚举不扩【定版5】）");
        ReleaseManifestInfo latest = manifestService.latest();
        assertNotNull(latest, "确认后权威表 latest 生效（探测不直写、确认才写=两态模型）");

        ReleaseCandidateInfo after = candidateService.findByVersion("1.9.0");
        assertEquals("confirmed", after.status());
        assertEquals(published.id(), after.confirmedManifestId(), "确认=权威行 id 记账回候选");
        assertEquals(0, candidateService.pendingCount(), "已确认候选退出待确认清单");
    }

    @Test
    void confirm_lowerDetectedCandidateReplaced() {
        candidateService.recordDetected(detected("1.8.0"));
        ReleaseCandidateInfo newer = candidateService.recordDetected(detected("1.9.0"));
        candidateService.confirm(newer.id(), registerInput("1.9.0"));

        assertEquals("replaced", candidateService.findByVersion("1.8.0").status(),
                "上游只追 latest：确认后更低版本 detected 候选被取代");
    }

    @Test
    void detectedAfterConfirm_noStatusRollback() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));
        candidateService.confirm(candidate.id(), registerInput("1.9.0"));

        candidateService.recordDetected(detected("1.9.0"));
        ReleaseCandidateInfo after = candidateService.findByVersion("1.9.0");
        assertEquals("confirmed", after.status(), "状态谱单调禁回退：已确认行重探不改状态");
        assertNotNull(after.confirmedManifestId(), "重探不许冲掉确认记账");
        assertEquals(0, candidateService.pendingCount(), "重探不复活待确认横幅");
    }

    @Test
    void confirm_alreadyConfirmed_rejectedInPlainWords() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));
        candidateService.confirm(candidate.id(), registerInput("1.9.0"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> candidateService.confirm(candidate.id(), registerInput("1.9.0")));
        assertTrue(e.getMessage().contains("已确认"), "重复确认人话拒：" + e.getMessage());
    }

    @Test
    void confirm_dismissedCandidate_rejected() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));
        candidateService.dismiss(candidate.id(), "他人已手动登记同版本");

        ServiceException e = assertThrows(ServiceException.class,
                () -> candidateService.confirm(candidate.id(), registerInput("1.9.0")));
        assertTrue(e.getMessage().contains("已作废"), "作废候选禁发布（单调谱）：" + e.getMessage());
    }

    @Test
    void confirm_versionMismatch_rejected() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> candidateService.confirm(candidate.id(), registerInput("2.0.0")));
        assertTrue(e.getMessage().contains("不一致"), "防误点：确认版本号须与候选一致：" + e.getMessage());
    }

    @Test
    void dismiss_removesFromPending_keepsAuditTrail() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));
        ReleaseCandidateInfo dismissed = candidateService.dismiss(candidate.id(), "内测版不发布");

        assertEquals("dismissed", dismissed.status());
        assertEquals(0, candidateService.pendingCount(), "dismiss 防横幅常驻");
        assertTrue(dismissed.payloadJson().contains("内测版不发布"), "作废理由留痕 payload");

        ServiceException e = assertThrows(ServiceException.class,
                () -> candidateService.dismiss(candidate.id(), "重复作废"));
        assertTrue(e.getMessage().contains("已作废"), "重复 dismiss 幂等语义=人话告知已作废：" + e.getMessage());
    }

    @Test
    void dismiss_confirmedCandidate_rejected() {
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.9.0"));
        candidateService.confirm(candidate.id(), registerInput("1.9.0"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> candidateService.dismiss(candidate.id(), "试图作废已发布"));
        assertTrue(e.getMessage().contains("已确认"), "已发布候选禁作废（权威面另管）：" + e.getMessage());
    }

    @Test
    void recordDetected_versionAlreadyPublished_landsReplacedNotPending() {
        manifestService.register(registerInput("2.0.0"));
        ReleaseCandidateInfo saved = candidateService.recordDetected(detected("2.0.0"));

        assertEquals("replaced", saved.status(), "权威表已有同版本=诚实留痕直接落非 pending");
        assertEquals(0, candidateService.pendingCount(), "已发布版本不再重复提醒");
    }

    @Test
    void recordDetected_validationRejectsInPlainWords() {
        ServiceException blank = assertThrows(ServiceException.class,
                () -> candidateService.recordDetected(
                        new ReleaseCandidateDetectedInput("  ", "released", "poll", "{}")));
        assertTrue(blank.getMessage().contains("版本号"), "空白版本号人话拒：" + blank.getMessage());

        ServiceException badPayload = assertThrows(ServiceException.class,
                () -> candidateService.recordDetected(new ReleaseCandidateDetectedInput(
                        "1.9.0", "released", "poll", "[1,2]")));
        assertTrue(badPayload.getMessage().contains("JSON 对象"), "payload 非对象人话拒：" + badPayload.getMessage());

        ServiceException badSource = assertThrows(ServiceException.class,
                () -> candidateService.recordDetected(new ReleaseCandidateDetectedInput(
                        "1.9.0", "released", "carrier-pigeon", "{}")));
        assertTrue(badSource.getMessage().contains("poll"), "检出来源枚举人话拒：" + badSource.getMessage());
    }

    // ── one-click promotion: confirm writes the confirmed version back as
    // the platform-family ledger target and re-scores the three-colour verdict
    // through ComponentSupportRules (single spectrum, no new judgement logic) ──

    @Test
    void confirm_writesBackPlatformTargets_andRejudgesColours() {
        componentService.upsert(LEDGER_NODE, "core", "1.4.0", "manual", null, null);
        componentService.upsert(LEDGER_NODE, "admin", null, "manual", null, null);
        componentService.upsert(LEDGER_NODE, "executor-agent", "1.0.0", "heartbeat", null, null);

        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.5.0"));
        candidateService.confirm(candidate.id(), registerInput("1.5.0"));

        ClusterComponentInfo core = ledgerRow("core");
        assertEquals("1.5.0", core.targetVersion(), "确认发布=platform 族台账 target 转正为发布版");
        assertEquals("YELLOW", core.supportState(), "现值 1.4.0 落后新基准=黄（可升级）");
        assertTrue(core.stateReason().contains("落后"), "落后判词人话句：" + core.stateReason());

        ClusterComponentInfo admin = ledgerRow("admin");
        assertEquals("1.5.0", admin.targetVersion(), "未上报行同样接收基准回写（心跳上报后自动重判）");
        assertEquals("UNKNOWN", admin.supportState(), "current=NULL=灰「未上报」诚实缺省，禁拿基准冒充绿");
        assertTrue(admin.stateReason().contains("未上报"), "灰态理由=未上报标注：" + admin.stateReason());

        assertNull(ledgerRow("executor-agent").targetVersion(),
                "非平台发版族不参与 target 转正（infra 族无平台发版语义）");
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_cluster_component WHERE node_id = '" + LEDGER_NODE
                        + "' AND target_version = '1.5.0'", Integer.class).intValue(),
                "台账缺行族=空集 no-op 禁造行（platform 行由 T354 agent 才建档）");
    }

    @Test
    void confirm_currentEqualsTarget_judgesGreen() {
        componentService.upsert(LEDGER_NODE, "frontend", "1.5.0", "manual", null, null);
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.5.0"));
        candidateService.confirm(candidate.id(), registerInput("1.5.0"));
        assertEquals("GREEN", ledgerRow("frontend").supportState(),
                "现值==转正基准=绿（三色全景在确认后自动覆盖 platform 族）");
    }

    @Test
    void dismiss_neverWritesBackTargets() {
        componentService.upsert(LEDGER_NODE, "core", "1.4.0", "manual", null, null);
        ReleaseCandidateInfo candidate = candidateService.recordDetected(detected("1.5.0"));
        candidateService.dismiss(candidate.id(), "内测版不发布");
        assertNull(ledgerRow("core").targetVersion(), "作废=未发布，禁碰台账基准（两态模型）");
    }
}
