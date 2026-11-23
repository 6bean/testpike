package com.sixbean.testpike.core.storage.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.component.S3StorageProvider;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeResult;
import com.sixbean.testpike.core.storage.domain.service.impl.StorageProbeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class StorageProbeServiceTest {

    @Autowired
    private StorageProbeService storageProbeService;

    @Autowired
    private S3StorageProvider s3Provider;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int configRowCountBefore;

    @BeforeEach
    void captureConfigRowCount() {
        configCacheHolder.loadAll(jdbcTemplate);
        configRowCountBefore = countStorageConfigRows();
        assertTrue(configRowCountBefore >= 8,
                "storage.% 八键必须已落库（V37 前置；缺键=先执行迁移脚本再跑测）");
    }

    @AfterEach
    void verifyNothingPersisted() {
        assertEquals(configRowCountBefore, countStorageConfigRows(),
                "probe 不得写 tp_sys_config（02 契约：不落库，保存前试）");
    }

    private int countStorageConfigRows() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_sys_config WHERE config_key LIKE 'storage.%'", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void provider枚举外_400人话含非法值与可选值() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> storageProbeService.probe(new StorageProbeRequest(
                        "hdfs", null, null, null, null, null)));
        assertTrue(e.getMessage().contains("hdfs"), "报错须含非法值: " + e.getMessage());
        assertTrue(e.getMessage().contains("local") && e.getMessage().contains("s3"),
                "报错须提示可选值: " + e.getMessage());
    }

    @Test
    void provider为空_400人话() {
        assertThrows(ServiceException.class, () -> storageProbeService.probe(
                new StorageProbeRequest(null, null, null, null, null, null)));
        assertThrows(ServiceException.class, () -> storageProbeService.probe(
                new StorageProbeRequest("  ", null, null, null, null, null)));
    }

    @Test
    void local模式_真实磁盘往返成功且探针清理() throws Exception {
        Path root = Files.createTempDirectory("tp-probe-local");
        StorageProbeResult result = storageProbeService.probe(new StorageProbeRequest(
                "local", root.toString(), null, null, null, null));
        assertTrue(result.success(), "本地连通测试应成功: " + result.message());
        assertEquals("local", result.provider());
        assertNotNull(result.message());
        try (var walk = Files.walk(root)) {
            List<Path> leftovers = walk.filter(Files::isRegularFile).toList();
            assertTrue(leftovers.isEmpty(), "探针对象未清理: " + leftovers);
        }
    }

    @Test
    void s3模式_真实活体往返成功且对象清理() {
        StorageProbeResult result = storageProbeService.probe(new StorageProbeRequest(
                "s3", null, null, null, null, null));
        assertTrue(result.success(), "S3 活体连通测试应成功: " + result.message());
        assertEquals("s3", result.provider());
        assertNotNull(result.probeKey(), "成功结果须回显探针对象 key 供追溯");
        assertFalse(s3Provider.exists(result.probeKey()),
                "探针对象应已删除: " + result.probeKey());
    }

    @Test
    void oss假凭据_失败分类人话不落库() {
        StorageProbeResult result = storageProbeService.probe(new StorageProbeRequest(
                "aliyun-oss", null,
                "https://oss-cn-test.invalid", "tp-nonexistent-bucket",
                "LTAI-fake-access-key", "fake-secret-key"));
        assertFalse(result.success());
        assertEquals("aliyun-oss", result.provider());
        assertEquals(StorageProbeResult.CATEGORY_NETWORK, result.category(),
                "实际: " + result.category() + " / " + result.message());
        assertTrue(result.message().contains("网络"), "失败信息须人话含分类: " + result.message());
    }

    @Test
    void oss缺凭据_失败分类鉴权指键名() {
        StorageProbeResult blank = storageProbeService.probe(new StorageProbeRequest(
                "aliyun-oss", null, "https://oss-cn-hangzhou.aliyuncs.com", "bkt", "", ""));
        assertFalse(blank.success());
        assertEquals(StorageProbeResult.CATEGORY_AUTH, blank.category(),
                "缺凭据=鉴权分类: " + blank.category() + " / " + blank.message());
        assertTrue(blank.message().contains("storage.oss.access-key-id"),
                "缺凭据须指键名: " + blank.message());
    }

    @Test
    void 错误分类器_鉴权桶网络三段映射() {
        assertEquals(StorageProbeResult.CATEGORY_AUTH, StorageProbeServiceImpl.classify(
                "OSS 错误: InvalidAccessKeyId: The AK, v4, that you provided does not exist."));
        assertEquals(StorageProbeResult.CATEGORY_AUTH, StorageProbeServiceImpl.classify(
                "AccessDenied (403) signature mismatch"));
        assertEquals(StorageProbeResult.CATEGORY_BUCKET, StorageProbeServiceImpl.classify(
                "NoSuchBucket: bucket testpike-exec not found"));
        assertEquals(StorageProbeResult.CATEGORY_NETWORK, StorageProbeServiceImpl.classify(
                "java.net.UnknownHostException: oss-cn-test.invalid"));
        assertEquals(StorageProbeResult.CATEGORY_NETWORK, StorageProbeServiceImpl.classify(
                "Connect to endpoint failed: Connection refused"));
        assertEquals(StorageProbeResult.CATEGORY_UNKNOWN, StorageProbeServiceImpl.classify(
                "some unexpected sdk failure"));
    }

    @Test
    void 探针对象key符合规范且用系统级项目0() {
        String key = StorageKeys.build("tp", 0L, "exec-artifact", "probe.txt");
        assertTrue(key.matches("tp/0/exec-artifact/\\d{6}/[0-9a-f]{8}-probe\\.txt"),
                "实际 key=" + key);
    }
}
