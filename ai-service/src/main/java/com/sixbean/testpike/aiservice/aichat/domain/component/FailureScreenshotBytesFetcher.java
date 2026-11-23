package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Downloads the raw bytes of one failure-scene screenshot artifact through its
 * presigned GET url .
 * <p>Why bytes instead of handing the url to the provider: the presigned url
 * points at the deployment's own storage host (e.g. localhost S3 on the
 * dev network). Cloud vision providers fetch images server-side and cannot
 * reach that host — Dashscope rejects the url with 400 "The provided URL does
 * not appear to be valid" (live-proven ). The platform-proven working
 * shape is the base64 data url inlined by VisualLocateServiceImpl, so this
 * component fetches the artifact once locally and the caller inlines it.
 * <p>Fail-closed posture: a non-2xx status, an over-cap body, or an IO failure
 * all raise a RuntimeException with an operator-readable Chinese reason — the
 * caller degrades to the text-only answer, never a partial image.
 */
@Component
public class FailureScreenshotBytesFetcher {

    /** Connect bound for the storage-host GET (enhancement leg: fail fast). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Whole-exchange bound covering download of a capped body. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** Stream read chunk — the cap is enforced while reading, not after. */
    private static final int READ_CHUNK = 64 * 1024;

    private final HttpClient httpClient;

    public FailureScreenshotBytesFetcher() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    FailureScreenshotBytesFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * GET the url and return the body bytes.
     *
     * @param url      presigned GET url issued by core's F-8 door
     * @param maxBytes hard size cap; an oversized body is rejected, not sent
     */
    public byte[] fetch(String url, int maxBytes) {
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("截图下载被中断", interrupted);
        } catch (IOException ioFailure) {
            throw new IllegalStateException("截图下载失败：" + ioFailure.getMessage(), ioFailure);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("截图下载失败：存储服务返回状态码 "
                        + response.statusCode() + "（预签名地址可能已过期，有效期 15 分钟）");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[READ_CHUNK];
            int read;
            int total = 0;
            while ((read = body.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IllegalStateException("截图超过大小上限 " + maxBytes
                            + " 字节，已放弃读图，可在系统设置调小或提高上限后重试");
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException ioFailure) {
            throw new IllegalStateException("截图读取失败：" + ioFailure.getMessage(), ioFailure);
        }
    }
}
