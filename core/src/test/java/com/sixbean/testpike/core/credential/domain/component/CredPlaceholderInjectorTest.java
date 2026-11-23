package com.sixbean.testpike.core.credential.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CredPlaceholderInjectorTest {

    @Autowired
    private CredPlaceholderInjector credPlaceholderInjector;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "inj" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "注入测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private Map<String, Object> step(String action, Map<String, Object> arguments) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("action", action);
        s.put("arguments", arguments);
        return s;
    }


    @Test
    void inject_replacesCredPlaceholdersRecursively() {
        credentialService.create(projectId, new CredentialInput(
                "登录口令", "qa_pwd", "S3cret-口令", null));
        credentialService.create(projectId, new CredentialInput(
                "登录账号", "qa_user", "qa_bot", null));

        List<Map<String, Object>> steps = List.of(
                step("app.input", Map.of("text", "{{CRED:qa_pwd}}")),
                step("app.input", Map.of("hint", "前置文案 {{CRED:qa_user}} 后置文案",
                        "nested", Map.of("deep", List.of("{{CRED:qa_pwd}}"),
                                "envKeep", "{{ENV:HOST}} 与 {{DATA:pool}} 不动"))));

        List<Map<String, Object>> out = credPlaceholderInjector.inject(projectId, steps);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstArgs = (Map<String, Object>) out.get(0).get("arguments");
        assertEquals("S3cret-口令", firstArgs.get("text"), "整串占位=实值替换");

        @SuppressWarnings("unchecked")
        Map<String, Object> secondArgs = (Map<String, Object>) out.get(1).get("arguments");
        assertEquals("前置文案 qa_bot 后置文案", secondArgs.get("hint"), "串内嵌占位=仅替换占位片段");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) secondArgs.get("nested");
        assertEquals(List.of("S3cret-口令"), nested.get("deep"), "List 元素内占位同样递归替换");
        assertEquals("{{ENV:HOST}} 与 {{DATA:pool}} 不动", nested.get("envKeep"),
                "非 CRED 命名空间原样留给执行器 env_resolver（禁服务端抢跑）");

        @SuppressWarnings("unchecked")
        Map<String, Object> origArgs = (Map<String, Object>) steps.get(0).get("arguments");
        assertEquals("{{CRED:qa_pwd}}", origArgs.get("text"), "入参 steps 禁就地改写");
    }


    @Test
    void inject_unknownKeyAndDisabled_failClosedNamingKeyNoSecret() {
        long offId = credentialService.create(projectId, new CredentialInput(
                "待禁口令", "off_pwd", "Zzz-实值", null));
        credentialService.setEnabled(offId, false);

        ServiceException unknown = assertThrows(ServiceException.class,
                () -> credPlaceholderInjector.inject(projectId,
                        List.of(step("app.input", Map.of("text", "{{CRED:no_such}}")))));
        assertTrue(unknown.getMessage().contains("no_such"), "未建档必须点名键");

        ServiceException disabled = assertThrows(ServiceException.class,
                () -> credPlaceholderInjector.inject(projectId,
                        List.of(step("app.input", Map.of("text", "{{CRED:off_pwd}}")))));
        assertTrue(disabled.getMessage().contains("已禁用"), "禁用=人话拒，实际=" + disabled.getMessage());
        assertFalse(disabled.getMessage().contains("Zzz-实值"), "错误文案零实值（红线 4）");
    }


    @Test
    void inject_stepsWithoutPlaceholders_passThroughUnchanged() {
        List<Map<String, Object>> steps = List.of(
                step("app.launch", Map.of("pkg", "com.demo")));
        assertEquals(steps, credPlaceholderInjector.inject(projectId, steps));
    }
}
