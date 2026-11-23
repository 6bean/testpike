package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ArtifactPrecheckItemView;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Artifact reachability pre-check in the upgrade plan impact view (step-2 gate).
 * <p>Verdict spectrum proven here (single source = ReleaseArtifactProber reading
 * ReleaseArtifactRules whitelist — no second copy): (1) HEAD 2xx with declared
 * size matching the origin = reachable green row; (2) HTTP error / refused
 * connection = unreachable red row with a plain-language "mirror broken, fix
 * before rolling" sentence; (3) host outside the whitelist = host_blocked AND
 * zero outbound probes (SSRF/supply-chain red line — blocked URLs are never
 * fetched, not even a HEAD); (4) declared vs origin size mismatch =
 * size_mismatch (silent truncation/replacement caught); (5) plan without a
 * pinned manifest version, or manifest without artifacts = zero rows — the
 * gate never fabricates verdicts so existing green impact views stay green.
 * <p>Real dev DB via SpringBootTest (no mock of business chain); the download
 * origin is a loopback fixture stub (ClusterUpstreamProbeTest approved posture).
 * The stub records request methods: the probe must be HEAD-only — a pre-check
 * that downloads whole packages would break the 10s wizard budget.
 */
@SpringBootTest(classes = CoreApplication.class)
class UpgradePlanArtifactPrecheckTest {

    private static final String NODE = "t353-precheck-node";
    private static final String OPERATOR = "t353-precheck";
    private static final String HOSTS_KEY = "testpike.cluster.artifact-allowed-hosts";
    /** Seed value from V223 (schema-init parity) — @AfterEach restores it (leak defense). */
    private static final String SEEDED_HOSTS = "github.com,objects.githubusercontent.com";

    @Autowired
    private UpgradePlanService planService;
    @Autowired
    private ClusterComponentService componentService;
    @Autowired
    private ReleaseManifestService manifestService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LoopbackArtifactStub stub;
    private ServerSocket refused;

    @BeforeEach
    void seed() throws IOException {
        clean();
        stub = new LoopbackArtifactStub();
        stub.route("/ok/core.jar", 200, 1024);
        stub.route("/wrongsize/core.jar", 200, 999_999);
        stub.route("/missing/core.jar", 404, 0);
        // bound-but-never-accepted socket = deterministic connection refused fixture
        refused = new ServerSocket(0, 1);
        configCacheHolder.put(HOSTS_KEY, "127.0.0.1,localhost.example");
        // one YELLOW ledger row (MOBILEWRIGHT runtime=node family, behind baseline)
        // so impact() passes its nothing-upgradable guard and reaches the pre-check
        componentService.upsert(NODE, "engine-runtime.node", "22.12", "scan", ">=22.12", "24.0");
    }

    @AfterEach
    void tearDown() throws IOException {
        clean();
        stub.close();
        refused.close();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id = ?", NODE);
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE node_id = ?", NODE);
        jdbcTemplate.execute("DELETE FROM tp_upgrade_plan WHERE operator = '" + OPERATOR + "'");
        jdbcTemplate.execute("DELETE FROM tp_release_manifest WHERE version LIKE '9.353.%'");
        // Restore the seeded whitelist instead of removing the key: the cache holder is
        // shared across the whole Spring test context, and a remove() here silently
        // blanks github.com for LATER test classes (register-gate SSRF rejections
        // unrelated to this fixture — batch regression proved the leak path).
        configCacheHolder.put(HOSTS_KEY, SEEDED_HOSTS);
        configCacheHolder.remove("testpike.cluster.artifact-probe-timeout-seconds");
    }

    private String url(String path) {
        return "http://127.0.0.1:" + stub.port() + path;
    }

    /** Register a manifest carrying the given artifact array (matrix without pins keeps the coverage gate green). */
    private void registerManifest(String version, String artifactsJson) {
        manifestService.register(new ReleaseRegisterInput(version,
                "[{\"component\":\"core\",\"support\":\">=1.0\"}]", "[]",
                null, null, null, "manual", artifactsJson, null));
    }

    private static String artifact(String family, String artifactUrl, long size) {
        return "{\"component_family\":\"" + family + "\",\"url\":\"" + artifactUrl
                + "\",\"sha256\":\"" + "a".repeat(64) + "\",\"size\":" + size + "}";
    }

    private UpgradePlanImpactView impactWith(String version) {
        // MOBILEWRIGHT engine scope: ledger YELLOW row above gives a non-empty preview
        return planService.impact(new UpgradePlanCreateInput("engine", List.of("MOBILEWRIGHT"),
                null, null, null, version,null,null, OPERATOR));
    }

