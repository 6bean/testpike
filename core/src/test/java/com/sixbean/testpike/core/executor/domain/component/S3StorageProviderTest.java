package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageProvider;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class S3StorageProviderTest {

    @Autowired
    private StorageProvider s3StorageProvider;

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 4, 5, 6};
    }

    private static String testKey(String name) {
        return "test/s3-provider/" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8) + "/" + name;
    }

    @Test
    void name_标识为s3() {
        assertEquals("s3", s3StorageProvider.name());
    }

    @Test
    void put后get字节回环一致_exists为真() {
        String key = testKey("roundtrip.png");
        byte[] content = pngBytes();

        s3StorageProvider.put(key, content, "image/png");

        assertTrue(s3StorageProvider.exists(key), "put 后对象必须真实存在");
        assertArrayEquals(content, s3StorageProvider.get(key), "get 回读与写入字节一致");
    }

    @Test
    void get不存在对象抛NOT_FOUND() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> s3StorageProvider.get(testKey("not-exist.bin")));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "不存在对象=40401 人话语义");
    }

    @Test
    void presignPut_真实直传后exists_且presignGet可下载一致() throws Exception {
        String key = testKey("direct.png");
        StorageGrant put = s3StorageProvider.presignPut(key, Duration.ofMinutes(15));
        assertEquals(key, put.key());
        assertTrue(put.url().contains("X-Amz-Signature"), "预签名 PUT URL 须带签名: " + put.url());

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(put.url()))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(pngBytes()))
                        .header("Content-Type", "image/png")
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "预签名 PUT 直传应成功: " + resp.body());
        assertTrue(s3StorageProvider.exists(key));

        StorageGrant get = s3StorageProvider.presignGet(key, Duration.ofMinutes(15));
        HttpResponse<byte[]> getResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(get.url())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, getResp.statusCode(), "预签名 GET 应可下载");
        assertArrayEquals(pngBytes(), getResp.body(), "下载内容与直传一致");
    }

    @Test
    void delete幂等_重复删除不抛_删除后exists为假() {
        String key = testKey("gone.txt");
        s3StorageProvider.put(key, "bye".getBytes(StandardCharsets.UTF_8), "text/plain");
        assertTrue(s3StorageProvider.exists(key));

        s3StorageProvider.delete(key);
        assertFalse(s3StorageProvider.exists(key), "delete 后对象应不存在");

        s3StorageProvider.delete(key);
    }

    @Test
    void list_按前缀返回且limit封顶() {
        String prefix = "test/s3-provider/list-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "/";
        s3StorageProvider.put(prefix + "a.txt", "A".getBytes(StandardCharsets.UTF_8), "text/plain");
        s3StorageProvider.put(prefix + "b.txt", "B".getBytes(StandardCharsets.UTF_8), "text/plain");

        List<String> all = s3StorageProvider.list(prefix, 10);
        assertTrue(all.contains(prefix + "a.txt") && all.contains(prefix + "b.txt"),
                "前缀下两对象都应列出: " + all);

        List<String> capped = s3StorageProvider.list(prefix, 1);
        assertEquals(1, capped.size(), "limit=1 封顶");
    }
}
