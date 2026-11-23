package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPackageGrantView;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoAppDownloadGrantTest {

    @Autowired
    private AppautoAppService appService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long taskId;

    @BeforeEach
    void setUp() {
        String code = "apg" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "包下载授权项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        long caseId = caseService.create(1L, new CaseCreateInput(projectId, "包下载用例", "前置",
                "预期", 1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", "MOBILEWRIGHT", null)).id();
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, ?, 4, 6, '{"kind":"app-auto-run","steps":[]}')
                """, projectId, caseId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ? AND case_id = ?",
                Long.class, projectId, caseId).longValue();
        cleanApps();
    }

    @AfterEach
    void tearDown() {
        cleanApps();
    }

    private void cleanApps() {
        jdbcTemplate.update("DELETE FROM tp_appauto_app WHERE project_id = ?", projectId);
    }

    private long createUploadedApp(String name, String pkg, String version, byte[] content) {
        long id = appService.create(projectId, new AppautoAppInput(name, pkg, version, "android", null));
        appService.uploadPackage(id, content, name + ".apk");
        return id;
    }


    @Test
    void grantByNumericArtifactRefReturnsPresignedView() {
        byte[] apk = "APK-BYTES-grant-1".getBytes(StandardCharsets.UTF_8);
        long id = createUploadedApp("商城包A", "com.shop.demo", "1.0.0", apk);

        AppautoPackageGrantView byId = appService.grantPackageDownload(taskId, id);
        assertNotNull(byId.downloadUrl(), "预签名 GET 必须签发");
        assertTrue(byId.downloadUrl().contains("X-Amz"), "预签名 URL 形态: " + byId.downloadUrl());
        assertEquals(sha256Hex(apk), byId.sha256(), "摘要=档案登记 SHA-256（执行侧对账锚）");
        assertEquals((long) apk.length, byId.sizeBytes());
        assertEquals(id, byId.appId());
        assertEquals("com.shop.demo", byId.packageName());
        assertEquals("1.0.0", byId.version());

        AppautoPackageGrantView byString = appService.grantPackageDownload(taskId, String.valueOf(id));
        assertEquals(byId.appId(), byString.appId(), "数字串引用=同档语义（IR artifactRef 字段字符串形）");
    }


    @Test
    void grantByPackageRefPicksLatestUploadedArchive() {
        createUploadedApp("旧版包", "com.shop.demo", "1.0.0", "OLD".getBytes(StandardCharsets.UTF_8));
        long latest = createUploadedApp("新版包", "com.shop.demo", "2.0.0", "NEW".getBytes(StandardCharsets.UTF_8));

        AppautoPackageGrantView view = appService.grantPackageDownload(taskId, "com.shop.demo");
        assertEquals(latest, view.appId(), "包名引用=最近登记版本档案（CI 反复出包默认取新）");
        assertEquals("2.0.0", view.version());
    }


    @Test
    void unknownTaskRejectedInPlainWords() {
        long id = createUploadedApp("包", "com.shop.x", "1.0", "A".getBytes(StandardCharsets.UTF_8));
        ServiceException e = assertThrows(ServiceException.class,
                () -> appService.grantPackageDownload(9999999L, id));
        assertTrue(e.getMessage().contains("任务"), "实际=" + e.getMessage());
    }

    @Test
    void unknownArtifactRefRejectedInPlainWords() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> appService.grantPackageDownload(taskId, "com.not.exists"));
        assertTrue(e.getMessage().contains("包档案"), "实际=" + e.getMessage());
    }

    @Test
    void crossProjectArchiveRejectedFailClosed() {
        String otherCode = "aph" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "包下载他项目-" + otherCode, otherCode);
        long otherProjectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, otherCode);
        try {
            long foreign = appService.create(otherProjectId, new AppautoAppInput(
                    "他项目包", "com.shop.demo", "1.0.0", "android", null));
            appService.uploadPackage(foreign, "X".getBytes(StandardCharsets.UTF_8), "x.apk");
            ServiceException e = assertThrows(ServiceException.class,
                    () -> appService.grantPackageDownload(taskId, "com.shop.demo"));
            assertTrue(e.getMessage().contains("包档案"), "跨项目=人话拒（H1 隔离，禁静默借档）: "
                    + e.getMessage());
            ServiceException byId = assertThrows(ServiceException.class,
                    () -> appService.grantPackageDownload(taskId, foreign));
            assertTrue(byId.getMessage().contains("项目"), "编号引用跨项目同样人话拒: "
                    + byId.getMessage());
        } finally {
            jdbcTemplate.update("DELETE FROM tp_appauto_app WHERE project_id = ?", otherProjectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProjectId);
        }
    }


    @Test
    void archiveWithoutArtifactUploadRejectedInPlainWords() {
        long id = appService.create(projectId, new AppautoAppInput(
                "空档", "com.shop.empty", "1.0.0", "android", null));
        ServiceException e = assertThrows(ServiceException.class,
                () -> appService.grantPackageDownload(taskId, id));
        assertTrue(e.getMessage().contains("包工件") || e.getMessage().contains("未上传"),
                "实际=" + e.getMessage());
    }

    private static String sha256Hex(byte[] content) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(content)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e2) {
            throw new IllegalStateException(e2);
        }
    }
}
