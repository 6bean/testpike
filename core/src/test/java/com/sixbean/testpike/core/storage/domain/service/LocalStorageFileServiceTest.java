package com.sixbean.testpike.core.storage.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.executor.domain.component.InMemoryConfigServiceStub;
import com.sixbean.testpike.core.executor.domain.component.S3StorageProvider;
import com.sixbean.testpike.core.executor.domain.component.AliyunOssStorageProvider;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.storage.domain.service.impl.LocalStorageFileServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class LocalStorageFileServiceTest {

    @Autowired
    private S3StorageProvider s3Provider;
    @Autowired
    private AliyunOssStorageProvider ossProvider;

    private LocalStorageFileServiceImpl serviceWith(Path root) {
        StorageRouter router = new StorageRouter(new InMemoryConfigServiceStub(
                        Map.of("storage.local-root", root.toString())),
                s3Provider, ossProvider,
                "http://localhost:8081/storage/files", "test-sign-secret-32-bytes-0000");
        return new LocalStorageFileServiceImpl(router);
    }

    
    private static long expiresOf(String url) {
        return Long.parseLong(paramOf(url, "expires"));
    }

    private static String signatureOf(String url) {
        return paramOf(url, "signature");
    }

    private static String paramOf(String url, String name) {
        int from = url.indexOf(name + "=") + name.length() + 1;
        int end = url.indexOf('&', from);
        return end < 0 ? url.substring(from) : url.substring(from, end);
    }

    @Test
    void 合法GET签名下载读回字节() throws Exception {
        Path root = Files.createTempDirectory("tp-lfs-get");
        LocalStorageFileServiceImpl service = serviceWith(root);
        LocalStorageProvider provider = service.provider();
        byte[] content = "download-me".getBytes(StandardCharsets.UTF_8);
        provider.put("2/script/202609/aabb1122-a.txt", content, "text/plain");

        StorageGrant grant = provider.presignGet("2/script/202609/aabb1122-a.txt",
                Duration.ofMinutes(15));
        byte[] back = service.download("2/script/202609/aabb1122-a.txt",
                expiresOf(grant.url()), signatureOf(grant.url()));
        assertArrayEquals(content, back);
    }

    @Test
    void 篡改签名与过期一律403() throws Exception {
        Path root = Files.createTempDirectory("tp-lfs-deny");
        LocalStorageFileServiceImpl service = serviceWith(root);
        LocalStorageProvider provider = service.provider();
        provider.put("3/recording/202609/ccdd3344-b.webm", new byte[]{1}, null);
        StorageGrant grant = provider.presignGet("3/recording/202609/ccdd3344-b.webm",
                Duration.ofMinutes(15));
        long exp = expiresOf(grant.url());
        String sig = signatureOf(grant.url());

        ServiceException tamper = assertThrows(ServiceException.class,
                () -> service.download("3/recording/202609/ccdd3344-b.webm", exp, "deadbeef"));
        assertEquals403(tamper);
        ServiceException expired = assertThrows(ServiceException.class,
                () -> service.download("3/recording/202609/ccdd3344-b.webm", exp - 120, sig));
        assertEquals403(expired);
    }

    @Test
    void PUT签名不能用于GET下载() throws Exception {
        Path root = Files.createTempDirectory("tp-lfs-verb1");
        LocalStorageFileServiceImpl service = serviceWith(root);
        LocalStorageProvider provider = service.provider();
        provider.put("4/import-export/202609/eeff5566-c.csv", new byte[]{2}, "text/csv");
        StorageGrant putGrant = provider.presignPut("4/import-export/202609/eeff5566-c.csv",
                Duration.ofMinutes(15));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.download("4/import-export/202609/eeff5566-c.csv",
                        expiresOf(putGrant.url()), signatureOf(putGrant.url())));
        assertEquals403(e);
    }

    @Test
    void PUT签名直传真实落盘且GET签名不能上传() throws Exception {
        Path root = Files.createTempDirectory("tp-lfs-verb2");
        LocalStorageFileServiceImpl service = serviceWith(root);
        LocalStorageProvider provider = service.provider();
        byte[] payload = "uploaded-bytes".getBytes(StandardCharsets.UTF_8);
        StorageGrant putGrant = provider.presignPut("5/artifact/202609/0011aabb-d.zip",
                Duration.ofMinutes(15));

        service.upload("5/artifact/202609/0011aabb-d.zip",
                expiresOf(putGrant.url()), signatureOf(putGrant.url()), payload);
        assertArrayEquals(payload,
                Files.readAllBytes(root.resolve("5/artifact/202609/0011aabb-d.zip")),
                "直传必须真实落盘（禁内存假实现）");

        provider.put("5/artifact/202609/0011aabb-d.zip", payload, null);
        StorageGrant getGrant = provider.presignGet("5/artifact/202609/0011aabb-d.zip",
                Duration.ofMinutes(15));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.upload("5/artifact/202609/0011aabb-d.zip",
                        expiresOf(getGrant.url()), signatureOf(getGrant.url()),
                        "overwrite".getBytes(StandardCharsets.UTF_8)));
        assertEquals403(e);
        assertArrayEquals(payload, provider.get("5/artifact/202609/0011aabb-d.zip"),
                "被拒的上传不得改动存量字节");
    }

    @Test
    void 穿越key400人话() throws Exception {
        Path root = Files.createTempDirectory("tp-lfs-traverse");
        LocalStorageFileServiceImpl service = serviceWith(root);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.download("../../etc/passwd", 9999999999L, "abcd"));
        assertTrue(e.getMessage().contains("越界") || e.getMessage().contains("非法"),
                "报错须人话指越界: " + e.getMessage());
        assertThrows(ServiceException.class,
                () -> service.upload("/abs/escape", 9999999999L, "abcd", new byte[]{1}));
    }

    private static void assertEquals403(ServiceException e) {
        assertEquals(40301, e.getCode().intValue(), "拒绝须 40301（HTTP 403 语义）: " + e.getMessage());
    }
}
