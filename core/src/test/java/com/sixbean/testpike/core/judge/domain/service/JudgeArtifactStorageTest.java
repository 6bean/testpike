package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.judge.api.dto.JudgeArtifactGrantView;
import com.sixbean.testpike.core.judge.domain.service.JudgeRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeArtifactStorageTest {

    @Autowired
    private JudgeRecordService judgeRecordService;

    @Autowired
    private ArtifactStorage artifactStorage;

    private static String ym() {
        return java.time.YearMonth.now().toString().replace("-", "");
    }

    @Test
    void 工件授权_key规范且真实PUT与view字节往返一致() throws Exception {
        long runId = 900000000L + ThreadLocalRandom.current().nextInt(1000);
        long sampleId = 910000000L + ThreadLocalRandom.current().nextInt(1000);
        JudgeArtifactGrantView grant = judgeRecordService.artifactGrant(runId, sampleId);
        try {
            assertTrue(grant.objectKey().startsWith("testpike/0/judge-artifact/" + ym() + "/"),
                    "系统级规范 key（项目位=0）: " + grant.objectKey());
            assertTrue(grant.objectKey().matches(".*/[0-9a-f]{8}-run-" + runId + "-sample-"
                    + sampleId + "\\.json"), "文件名锚段=run/sample 语义可归因: " + grant.objectKey());

            byte[] payload = ("{\"content\":\"裁判原始回包-" + runId + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<byte[]> put = http.send(
                    HttpRequest.newBuilder(URI.create(grant.uploadUrl()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, put.statusCode(), "预签名 PUT 须真实成功: " + put);

            assertTrue(artifactStorage.exists(grant.objectKey()),
                    "exists 经 0 位 key 反解回退系统默认=与写侧同路由（读写同源）");
            String view = artifactStorage.grantViewUrl(grant.objectKey());
            HttpResponse<byte[]> get = http.send(
                    HttpRequest.newBuilder(URI.create(view)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, get.statusCode(), "预签名 GET 须真实成功");
            assertArrayEquals(payload, get.body(), "工件字节往返必须一致（人审回读原文）");
        } finally {
            artifactStorage.deleteQuietly(grant.objectKey());
        }
    }

    @Test
    void 非正runId或sampleId_failClosed人话拒() {
        assertThrows(ValidationException.class,
                () -> judgeRecordService.artifactGrant(0L, 11L));
        assertThrows(ValidationException.class,
                () -> judgeRecordService.artifactGrant(5L, -1L));
    }
}
