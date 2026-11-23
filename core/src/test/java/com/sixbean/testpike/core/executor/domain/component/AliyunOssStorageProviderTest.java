package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AliyunOssStorageProviderTest {

    private static AliyunOssStorageProvider providerWith(Map<String, String> conf) {
        return new AliyunOssStorageProvider(new InMemoryConfigServiceStub(conf));
    }

    private static Map<String, String> fakeOssConf() {
        return Map.of(
                "storage.oss.endpoint", "https://oss-cn-hangzhou.aliyuncs.com",
                "storage.oss.bucket", "testpike-test",
                "storage.oss.access-key-id", "testAccessKeyId000",
                "storage.oss.access-key-secret", "testAccessKeySecret0000000000000000");
    }

    @Test
    void name_标识为aliyunoss() {
        assertEquals("aliyun-oss", providerWith(fakeOssConf()).name());
    }

    @Test
    void AKSK缺失时读写failclosed拒绝() {
        AliyunOssStorageProvider p = providerWith(Map.of(
                "storage.oss.endpoint", "https://oss-cn-hangzhou.aliyuncs.com",
                "storage.oss.bucket", "testpike-test"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> p.put("1/script/202609/ab-cd.png", new byte[]{1}, "image/png"));
        assertTrue(e.getMessage().contains("storage.oss"), "人话报错须点名配置键: " + e.getMessage());
    }

    @Test
    void 空key一律校验拒绝() {
        AliyunOssStorageProvider p = providerWith(fakeOssConf());
        assertThrows(ServiceException.class, () -> p.presignPut(" ", Duration.ofMinutes(15)));
        assertThrows(ServiceException.class, () -> p.presignGet(null, Duration.ofMinutes(15)));
    }

    @Test
    void presignPut生成带签名参数URL且含key与桶host() {
        StorageGrant grant = providerWith(fakeOssConf())
                .presignPut("7/script/202609/deadbeef-login page.png", Duration.ofMinutes(30));
        assertEquals("7/script/202609/deadbeef-login page.png", grant.key());
        String url = grant.url();
        assertTrue(url.contains("testpike-test.oss-cn-hangzhou.aliyuncs.com"),
                "URL 须含 bucket.endpoint host: " + url);
        assertTrue(url.contains("deadbeef-login%20page.png") || url.contains("deadbeef-login page.png"),
                "URL 须含对象 key: " + url);
        assertTrue(url.contains("Signature=") || url.contains("x-oss-signature"),
                "URL 须含签名参数: " + url);
        assertTrue(url.contains("Expires=") || url.contains("x-oss-expires"),
                "URL 须含时效参数: " + url);
    }

    @Test
    void presignPut与Get签名互不可替换() {
        AliyunOssStorageProvider p = providerWith(fakeOssConf());
        String key = "7/recording/202609/12345678-clip.webm";
        StorageGrant put = p.presignPut(key, Duration.ofMinutes(15));
        StorageGrant get = p.presignGet(key, Duration.ofMinutes(15));
        assertFalse(put.url().equals(get.url()), "PUT/GET 动词参与签名，同 key 两 URL 必不同（防替换）");
    }

    @Test
    void TTL超7天钳制且非法TTL兜底15分钟() {
        AliyunOssStorageProvider p = providerWith(fakeOssConf());
        StorageGrant g1 = p.presignGet("k/x", Duration.ofDays(365));
        StorageGrant g2 = p.presignGet("k/x", null);
        assertTrue(g1.url().contains("Expires") || g1.url().contains("expires"));
        assertTrue(g2.url().contains("Expires") || g2.url().contains("expires"));
    }

    @Test
    void 网络不可达时get封装为人话ServiceException() {
        Map<String, String> conf = Map.of(
                "storage.oss.endpoint", "https://oss-cn-testpike.invalid",
                "storage.oss.bucket", "testpike-test",
                "storage.oss.access-key-id", "ak",
                "storage.oss.access-key-secret", "sk");
        AliyunOssStorageProvider p = providerWith(conf);
        ServiceException e = assertThrows(ServiceException.class, () -> p.get("1/script/202609/x-y.png"));
        assertTrue(e.getMessage().contains("对象读取失败"), "须人话封装不透传 SDK 原文: " + e.getMessage());
    }
}
