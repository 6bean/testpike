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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool-result artifact storage grant test . Verbatim tool output
 * of an oversized dispatcher backfill is internal AI-session data, not a
 * project artifact, so the grant mirrors the judge-artifact precedent: system
 * level, project segment 0, always the system default provider. Assertions:
 * 1) canonical key = {global prefix}/0/tool-result/{yyyyMM}/{uuid8}-{fileName};
 * 2) real PUT -> exists -> view url -> GET byte round-trip identical (read and
 * write share one route: a project-0 key resolves to no project setting and
 * falls back to the system default, same provider the write used — pit 50);
 * 3) blank file name falls back to a default stem instead of a rejected grant.
 * Real S3 through the dev stack, no mocks; objects cleaned in finally.
 */
@SpringBootTest(classes = CoreApplication.class)
class ArtifactStorageToolResultGrantTest {

    @Autowired
    private ArtifactStorage artifactStorage;

    @Autowired
    private S3UrlExpectations urlExpectations;

    private static String ym() {
        return java.time.YearMonth.now().toString().replace("-", "");
    }

    private static String tag() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong(1L << 60));
    }

    @Test
    void toolResultGrant_isSystemLevelKeyAndRoundTripsThroughRealStorage() throws Exception {
        String t = tag();
        String name = "task42-" + t + ".txt";
        StorageGrant grant = artifactStorage.grantToolResultUpload(name);
        try {
            assertTrue(grant.key().startsWith("testpike/0/tool-result/" + ym() + "/"),
                    "system-level canonical key (project segment 0): " + grant.key());
            assertTrue(grant.key().matches(".*/[0-9a-f]{8}-task42-" + t + "\\.txt"),
                    "file name segment carries the task anchor untouched: " + grant.key());
            assertTrue(grant.url().startsWith(urlExpectations.presignedPrefix()),
                    "system default provider presign target: " + grant.url());

            byte[] payload = ("工具全文-" + t).getBytes(StandardCharsets.UTF_8);
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<byte[]> put = http.send(
                    HttpRequest.newBuilder(URI.create(grant.url()))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, put.statusCode(), "presigned PUT must really succeed: " + put);

            assertTrue(artifactStorage.exists(grant.key()),
                    "exists must resolve the project-0 key back to the same system route");
            String view = artifactStorage.grantViewUrl(grant.key());
            HttpResponse<byte[]> get = http.send(
                    HttpRequest.newBuilder(URI.create(view)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, get.statusCode(), "presigned GET must really succeed");
            assertArrayEquals(payload, get.body(),
                    "stored bytes must round-trip verbatim for the retrieval meta-tool");
        } finally {
            artifactStorage.deleteQuietly(grant.key());
        }
    }

    @Test
    void toolResultGrant_blankFileNameFallsBackToDefaultStem() {
        StorageGrant grant = artifactStorage.grantToolResultUpload("  ");
        try {
            assertTrue(grant.key().matches("testpike/0/tool-result/\\d{6}/[0-9a-f]{8}-result\\.txt"),
                    "blank name degrades to the default stem, never a rejected grant: "
                            + grant.key());
        } finally {
            artifactStorage.deleteQuietly(grant.key());
        }
    }
}
