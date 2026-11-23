package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.common.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class StorageRouterTest {

    @Autowired
    private S3StorageProvider s3Provider;
    @Autowired
    private AliyunOssStorageProvider ossProvider;

    private StorageRouter routerWith(Map<String, String> configValues) {
        return new StorageRouter(new InMemoryConfigServiceStub(configValues),
                s3Provider, ossProvider,
                "http://localhost:8081/storage/files", "test-sign-secret");
    }

    @Test
    void 系统默认模式_解析到storage_provider配置的s3() {
        StorageRouter router = routerWith(Map.of("storage.provider", "s3"));
        assertEquals("s3", router.route(1, null).provider().name());
        assertEquals("s3", router.systemProvider().name());
    }

    @Test
    void 项目强制本地_mode2落local真实磁盘读写() throws Exception {
        Path root = Files.createTempDirectory("tp-router-local");
        StorageRouter router = routerWith(Map.of(
                "storage.provider", "s3",
                "storage.local-root", root.toString()));
        StorageRoute route = router.route(2, null);
        assertEquals("local", route.provider().name());
        byte[] bytes = "router-test".getBytes();
        route.provider().put("9/import-export/202609/abcd-file.txt", bytes, "text/plain");
        assertArrayEquals(bytes, route.provider().get("9/import-export/202609/abcd-file.txt"));
        assertTrue(Files.exists(root.resolve("9/import-export/202609/abcd-file.txt")));
    }

    @Test
    void localProvider公开访问器_反代文件端点消费真实磁盘() throws Exception {
        Path root = Files.createTempDirectory("tp-router-accessor");
        StorageRouter router = routerWith(Map.of("storage.local-root", root.toString()));
        assertInstanceOf(LocalStorageProvider.class, router.localProvider());
        byte[] bytes = "accessor".getBytes();
        router.localProvider().put("1/script/202609/ab12-f.txt", bytes, "text/plain");
        assertTrue(Files.exists(root.resolve("1/script/202609/ab12-f.txt")), "必须真实落盘");
        assertInstanceOf(LocalStorageProvider.class, router.localProvider(),
                "二次调用返回同实例（懒装配缓存）");
    }

    @Test
    void 冲突回退_mode3云但系统local_回退默认() throws Exception {
        Path root = Files.createTempDirectory("tp-router-conflict");
        StorageRouter router = routerWith(Map.of(
                "storage.provider", "local",
                "storage.local-root", root.toString()));
        assertEquals("local", router.route(3, null).provider().name());
    }

    @Test
    void 云模式_系统provider为s3时mode3落s3() {
        StorageRouter router = routerWith(Map.of("storage.provider", "s3"));
        assertEquals("s3", router.route(3, null).provider().name());
    }

    @Test
    void 云模式_系统provider为aliyun_oss时mode3落oss且mode1同源() {
        StorageRouter router = routerWith(Map.of("storage.provider", "aliyun-oss"));
        assertEquals("aliyun-oss", router.route(3, null).provider().name());
        assertEquals("aliyun-oss", router.route(null, null).provider().name());
    }

    @Test
    void provider枚举外_400人话含非法值() {
        StorageRouter router = routerWith(Map.of("storage.provider", "hdfs"));
        ServiceException e = assertThrows(ServiceException.class, () -> router.route(1, null));
        assertTrue(e.getMessage().contains("hdfs"), "报错须含非法值: " + e.getMessage());
        assertTrue(e.getMessage().contains("local"), "报错须提示可选值: " + e.getMessage());
    }

    @Test
    void storage_mode非法值_400人话() {
        StorageRouter router = routerWith(Map.of("storage.provider", "s3"));
        ServiceException e = assertThrows(ServiceException.class, () -> router.route(9, null));
        assertTrue(e.getMessage().contains("9"), "报错须含非法值: " + e.getMessage());
    }

    @Test
    void 本地根目录未配置_fail_closed人话指键名() {
        StorageRouter router = routerWith(Map.of(
                "storage.provider", "s3", "storage.local-root", ""));
        ServiceException e = assertThrows(ServiceException.class, () -> router.route(2, null));
        assertTrue(e.getMessage().contains("storage.local-root"),
                "须指缺哪个键: " + e.getMessage());
    }

    @Test
    void 预签名时效与key前缀_键组单一来源() {
        assertEquals(Duration.ofMinutes(15),
                routerWith(Map.of("storage.provider", "s3")).urlTtl());
        assertEquals(Duration.ofMinutes(30),
                routerWith(Map.of("storage.provider", "s3", "storage.url-ttl", "30")).urlTtl());
        assertEquals(Duration.ofMinutes(1),
                routerWith(Map.of("storage.provider", "s3", "storage.url-ttl", "0")).urlTtl());
        assertEquals(Duration.ofMinutes(7 * 24 * 60),
                routerWith(Map.of("storage.provider", "s3", "storage.url-ttl", "99999")).urlTtl());
        assertEquals(Duration.ofMinutes(15),
                routerWith(Map.of("storage.provider", "s3", "storage.url-ttl", "abc")).urlTtl());
        assertEquals("testpike",
                routerWith(Map.of("storage.provider", "s3",
                        "storage.key-prefix", "testpike")).keyPrefix());
        assertEquals("", routerWith(Map.of("storage.provider", "s3")).keyPrefix());
    }

    @Test
    void 项目自定义路径_规范化去斜杠_穿越拒绝400() {
        StorageRouter router = routerWith(Map.of("storage.provider", "s3"));
        assertEquals("rec-2", router.route(1, "/rec-2").prefix());
        assertEquals("rec-2", router.route(1, "rec-2/").prefix());
        assertEquals("", router.route(1, null).prefix());
        assertThrows(ServiceException.class, () -> router.route(1, "../etc/passwd"));
        assertThrows(ServiceException.class, () -> router.route(1, "a/../../b"));
    }

    @Test
    void 本地provider_键热更改root后重建生效() throws Exception {
        Path rootA = Files.createTempDirectory("tp-router-hot-a");
        Path rootB = Files.createTempDirectory("tp-router-hot-b");
        ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
        values.put("storage.provider", "s3");
        values.put("storage.local-root", rootA.toString());
        StorageRouter router = routerWith(values);
        StorageProvider first = router.route(2, null).provider();
        assertInstanceOf(LocalStorageProvider.class, first);
        values.put("storage.local-root", rootB.toString());
        StorageProvider second = router.route(2, null).provider();
        second.put("1/script/202609/aa-hot.txt", "x".getBytes(), "text/plain");
        assertTrue(Files.exists(rootB.resolve("1/script/202609/aa-hot.txt")),
                "热更改 root 后必须落新目录");
    }
}
