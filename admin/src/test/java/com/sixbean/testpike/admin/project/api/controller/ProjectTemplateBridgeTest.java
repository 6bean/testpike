package com.sixbean.testpike.admin.project.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.CreateProjectRequest;
import com.sixbean.testpike.admin.project.api.dto.ProjectTemplateContentRequest;
import com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView;
import com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest;
import com.sixbean.testpike.admin.project.api.dto.TemplateEnvItemRequest;
import com.sixbean.testpike.admin.project.api.dto.TemplateTagItemRequest;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ProjectTemplateBridgeTest {

    private static final String KEY = "perm:project:project-view";

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);
    private final ProjectTemplateController controller = new ProjectTemplateController(client, guard);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    private void loginDeveloper() {
        UserContext.set(new CurrentUser(2L, "dev", "D", "开发人员", List.of("DEVELOPER"),
                System.currentTimeMillis() + 3600_000, null));
    }

    private static String permOf(Class<?> controllerClass, String methodName, Class<?>... params) {
        try {
            Method m = controllerClass.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("模板桥签名漂移：" + e.getMessage(), e);
        }
    }

    @Test
    void 读路径挂VIEW族键零漂移且列表透传core() {
        assertEquals(KEY, permOf(ProjectTemplateController.class, "list"),
                "模板列表=创建向导消费面，登录用户皆可（project-view 预定义恒命中）");
        assertEquals(KEY, permOf(ProjectTemplateController.class, "detail", long.class));
        loginDeveloper();
        List<ProjectTemplateView> views = controller.list().getData();
        assertEquals(client.stubTemplates(), views, "读路径必须原样透传 core 列表，禁包装层重建");
    }

    @Test
    void 写路径仅系统管理员_非管理员人话403() {
        assertEquals(KEY, permOf(ProjectTemplateController.class, "create", SaveProjectTemplateRequest.class),
                "写路径挂 VIEW 族键=拦截器零漂移，ADMIN 实体闸由 guard 保留人话文案（tick33 定案同型）");
        loginDeveloper();
        SaveProjectTemplateRequest req = new SaveProjectTemplateRequest("电商模板", "ecommerce",
                new ProjectTemplateContentRequest(List.of(), List.of(), List.of(), List.of()));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.create(req));
        assertTrue(ex.getMessage().contains("系统管理员"), "写路径拒绝文案必须人话点名角色：" + ex.getMessage());
        assertThrows(ServiceException.class, () -> controller.delete(9L));
    }

    @Test
    void 管理员写路径整对象透传core() {
        loginAdmin();
        SaveProjectTemplateRequest req = new SaveProjectTemplateRequest("金融模板", "finance",
                new ProjectTemplateContentRequest(List.of(7L),
                        List.of(new TemplateTagItemRequest("支付", 1)),
                        List.of(new TemplateEnvItemRequest("测试环境", 2,
                                "http://test.example.com", "web", "chromium")),
                        List.of("建议为测试负责人分配用例评审权限")));
        controller.create(req);
        assertSame(req, client.lastTemplateSaveRequest(), "admin 桥必须整对象透传，禁手动逐字段拷贝");
        controller.update(3L, req);
        assertSame(req, client.lastTemplateSaveRequest());
        assertEquals(3L, client.lastTemplateUpdateId());
    }

    @Test
    void 请求JSON字段名与core契约对齐() throws Exception {
        SaveProjectTemplateRequest req = new SaveProjectTemplateRequest("n", "industry",
                new ProjectTemplateContentRequest(List.of(1L),
                        List.of(new TemplateTagItemRequest("t", 1)),
                        List.of(new TemplateEnvItemRequest("e", 2, "http://x", "web", "chromium")),
                        List.of("hint")));
        Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(req), Map.class);
        for (String key : List.of("name", "industry", "content")) {
            assertTrue(json.containsKey(key), "保存契约字段缺失：" + key);
        }
        Map<?, ?> content = mapper.readValue(mapper.writeValueAsString(req.content()), Map.class);
        for (String key : List.of("skillIds", "tags", "envs", "roleHints")) {
            assertTrue(content.containsKey(key), "content 四件套字段缺失：" + key);
        }
        Map<?, ?> tag = (Map<?, ?>) ((List<?>) content.get("tags")).get(0);
        assertNotNull(tag.get("name"));
        assertNotNull(tag.get("type"));
        Map<?, ?> env = (Map<?, ?>) ((List<?>) content.get("envs")).get(0);
        for (String key : List.of("name", "envType", "baseUrl", "endTypes", "browsers")) {
            assertTrue(env.containsKey(key), "env 项字段缺失：" + key);
        }
    }

    @Test
    void 创建项目请求扩templateId且旧构造零漂移() throws Exception {
        CreateProjectRequest withTemplate = new CreateProjectRequest(
                "项目A", "TPX", "zh-CN", "P1", new BigDecimal("100"), 42L);
        Map<?, ?> json = mapper.readValue(mapper.writeValueAsString(withTemplate), Map.class);
        assertEquals(42L, ((Number) json.get("templateId")).longValue(),
                "core ProjectCreateInput.templateId 同名对齐（跨服务按名反序列化）");
        CreateProjectRequest legacy = new CreateProjectRequest(
                "项目B", "TPY", "zh-CN", "P2", null);
        assertEquals(null, legacy.templateId());
    }
}
