package com.sixbean.testpike.admin.llmconfig.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigSaveRequest;
import com.sixbean.testpike.admin.llmconfig.api.dto.LlmConfigView;
import com.sixbean.testpike.admin.llmconfig.api.dto.ProjectLlmConfigSaveBody;
import com.sixbean.testpike.admin.llmconfig.api.dto.ProjectLlmConfigView;
import com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client.ProjectLlmConfigRef;
import com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client.StubLlmConfigClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmConfigAccessGuardTest {

    private final StubLlmConfigClient llmClient = new StubLlmConfigClient();
    private final StubCoreProjectClient coreClient = new StubCoreProjectClient();
    private final LlmConfigController controller =
            new LlmConfigController(llmClient, new ProjectAccessGuard(coreClient));

    @BeforeEach
    void setUp() {
        llmClient.seed(new LlmConfigView(1L, "deepseek-main", "deepseek", "deepseek-chat",
                "******", "https://api.deepseek.com/v1", 1, 1, "默认", 1_048_576));
        llmClient.seed(new LlmConfigView(2L, "disabled-model", "openai", "gpt-4o",
                "******", "http://gw/v1", 0, 0, "停用", 262_144));
    }

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 项目负责人_选用模型_回显含启用清单() {
        coreClient.memberRoleResult(2);
        UserContext.set(user("TEST_LEAD"));

        ProjectLlmConfigView view = controller.updateProjectConfig(10L, new ProjectLlmConfigSaveBody(1L)).getData();
        assertEquals(1L, view.llmConfigId());
        assertEquals("deepseek-main", view.llmConfigName(), "选用后回显（TC-K9-01）");
        assertEquals(1, view.options().size(), "options 只含启用模型（停用过滤）");
        assertEquals("deepseek-chat", view.options().get(0).modelName());
    }

    @Test
    void 项目负责人_解除选用_回系统默认() {
        coreClient.memberRoleResult(2);
        UserContext.set(user("TEST_LEAD"));

        ProjectLlmConfigView view = controller.updateProjectConfig(10L, new ProjectLlmConfigSaveBody(null)).getData();
        assertNull(view.llmConfigId(), "null=系统默认");
    }

    @Test
    void 普通成员_选用模型_403() {
        coreClient.memberRoleResult(1);
        UserContext.set(user("TESTER"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.updateProjectConfig(10L, new ProjectLlmConfigSaveBody(1L)));
        assertEquals(40301, ex.getCode(), "仅项目负责人可选用（H1 隔离）");
    }

    @Test
    void 非项目成员_查看项目配置_403() {
        coreClient.memberRoleResult(0);
        UserContext.set(user("TESTER"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.getProjectConfig(10L));
        assertEquals(40301, ex.getCode(), "非成员无权查看项目大模型配置");
    }

    @Test
    void 项目成员_查看项目配置_通过() {
        coreClient.memberRoleResult(1);
        UserContext.set(user("TESTER"));
        llmClient.projectRef(new ProjectLlmConfigRef(null, null));

        ProjectLlmConfigView view = controller.getProjectConfig(10L).getData();
        assertNull(view.llmConfigId());
        assertEquals(1, view.options().size(), "数据源=系统启用模型清单");
        assertTrue(view.options().stream().noneMatch(o -> o.id() == 2L), "停用模型不在下拉数据源");
    }


    @Test
    void 保存请求_embedding类型字段_创建回显往返() {
        LlmConfigView created = llmClient.create(new LlmConfigSaveRequest(
                "emb-admin", "dashscope-compatible", "text-embedding-v3", "sk-e",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", 1, 0, "embedding",
                null, null, 2, 1024));
        assertEquals(2, created.modelType(), "类型回显往返");
        assertEquals(1024, created.embeddingDimension(), "维度回显往返");
    }

    @Test
    void embedding行_不进项目下拉数据源_类型筛选两条链路() {
        llmClient.seed(new LlmConfigView(9L, "emb-only", "dashscope-compatible", "text-embedding-v3",
                "******", "https://x/v1", 1, 1, "embedding 默认", 1_048_576, "auto", 2, 1024));
        coreClient.memberRoleResult(2);
        UserContext.set(user("TEST_LEAD"));

        ProjectLlmConfigView view = controller.getProjectConfig(10L).getData();
        assertTrue(view.options().stream().noneMatch(o -> o.id() == 9L),
                "embedding 行不作生成模型下拉（分域铁律 admin 镜像链路）");
        assertEquals(3, controller.list(null).getData().size(), "全集含生成+embedding");
        assertEquals(1, controller.list(2).getData().size(), "type=2 筛选仅 embedding");
        assertEquals(2, controller.list(1).getData().size(), "type=1 筛选仅生成");
    }
}
