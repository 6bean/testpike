package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ArtifactGrantView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ArtifactStorageTest {

    @Autowired
    private ArtifactStorage artifactStorage;

    @Autowired
    private S3UrlExpectations urlExpectations;

    @Test
    void grantUpload_落StorageKeys规范key_返回预签名PUT地址() {
        ArtifactGrantView grant = artifactStorage.grantUpload(1001L, "screenshot", "step1.png");

        assertNotNull(grant);
        assertEquals(1001L, grant.taskId());
        assertTrue(grant.objectKey().matches("testpike/1001/exec-artifact/\\d{6}/[0-9a-f]{8}-screenshot_step1\\.png"),
                "objectKey 须为 StorageKeys 规范（prefix/taskId/scene/yyyyMM/uuid8-kind_name）: "
                        + grant.objectKey());
        assertTrue(grant.uploadUrl().startsWith(urlExpectations.presignedPrefix()),
                "预签名 URL 指向执行产物桶: " + grant.uploadUrl());
        assertTrue(grant.uploadUrl().contains("X-Amz-Signature"), "必须为签名 URL");
    }

    @Test
    void 预签名URL_真实PUT上传_对象存在性校验通过() throws Exception {
        ArtifactGrantView grant = artifactStorage.grantUpload(1002L, "screenshot", "login.png");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(grant.uploadUrl()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(png))
                        .header("Content-Type", "image/png")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(), "预签名 PUT 应成功: " + resp.body());
        assertTrue(artifactStorage.exists(grant.objectKey()), "上传后对象必须真实存在");
    }

    @Test
    void exists_对象不存在返回false() {
        assertTrue(!artifactStorage.exists("exec/999999/not-exist.png"));
    }

    @Test
    void 旧exec前缀存量key_直通可写可读可下载() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 5, 5, 5};
        String legacyKey = "exec/1001/screenshot/legacy_" + System.nanoTime() + ".png";
        artifactStorage.putBytes(legacyKey, png, "image/png");
        assertTrue(artifactStorage.exists(legacyKey), "旧前缀 key 写入后必须存在");

        String viewUrl = artifactStorage.grantViewUrl(legacyKey);
        HttpResponse<byte[]> getResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(viewUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, getResp.statusCode(), "旧 key 预签名 GET 应可下载: " + viewUrl);
        assertArrayEquals(png, getResp.body());

        artifactStorage.deleteQuietly(legacyKey);
        assertTrue(!artifactStorage.exists(legacyKey), "deleteQuietly 对旧 key 同样直通生效");
    }

    @Test
    void grantViewUrl_返回预签名GET地址_可真实下载上传对象() throws Exception {
        ArtifactGrantView grant = artifactStorage.grantUpload(1003L, "screenshot", "view.png");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7, 6};
        HttpResponse<String> putResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(grant.uploadUrl()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(png))
                        .header("Content-Type", "image/png")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResp.statusCode(), "预签名 PUT 应成功");

        String viewUrl = artifactStorage.grantViewUrl(grant.objectKey());
        assertNotNull(viewUrl);
        assertTrue(viewUrl.startsWith(urlExpectations.presignedPrefix()),
                "预览 URL 指向执行产物桶: " + viewUrl);
        assertTrue(viewUrl.contains("X-Amz-Signature"), "必须为签名 URL");

        HttpResponse<byte[]> getResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(viewUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, getResp.statusCode(), "预签名 GET 应可下载截图");
        assertArrayEquals(png, getResp.body(), "下载内容与上传一致（截图查看真实可用）");
    }
}
