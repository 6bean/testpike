package com.sixbean.testpike.core.storage.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.LocalStorageProvider;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.executor.domain.component.AliyunOssStorageProvider;
import com.sixbean.testpike.core.executor.domain.component.S3StorageProvider;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigInput;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class StorageConfigServiceTest {

    @Autowired
    private StorageConfigService storageConfigService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private ConfigCipher configCipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** [config_key, config_value, group_name, value_type, encrypt_flag, description] */
    private List<Object[]> snapshot;

    
    private String originalProvider;

    @BeforeEach
    void snapshotStorageKeys() {
        snapshot = jdbcTemplate.query("""
                SELECT config_key, config_value, group_name, value_type, encrypt_flag, description
                FROM tp_sys_config WHERE config_key LIKE 'storage.%'
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getString(6)});
        originalProvider = snapshotProvider();
        assertTrue(originalProvider != null && !originalProvider.isBlank(),
                "storage.provider 键必须存在（V37 落库前置；缺键=先执行迁移脚本再跑测）");
    }

    @AfterEach
    void restoreStorageKeys() {
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key LIKE 'storage.%'");
        for (Object[] row : snapshot) {
            jdbcTemplate.update("""
                    INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type,
                        encrypt_flag, description) VALUES (?, ?, ?, ?, ?, ?)
                    """, row);
        }
        configCacheHolder.loadAll(jdbcTemplate);
    }

    private static StorageConfigInput onlyProvider(String provider) {
        return new StorageConfigInput(provider, null, null, null, null, null, null, null);
    }


    @Test
    void provider枚举外_400人话含值与可选值() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> storageConfigService.update(1L, onlyProvider("hdfs")));
        assertTrue(e.getMessage().contains("hdfs"), "报错须含非法值: " + e.getMessage());
        assertTrue(e.getMessage().contains(LocalStorageProvider.NAME)
                        && e.getMessage().contains(S3StorageProvider.NAME)
                        && e.getMessage().contains(AliyunOssStorageProvider.NAME),
                "报错须含全部可选值: " + e.getMessage());
        assertEquals(originalProvider, snapshotProvider(), "拒绝后实配不得被改");
    }

    @Test
    void provider空串_400() {
        assertThrows(ServiceException.class,
                () -> storageConfigService.update(1L, onlyProvider("  ")));
    }


    @Test
    void 选aliyun_oss缺endpoint与bucket_400指键名() {
        StorageConfigInput input = new StorageConfigInput(AliyunOssStorageProvider.NAME,
                null, null, null, "", "", null, null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> storageConfigService.update(1L, input));
        assertTrue(e.getMessage().contains("storage.oss.endpoint"), e.getMessage());
        assertTrue(e.getMessage().contains("storage.oss.bucket"), e.getMessage());
    }

    @Test
    void 选aliyun_oss缺AK_SK_400指键名() {
        StorageConfigInput input = new StorageConfigInput(AliyunOssStorageProvider.NAME,
                null, null, null, "https://oss-cn-hangzhou.aliyuncs.com", "tp-bucket", "", "");
        ServiceException e = assertThrows(ServiceException.class,
                () -> storageConfigService.update(1L, input));
        assertTrue(e.getMessage().contains("storage.oss.access-key-id"), e.getMessage());
        assertTrue(e.getMessage().contains("storage.oss.access-key-secret"), e.getMessage());
    }

    @Test
    void 选local缺根目录_400指键名() {
        StorageConfigInput input = new StorageConfigInput(LocalStorageProvider.NAME,
                null, null, "  ", null, null, null, null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> storageConfigService.update(1L, input));
        assertTrue(e.getMessage().contains("storage.local-root"), e.getMessage());
    }


    @Test
    void SK写入后GET打码_DB存密文可解密() {
        storageConfigService.update(1L, new StorageConfigInput(null, null, null, null,
                null, null, "ak-real-001", "sk-real-secret-987"));
        StorageConfigView view = storageConfigService.get();
        assertEquals("******", view.ossAccessKeySecret(), "GET 须打码");
        assertEquals("******", view.ossAccessKeyId(), "AK 加密位同为敏感");
        String stored = storedValue("storage.oss.access-key-secret");
        assertNotEquals("sk-real-secret-987", stored);
        assertTrue(!stored.contains("sk-real-secret-987"), "DB 禁明文");
        assertEquals("sk-real-secret-987", configCipher.decrypt(stored), "AES 真实往返");
    }

    @Test
    void MASK回显提交_不得覆盖密文() {
        String before = storedValue("storage.oss.access-key-secret");
        storageConfigService.update(1L, new StorageConfigInput(null, null, null, null,
                null, null, null, "******"));
        assertEquals(before, storedValue("storage.oss.access-key-secret"),
                "前端回显 ****** 提交必须视为不变更（防清掉已存凭据）");
    }


    @Test
    void provider变更_审计before_after_diff() {
        String original = snapshotProvider();
        String target = S3StorageProvider.NAME.equals(original)
                ? LocalStorageProvider.NAME : S3StorageProvider.NAME;
        Long lastAuditId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM tp_audit_log", Long.class);
        storageConfigService.update(1L, onlyProvider(target));
        List<String> rows = jdbcTemplate.queryForList("""
                SELECT before_json::text || '|' || after_json::text FROM tp_audit_log
                WHERE object_type = 'sys_config' AND action = 'update' AND id > ?
                  AND before_json::text LIKE '%"storage.provider"%'
                ORDER BY id DESC LIMIT 1
                """, String.class, lastAuditId);
        assertEquals(1, rows.size(), "写路径必须落审计（复用 K8 @Audit 单源）");
        assertTrue(rows.get(0).contains(original), "before 须含旧值: " + rows.get(0));
        assertTrue(rows.get(0).contains(target), "after 须含新值: " + rows.get(0));
    }


    @Test
    void 只改provider_其余键零变更() {
        String prefix = storedValue("storage.key-prefix");
        String ttl = storedValue("storage.url-ttl");
        String root = storedValue("storage.local-root");
        storageConfigService.update(1L, onlyProvider("s3"));
        assertEquals(prefix, storedValue("storage.key-prefix"));
        assertEquals(ttl, storedValue("storage.url-ttl"));
        assertEquals(root, storedValue("storage.local-root"));
        StorageConfigView view = storageConfigService.get();
        assertEquals("s3", view.provider());
        assertEquals(prefix, view.keyPrefix());
    }


    @Test
    void urlTtl越界与非数字_400人话() {
        assertThrows(ServiceException.class, () -> storageConfigService.update(1L,
                new StorageConfigInput(null, null, "99999", null, null, null, null, null)));
        assertThrows(ServiceException.class, () -> storageConfigService.update(1L,
                new StorageConfigInput(null, null, "0", null, null, null, null, null)));
        assertThrows(ServiceException.class, () -> storageConfigService.update(1L,
                new StorageConfigInput(null, null, "abc", null, null, null, null, null)));
        StorageConfigView view = storageConfigService.update(1L,
                new StorageConfigInput(null, null, "30", null, null, null, null, null));
        assertEquals("30", view.urlTtl());
    }

    private String snapshotProvider() {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = 'storage.provider'",
                String.class);
    }

    private String storedValue(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?", String.class, key);
    }
}
