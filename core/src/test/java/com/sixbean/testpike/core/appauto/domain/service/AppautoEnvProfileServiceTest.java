package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoEnvProfileServiceTest {

    @Autowired
    private AppautoEnvProfileService envProfileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long otherProjectId;

    @BeforeEach
    void setUp() {
        String code = "apx" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "env测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        String otherCode = "apy" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "env他项目-" + otherCode, otherCode);
        otherProjectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, otherCode);
        cleanProfiles();
    }

    @AfterEach
    void tearDown() {
        cleanProfiles();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id IN (?, ?)", projectId, otherProjectId);
    }

    private void cleanProfiles() {
        if (projectId != 0) {
            jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE project_id IN (?, ?)",
                    projectId, otherProjectId);
        }
    }

    private AppautoEnvProfileInput mpInput(String name) {
        return new AppautoEnvProfileInput(name, "MP_DEVTOOLS",
                Map.of("appid", "wx1234567890abcdef", "projectPath", "D:/mini/demo",
                        "cliPath", "C:/Program Files (x86)/Tencent/微信web开发者工具/cli.bat",
                        "autoPort", 9420),
                "样例小程序开发档");
    }


    @Test
    void create_thenGet_payloadRoundTrips() {
        long id = envProfileService.create(projectId, mpInput("开发工具-main"));
        assertTrue(id > 0);

        AppautoEnvProfileInfo info = envProfileService.get(id);
        assertEquals("开发工具-main", info.name());
        assertEquals("MP_DEVTOOLS", info.profileKind());
        assertEquals(Long.valueOf(projectId), info.projectId());
        Map<String, Object> payload = info.payload();
        assertEquals("wx1234567890abcdef", payload.get("appid"));
        assertEquals("D:/mini/demo", payload.get("projectPath"));
        assertNotNull(info.createdAt());
    }


    @Test
    void create_duplicateNameSameProject_rejectsHumanly() {
        envProfileService.create(projectId, mpInput("同名档案"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> envProfileService.create(projectId, mpInput("同名档案")));
        assertTrue(e.getMessage().contains("同名"), e.getMessage());
    }

    @Test
    void create_sameNameCrossProject_legal() {
        long a = envProfileService.create(projectId, mpInput("跨项目同名"));
        long b = envProfileService.create(otherProjectId, mpInput("跨项目同名"));
        assertTrue(a > 0 && b > 0 && a != b);
    }


    @Test
    void create_unknownKind_rejectsHumanly() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> envProfileService.create(projectId,
                        new AppautoEnvProfileInput("怪型", "SOMETHING_ELSE", Map.of(), null)));
        assertTrue(e.getMessage().contains("SOMETHING_ELSE"), e.getMessage());
    }

    @Test
    void create_blankName_rejectsHumanly() {
        assertThrows(ServiceException.class,
                () -> envProfileService.create(projectId,
                        new AppautoEnvProfileInput("  ", "MP_DEVTOOLS", Map.of(), null)));
    }


    @Test
    void list_byProjectAndKind_filters() {
        envProfileService.create(projectId, mpInput("小程序档"));
        envProfileService.create(projectId, new AppautoEnvProfileInput("安卓SDK档",
                "ANDROID_SDK", Map.of("sdkRoot", "D:/Android/sdk", "adbPath",
                        "D:/Android/sdk/platform-tools/adb.exe"), null));
        envProfileService.create(otherProjectId, mpInput("他项目档"));

        List<AppautoEnvProfileInfo> all = envProfileService.list(projectId, null);
        assertEquals(2, all.size());

        List<AppautoEnvProfileInfo> mpOnly = envProfileService.list(projectId, "MP_DEVTOOLS");
        assertEquals(1, mpOnly.size());
        assertEquals("小程序档", mpOnly.get(0).name());
    }


    @Test
    void update_payloadEffective_nextReadSeesIt() {
        long id = envProfileService.create(projectId, mpInput("改前名"));
        envProfileService.update(id, new AppautoEnvProfileInput("改后名", "MP_DEVTOOLS",
                Map.of("appid", "wxchanged", "autoPort", 9421), "改过发布渠道"));

        AppautoEnvProfileInfo info = envProfileService.get(id);
        assertEquals("改后名", info.name());
        assertEquals("wxchanged", info.payload().get("appid"));
        assertEquals(9421, ((Number) info.payload().get("autoPort")).intValue());
        assertEquals("改过发布渠道", info.remark());
    }

    @Test
    void update_renameIntoOtherProfile_rejectsHumanly() {
        envProfileService.create(projectId, mpInput("占用名"));
        long second = envProfileService.create(projectId,
                new AppautoEnvProfileInput("第二档", "ANDROID_SDK", Map.of(), null));
        ServiceException e = assertThrows(ServiceException.class,
                () -> envProfileService.update(second,
                        new AppautoEnvProfileInput("占用名", "ANDROID_SDK", Map.of(), null)));
        assertTrue(e.getMessage().contains("占用名"), e.getMessage());
    }


    @Test
    void delete_thenGet_returnsHuman404() {
        long id = envProfileService.create(projectId, mpInput("待删档"));
        envProfileService.delete(id);
        ServiceException e = assertThrows(ServiceException.class, () -> envProfileService.get(id));
        assertTrue(e.getMessage().contains("环境档案"), e.getMessage());
    }
}
