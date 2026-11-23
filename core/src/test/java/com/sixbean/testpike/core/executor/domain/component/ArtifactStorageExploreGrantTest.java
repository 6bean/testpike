package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ArtifactStorageExploreGrantTest {

    @Autowired
    private ArtifactStorageTestHarness harness;

    
    @Test
    void mode3项目_grant留证key规范_s3真实PUT与view字节往返() throws Exception {
        String tag = harness.uniq();
        try {
            long projectId = harness.seedProject(3, "/rec-" + tag, tag);
            StorageGrant grant = harness.artifactStorage().grantExploreUpload(projectId, "screenshot", "shot.png");
            harness.registerKey(grant.key());

            assertTrue(grant.key().startsWith("testpike/rec-" + tag + "/" + projectId
                            + "/explore/" + harness.ym() + "/"),
                    "mode=3 留证 key 须为 全局前缀/项目路径/projectId/explore：...: " + grant.key());
            assertTrue(grant.key().matches(".*/[0-9a-f]{8}-screenshot_shot\\.png"),
                    "文件名清洗段不变: " + grant.key());
            assertTrue(grant.url().startsWith(harness.urlExpectations().presignedPrefix()),
                    "mode=3 系统=s3 预签名须落云桶: " + grant.url());

            byte[] payload = ("explore-evidence-" + tag).getBytes(StandardCharsets.UTF_8);
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<byte[]> put = http.send(
                    HttpRequest.newBuilder(URI.create(grant.url()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, put.statusCode(), "预签名 PUT 须真实成功: " + put);

            assertTrue(harness.artifactStorage().exists(grant.key()),
                    "exists 须经 explore 反解命中同一路由（PUT 后必存在）");
            String view = harness.artifactStorage().grantViewUrl(grant.key());
            assertTrue(view.startsWith(harness.urlExpectations().presignedPrefix()),
                    "查看 URL 走云预签名: " + view);
            HttpResponse<byte[]> get = http.send(
                    HttpRequest.newBuilder(URI.create(view)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, get.statusCode(), "预签名 GET 须真实成功: " + get);
            assertArrayEquals(payload, get.body(), "读路径字节往返必须与写入一致（读写同源）");
        } finally {
            harness.cleanupAll();
        }
    }

    
    @Test
    void mode2项目_grant留证_读路径经反解走本地防漂移() throws Exception {
        String tag = harness.uniq();
        try {
            long projectId = harness.seedProject(2, "/rec-" + tag, tag);
            StorageGrant grant = harness.artifactStorage().grantExploreUpload(projectId, "dom", "snap.json");
            assertTrue(grant.key().startsWith("testpike/rec-" + tag + "/" + projectId
                    + "/explore/" + harness.ym() + "/"), "mode=2 key 前缀叠加: " + grant.key());
            assertTrue(grant.key().matches(".*/[0-9a-f]{8}-dom_snap\\.json"),
                    "文件名清洗段不变: " + grant.key());
            assertTrue(grant.url().contains("/storage/files/"),
                    "本地模式预签名须指向 admin 反代端点: " + grant.url());
            assertTrue(grant.url().contains("signature="), "带签 URL: " + grant.url());

            harness.assertLocalProviderRoundTrip(grant.key(), new byte[]{9, 8, 7},
                    "本地留证存在性确认须经本地 Provider（s3 上必不存在=漂回系统必红）", "查看 URL 须经反解走本地反代: ");
        } finally {
            harness.cleanupAll();
        }
    }

    
    @Test
    void 项目不存在_grant留证回退系统默认_零破坏() {
        StorageGrant grant = harness.artifactStorage().grantExploreUpload(999999902L, "screenshot", "legacy.png");
        harness.registerKey(grant.key());
        try {
            assertTrue(grant.key().matches("testpike/999999902/explore/\\d{6}/[0-9a-f]{8}-screenshot_legacy\\.png"),
                    "悬空项目留证 key 规范不变: " + grant.key());
            assertTrue(grant.url().startsWith(harness.urlExpectations().presignedPrefix()),
                    "回退系统 provider: " + grant.url());
        } finally {
            harness.cleanupAll();
        }
    }
}