    @Test
    void reachableArtifact_headOk_greenRowWithSizes() {
        registerManifest("9.353.1", "[" + artifact("core", url("/ok/core.jar"), 1024) + "]");

        UpgradePlanImpactView impact = impactWith("9.353.1");

        assertEquals(1, impact.precheck().size(), "one row per declared artifact");
        ArtifactPrecheckItemView row = impact.precheck().get(0);
        assertEquals(ArtifactPrecheckItemView.VERDICT_REACHABLE, row.verdict(), row.reason());
        assertEquals("core", row.componentFamily());
        assertEquals(1024, row.declaredSize());
        assertEquals(1024, row.observedSize(), "origin-reported length shown in the wizard row");
        assertTrue(stub.seenMethods().stream().allMatch("HEAD"::equals),
                "pre-check must probe with HEAD only — full packages never downloaded: " + stub.seenMethods());
    }

    @Test
    void httpErrorAndRefused_unreachableRows_humanFixMirrorSentence() {
        registerManifest("9.353.2", "[" + artifact("core", url("/missing/core.jar"), 1024)
                + "," + artifact("executor-agent",
                "http://127.0.0.1:" + refused.getLocalPort() + "/ok/core.jar", 512) + "]");

        UpgradePlanImpactView impact = impactWith("9.353.2");

        assertEquals(2, impact.precheck().size());
        ArtifactPrecheckItemView notFound = findByFamily(impact.precheck(), "core");
        ArtifactPrecheckItemView refusedRow = findByFamily(impact.precheck(), "executor-agent");
        assertEquals(ArtifactPrecheckItemView.VERDICT_UNREACHABLE, notFound.verdict(), notFound.reason());
        assertTrue(notFound.reason().contains("404"), "HTTP code belongs in the human reason: " + notFound.reason());
        assertTrue(notFound.reason().contains("不可达"), "plain-language sentence expected: " + notFound.reason());
        assertEquals(ArtifactPrecheckItemView.VERDICT_UNREACHABLE, refusedRow.verdict(), refusedRow.reason());
        assertTrue(refusedRow.reason().contains("不可达"),
                "refused connection = same honest red row: " + refusedRow.reason());
    }

    @Test
    void hostOutsideWhitelist_blockedWithoutAnyOutboundProbe() {
        // register while 127.0.0.2 is still whitelisted, then narrow the K8 whitelist
        // (hot-update scenario); 127.0.0.2 is a valid Linux loopback alias — even a
        // reachable origin must get ZERO probes once the host leaves the whitelist
        // (SSRF red line: the whitelist decides before any outbound traffic)
        configCacheHolder.put(HOSTS_KEY, "127.0.0.2");
        registerManifest("9.353.3", "[" + artifact("core",
                "http://127.0.0.2:" + stub.port() + "/ok/core.jar", 1024) + "]");
        configCacheHolder.put(HOSTS_KEY, "localhost.example");

        UpgradePlanImpactView impact = impactWith("9.353.3");

        ArtifactPrecheckItemView row = impact.precheck().get(0);
        assertEquals(ArtifactPrecheckItemView.VERDICT_HOST_BLOCKED, row.verdict(), row.reason());
        assertTrue(row.reason().contains("白名单"), "whitelist key named in the human reason: " + row.reason());
        assertTrue(stub.seenMethods().isEmpty(), "blocked hosts must receive zero probes — nothing sent outbound");
    }

    @Test
    void declaredSizeMismatch_sizeMismatchRow() {
        registerManifest("9.353.4", "[" + artifact("core", url("/wrongsize/core.jar"), 1024) + "]");

        UpgradePlanImpactView impact = impactWith("9.353.4");

        ArtifactPrecheckItemView row = impact.precheck().get(0);
        assertEquals(ArtifactPrecheckItemView.VERDICT_SIZE_MISMATCH, row.verdict(), row.reason());
        assertEquals(1024, row.declaredSize());
        assertEquals(999_999, row.observedSize());
    }

    @Test
    void noPinnedVersionOrEmptyArtifacts_zeroRowsNeverFakeRed() {
        // (a) plan without manifest reference = gate not applicable
        assertEquals(List.of(), planService.impact(new UpgradePlanCreateInput("engine",
                List.of("MOBILEWRIGHT"), null, null, null, null,null,null, OPERATOR)).precheck(),
                "unpinned plan must not fabricate pre-check rows");

        // (b) announce-only release (empty artifact list) = zero rows, wizard stays green
        registerManifest("9.353.5", "[]");
        UpgradePlanImpactView impact = impactWith("9.353.5");
        assertEquals(List.of(), impact.precheck(), "empty artifact list = no rows (no fake red)");
        assertTrue(impact.affectedNodes() >= 1, "the rest of the impact view keeps working untouched");
    }

    private static ArtifactPrecheckItemView findByFamily(List<ArtifactPrecheckItemView> rows, String family) {
        return rows.stream().filter(r -> family.equals(r.componentFamily())).findFirst()
                .orElseThrow(() -> new AssertionError("missing precheck row for " + family));
    }
}
