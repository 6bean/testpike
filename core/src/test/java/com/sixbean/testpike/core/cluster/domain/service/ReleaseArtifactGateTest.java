package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release artifact supply-chain gates on the manifest registration path.
 * <p>Coverage: (1) a matrix family that declares a pinned version must ship a
 * matching download artifact (reject with the family named in plain words —
 * "declared but nothing to upgrade from" is the failure this gate exists for);
 * (2) no pin + no artifacts = announce-only registration stays allowed (release
 * declaration is decoupled from package distribution, intranet manual deployments
 * must not break); (3) artifact item shape (component_family / url scheme /
 * sha256 each pointed out per item); (4) download-host whitelist (SSRF and
 * supply-chain red line: hosts outside the list are rejected; the
 * installkit.mirror-base-url host is merged at read time so mirror re-routing
 * takes effect without a restart); (5) artifacts/notes round-trip and
 * published_at persistence.
 * <p>Runs against the real dev database (SpringBootTest, no mocks); the verdict
 * spectrum is single-source in ReleaseArtifactRules.
 */
@SpringBootTest(classes = CoreApplication.class)
class ReleaseArtifactGateTest {

    @Autowired
    private ReleaseManifestService manifestService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String originalMirror;

    @BeforeEach
    @AfterEach
    void cleanAndRestore() {
        jdbcTemplate.execute("DELETE FROM tp_release_manifest");
        originalMirror = configCacheHolder.get("installkit.mirror-base-url");
    }

    @AfterEach
    void restoreMirrorKey() {
        if (originalMirror != null) {
            configCacheHolder.put("installkit.mirror-base-url", originalMirror);
        } else {
            configCacheHolder.remove("installkit.mirror-base-url");
        }
    }

    private static ReleaseRegisterInput input(String matrixJson, String artifactsJson, String notes) {
        return new ReleaseRegisterInput("3.0.0", matrixJson, "[]", null, null, null, "manual",
                artifactsJson, notes);
    }

    private static String artifact(String family, String url, String sha) {
        return "{\"component_family\":\"" + family + "\",\"url\":\"" + url
                + "\",\"sha256\":\"" + sha + "\",\"size\":1024}";
    }

    @Test
    void pinnedFamilyWithoutArtifact_rejectedNamingFamily() {
        // A matrix item declaring pinned must have its artifact supplied,
        // otherwise registration is rejected with the family named in words.
        ServiceException e = assertThrows(ServiceException.class, () -> manifestService.register(
                input("[{\"component\":\"executor\",\"support\":\">=1.3\",\"pinned\":\"1.4.2\"}]",
                        "[]", null)));
        assertTrue(e.getMessage().contains("executor") && e.getMessage().contains("钉版"),
                "须点名钉版族：部件 executor 声明了钉版但未提供下载件。实际：" + e.getMessage());
    }

    @Test
    void pinnedFamilyWithMatchingArtifact_acceptedAndRoundTrip() {
        ReleaseManifestInfo saved = manifestService.register(input(
                "[{\"component\":\"executor\",\"support\":\">=1.3\",\"pinned\":\"1.4.2\"}]",
                "[" + artifact("executor", "https://github.com/testpike/releases/executor-1.4.2.zip",
                        "a".repeat(64)) + "]",
                "## 3.0.0\n升级 executor 协议兼容 1.3~1.5"));
        assertNotNull(saved.artifacts(), "工件清单读回非空");
        assertTrue(saved.artifacts().contains("executor-1.4.2.zip"), "工件 url 透传：" + saved.artifacts());
        assertTrue(saved.releaseNotesMd().contains("协议兼容"), "发布说明 markdown 透传");
        assertNotNull(saved.publishedAt(), "发布时间落库（默认=登记时点）");
    }

    @Test
    void noPinned_noArtifacts_announceOnlyAllowed() {
        // Declaration is decoupled from package distribution: a family without
        // a pin does not force an artifact (manual intranet deployments must not break).
        ReleaseManifestInfo saved = manifestService.register(input(
                "[{\"component\":\"core\",\"support\":\">=2.0\"}]", "[]", null));
        assertEquals("[]", saved.artifacts(), "无工件=合法登记，空数组读回");
    }

    @Test
    void artifactItemShape_checkedPerFieldWithHumanMessage() {
        ServiceException noFamily = assertThrows(ServiceException.class, () -> manifestService.register(
                input("[]", "[{\"url\":\"https://github.com/a.zip\",\"sha256\":\"" + "b".repeat(64) + "\"}]", null)));
        assertTrue(noFamily.getMessage().contains("component_family"), noFamily.getMessage());

        ServiceException noSha = assertThrows(ServiceException.class, () -> manifestService.register(
                input("[]", "[{\"component_family\":\"core\",\"url\":\"https://github.com/a.zip\"}]", null)));
        assertTrue(noSha.getMessage().contains("sha256"), noSha.getMessage());

        ServiceException badScheme = assertThrows(ServiceException.class, () -> manifestService.register(
                input("[]", "[{\"component_family\":\"core\",\"url\":\"ftp://github.com/a.zip\",\"sha256\":\""
                        + "c".repeat(64) + "\"}]", null)));
        assertTrue(badScheme.getMessage().contains("http"), "仅允许 http/https 下载：" + badScheme.getMessage());
    }

    @Test
    void urlOutsideWhitelist_rejected() {
        // SSRF / supply-chain red line: hosts outside the whitelist are rejected
        // (default list covers github.com; the example domain below is not in it).
        ServiceException e = assertThrows(ServiceException.class, () -> manifestService.register(
                input("[]", "[" + artifact("core", "https://evil-cdn.example.com/core.jar", "d".repeat(64)) + "]",
                        null)));
        assertTrue(e.getMessage().contains("白名单"), e.getMessage());
    }

    @Test
    void mirrorHost_dynamicallyMergedIntoWhitelist() {
        // Mirror re-routing: the installkit.mirror-base-url host joins the
        // whitelist at read time (hot key, no secondary cache).
        configCacheHolder.put("installkit.mirror-base-url", "http://releases.example.net/releases");
        ReleaseManifestInfo saved = manifestService.register(input(
                "[]", "[" + artifact("core", "http://releases.example.net:8081/releases/core.jar", "e".repeat(64)) + "]",
                null));
        assertTrue(saved.artifacts().contains("releases.example.net"), "镜像域工件放行：" + saved.artifacts());
    }

    @Test
    void matrixPinnedFamilyMatchesArtifactCaseAndTrim() {
        // Coverage tolerance on the family-name match: case-insensitive and
        // whitespace-trimmed (registration must not reject harmless variance).
        ReleaseManifestInfo saved = manifestService.register(input(
                "[{\"component\":\"Executor\",\"support\":\">=1.3\",\"pinned\":\" 1.4.2 \"}]",
                "[" + artifact("executor", "https://github.com/t/e.zip", "f".repeat(64)) + "]", null));
        assertNotNull(saved.id());
    }
}
