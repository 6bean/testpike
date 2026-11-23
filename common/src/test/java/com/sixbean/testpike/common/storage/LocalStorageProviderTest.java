package com.sixbean.testpike.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LocalStorageProviderTest {

    @TempDir
    Path root;

    private LocalStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalStorageProvider(root.toString(), "http://localhost:8081/storage/files",
                "test-secret-32-bytes-long-0000000");
    }

    @Test
    void put后get读回一致_且真实落盘() {
        byte[] content = "hello-local".getBytes(StandardCharsets.UTF_8);
        provider.put("1/script/202609/abcd1234-a.txt", content, "text/plain");
        assertArrayEquals(content, provider.get("1/script/202609/abcd1234-a.txt"));
        assertTrue(root.resolve("1/script/202609/abcd1234-a.txt").toFile().exists(),
                "必须真实落盘（禁内存假实现）");
    }

    @Test
    void exists与delete幂等() {
        provider.put("3/recording/202609/ab12-c.webm", new byte[]{1, 2}, null);
        assertTrue(provider.exists("3/recording/202609/ab12-c.webm"));
        provider.delete("3/recording/202609/ab12-c.webm");
        assertFalse(provider.exists("3/recording/202609/ab12-c.webm"));
        provider.delete("3/recording/202609/ab12-c.webm");
    }

    @Test
    void 越界key路径穿越被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> provider.get("../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> provider.put("/abs/escape", new byte[]{1}, null));
    }

    @Test
    void list按前缀返回且limit封顶() {
        for (int i = 0; i < 5; i++) {
            provider.put("7/import-export/202609/0000000" + i + "-f.csv", new byte[]{(byte) i}, "text/csv");
        }
        List<String> all = provider.list("7/import-export/202609/", 10);
        assertEquals(5, all.size());
        assertEquals(2, provider.list("7/import-export/202609/", 2).size());
    }

    @Test
    void presignGet_合法签名校验通过_篡改与过期拒绝() {
        provider.put("2/skill-pack/202609/ff00ff00-p.zip", new byte[]{9}, null);
        StorageGrant grant = provider.presignGet("2/skill-pack/202609/ff00ff00-p.zip",
                Duration.ofMinutes(15));
        assertTrue(grant.url().startsWith("http://localhost:8081/storage/files/2/skill-pack/"));
        long exp = Long.parseLong(grant.url().substring(grant.url().indexOf("expires=") + 8,
                grant.url().indexOf('&', grant.url().indexOf("expires="))));
        String sig = grant.url().substring(grant.url().indexOf("signature=") + 10);
        assertTrue(provider.verifySignedUrl("2/skill-pack/202609/ff00ff00-p.zip", exp, sig),
                "未过期未篡改必须放行");
        assertFalse(provider.verifySignedUrl("2/skill-pack/202609/ff00ff00-p.zip", exp, "deadbeef"),
                "篡改签名拒绝");
        assertFalse(provider.verifySignedUrl("2/skill-pack/202609/ff00ff00-p.zip", exp - 60, sig),
                "换 key 拒绝");
        assertFalse(provider.verifySignedUrl("3/skill-pack/202609/ff00ff00-p.zip", exp, sig));
        assertFalse(provider.verifySignedUrl("2/skill-pack/202609/ff00ff00-p.zip",
                System.currentTimeMillis() / 1000 - 1, sig), "过期拒绝");
    }

    @Test
    void 签名动词互斥_put签名不能GET用_get签名不能PUT用() {
        StorageGrant putGrant = provider.presignPut("4/recording/202609/aa11-b.webm",
                Duration.ofMinutes(15));
        long putExp = Long.parseLong(parseParam(putGrant.url(), "expires"));
        String putSig = parseParam(putGrant.url(), "signature");
        assertTrue(provider.verifySignedUrl("PUT", "4/recording/202609/aa11-b.webm", putExp, putSig),
                "PUT 签名对 PUT 校验必须放行");
        assertFalse(provider.verifySignedUrl("GET", "4/recording/202609/aa11-b.webm", putExp, putSig),
                "PUT 签名换 GET 动词必须拒绝");

        provider.put("4/recording/202609/aa11-b.webm", new byte[]{1}, null);
        StorageGrant getGrant = provider.presignGet("4/recording/202609/aa11-b.webm",
                Duration.ofMinutes(15));
        long getExp = Long.parseLong(parseParam(getGrant.url(), "expires"));
        String getSig = parseParam(getGrant.url(), "signature");
        assertTrue(provider.verifySignedUrl("GET", "4/recording/202609/aa11-b.webm", getExp, getSig),
                "GET 签名对 GET 校验必须放行（旧三参口径同源）");
        assertFalse(provider.verifySignedUrl("PUT", "4/recording/202609/aa11-b.webm", getExp, getSig),
                "GET 签名换 PUT 动词必须拒绝");
    }

    
    private static String parseParam(String url, String name) {
        String marker = name + "=";
        int from = url.indexOf(marker) + marker.length();
        int end = url.indexOf('&', from);
        return end < 0 ? url.substring(from) : url.substring(from, end);
    }
}
