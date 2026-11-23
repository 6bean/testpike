package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoAppServiceTest {

    @Autowired
    private AppautoAppService appService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;

    @BeforeEach
    void setUp() {
        String code = "apx" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "包档案测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String otherCode = "apy" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "包档案他项目-" + otherCode, otherCode);
        otherProjectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, otherCode);
        cleanApps();
    }

    @AfterEach
    void cleanUp() {
        cleanApps();
    }

    private void cleanApps() {
        jdbcTemplate.update("DELETE FROM tp_appauto_app WHERE project_id IN (?, ?)",
                projectId, otherProjectId);
    }


    @Test
    void createAppReturnsIdAndEchoesProfile() {
        long id = appService.create(projectId, new AppautoAppInput(
                "商城App", "com.shop.demo", "1.2.3", "android", null));
        assertTrue(id > 0);
        AppautoAppInfo info = appService.get(id);
        assertEquals("商城App", info.name());
        assertEquals("com.shop.demo", info.packageName());
        assertEquals("1.2.3", info.version());
        assertEquals("ANDROID", info.platformCode(), "平台码归一大写对账目录启用面");
        assertEquals(projectId, info.projectId());
        assertEquals("MANUAL", info.source());
        assertNull(info.artifactKey(), "未上传工件时 key 列为空");
    }

    @Test
    void createRejectsMissingNameOrPackageInPlainWords() {
        ServiceException noName = assertThrows(ServiceException.class, () ->
                appService.create(projectId, new AppautoAppInput(
                        "  ", "com.shop.demo", "1.0.0", "android", null)));
        assertTrue(noName.getMessage().contains("应用名称"), "实际=" + noName.getMessage());

        ServiceException noPkg = assertThrows(ServiceException.class, () ->
                appService.create(projectId, new AppautoAppInput(
                        "商城App", null, "1.0.0", "android", null)));
        assertTrue(noPkg.getMessage().contains("包名"), "实际=" + noPkg.getMessage());
    }


    @Test
    void samePackageSameVersionRecreateOverwritesSingleRow() {
        long first = appService.create(projectId, new AppautoAppInput(
                "旧名称", "com.shop.demo", "1.2.3", "android", null));
        long second = appService.create(projectId, new AppautoAppInput(
                "新名称", "com.shop.demo", "1.2.3", "android", null));
        assertEquals(first, second, "uq 冲突回读同一档案 id（覆盖不新建）");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_app WHERE project_id = ? AND package_name = ?",
                Integer.class, projectId, "com.shop.demo");
        assertEquals(1, rows);
        assertEquals("新名称", appService.get(first).name());
    }

    @Test
    void samePackageDifferentVersionCoexists() {
        long a = appService.create(projectId, new AppautoAppInput(
                "商城1.2", "com.shop.demo", "1.2.3", "android", null));
        long b = appService.create(projectId, new AppautoAppInput(
                "商城1.3", "com.shop.demo", "1.3.0", "android", null));
        assertNotEquals(a, b, "版本不同=两条档案并存（多版本归档语义）");
    }


    @Test
    void uploadStoresKeySizeAndSha256() {
        long id = appService.create(projectId, new AppautoAppInput(
                "商城App", "com.shop.demo", "1.2.3", "android", null));
        byte[] apk = "APK-BYTES-demo".getBytes(StandardCharsets.UTF_8);
        String key = appService.uploadPackage(id, apk, "shop-1.2.3.apk");

        AppautoAppInfo info = appService.get(id);
        assertEquals(key, info.artifactKey());
        assertEquals((long) apk.length, info.artifactSizeBytes(), "体积=上传字节实测");
        assertEquals(sha256Hex(apk), info.artifactSha256(), "摘要=上传内容 SHA-256（执行侧下载校验锚）");
        assertTrue(info.artifactKey().contains("app-package"),
                "objectKey 走规范存储场景: " + info.artifactKey());
    }

    @Test
    void reuploadReplacesArtifactAndCleansOldObject() {
        long id = appService.create(projectId, new AppautoAppInput(
                "商城App", "com.shop.demo", "1.2.3", "android", null));
        String firstKey = appService.uploadPackage(id,
                "OLD".getBytes(StandardCharsets.UTF_8), "old.apk");
        String secondKey = appService.uploadPackage(id,
                "NEW-CONTENT".getBytes(StandardCharsets.UTF_8), "new.apk");

        assertNotEquals(firstKey, secondKey, "重传生成新对象 key（uuid 防同名覆盖）");
        AppautoAppInfo info = appService.get(id);
        assertEquals(secondKey, info.artifactKey());
        assertEquals(11L, info.artifactSizeBytes(), "体积按新内容实测复位");
        assertEquals(sha256Hex("NEW-CONTENT".getBytes(StandardCharsets.UTF_8)), info.artifactSha256());
    }

    @Test
    void uploadRejectsOverK8ConfiguredMaxSize() {
        long id = appService.create(projectId, new AppautoAppInput(
                "商城App", "com.shop.demo", "1.2.3", "android", null));
        configCacheHolder.put(AppautoConfigReader.KEY_PKG_MAX_SIZE, "10");
        try {
            ServiceException tooBig = assertThrows(ServiceException.class, () ->
                    appService.uploadPackage(id,
                            "THIS-PAYLOAD-IS-CERTAINLY-OVER-TEN-BYTES".getBytes(StandardCharsets.UTF_8),
                            "big.apk"));
            assertTrue(tooBig.getMessage().contains("超过"), "实际=" + tooBig.getMessage());
            assertTrue(tooBig.getMessage().contains("上限"), "实际=" + tooBig.getMessage());
            assertNull(appService.get(id).artifactKey());
        } finally {
            configCacheHolder.remove(AppautoConfigReader.KEY_PKG_MAX_SIZE);
        }
    }

    @Test
    void uploadRejectsEmptyPayload() {
        long id = appService.create(projectId, new AppautoAppInput(
                "商城App", "com.shop.demo", "1.2.3", "android", null));
        ServiceException empty = assertThrows(ServiceException.class, () ->
                appService.uploadPackage(id, new byte[0], "empty.apk"));
        assertTrue(empty.getMessage().contains("空"), "实际=" + empty.getMessage());
    }


    @Test
    void deleteAppRemovesRowAndArtifactReference() {
        long id = appService.create(projectId, new AppautoAppInput(
                "商城App", "com.shop.demo", "1.2.3", "android", null));
        appService.uploadPackage(id, "BYTES".getBytes(StandardCharsets.UTF_8), "shop.apk");
        appService.delete(id);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_app WHERE id = ?", Integer.class, id);
        assertEquals(0, rows);
        ServiceException gone = assertThrows(ServiceException.class, () -> appService.get(id));
        assertTrue(gone.getMessage().contains("不存在"), "实际=" + gone.getMessage());
    }


    @Test
    void listIsProjectScopedAndMissingGetSpeaksPlainly() {
        appService.create(projectId, new AppautoAppInput(
                "本项目App", "com.a.demo", "1.0.0", "android", null));
        appService.create(otherProjectId, new AppautoAppInput(
                "他项目App", "com.b.demo", "1.0.0", "android", null));

        List<AppautoAppInfo> mine = appService.list(projectId);
        assertEquals(1, mine.size());
        assertEquals("com.a.demo", mine.get(0).packageName());

        ServiceException missing = assertThrows(ServiceException.class, () -> appService.get(999999999L));
        assertTrue(missing.getMessage().contains("不存在"), "实际=" + missing.getMessage());
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(content)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
