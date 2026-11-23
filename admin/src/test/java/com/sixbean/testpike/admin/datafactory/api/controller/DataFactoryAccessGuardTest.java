package com.sixbean.testpike.admin.datafactory.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryCreateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRecordView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunResultView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryRunView;
import com.sixbean.testpike.admin.datafactory.api.dto.DataFactoryUpdateRequest;
import com.sixbean.testpike.admin.datafactory.api.dto.DataPoolItemView;
import com.sixbean.testpike.admin.datafactory.infrastructure.remote.client.CoreDataFactoryClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class DataFactoryAccessGuardTest {

    private final StubCoreProjectClient client = new StubCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 成员_查看与执行_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanView(user("TESTER"), 1L),
                "项目内测试人员可查看造数脚本/执行（TC-L5-01）");
    }

    @Test
    void 非成员_查看_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanView(user("TESTER"), 1L),
                "非项目成员查看造数脚本 → 403（H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 负责人_管理_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanManage(user("TEST_LEAD"), 1L),
                "项目负责人可创建/编辑/删除造数脚本");
        assertDoesNotThrow(() -> guard.assertCanManage(user("ADMIN"), 1L),
                "系统管理员可管理任意项目造数脚本");
    }

    @Test
    void 普通成员_管理_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user("TESTER"), 1L),
                "普通成员创建/编辑/删除造数脚本 → 403（负责人专属）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 非成员_管理_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user("TESTER"), 1L),
                "非成员管理造数脚本 → 403（H1）");
        assertEquals(40301, ex.getCode());
    }


    @Test
    void 成员_查看数据池_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanView(user("TESTER"), 1L),
                "项目内成员可查看数据池（PRD 4.7）");
    }

    @Test
    void 普通成员_回收清理池_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManage(user("TESTER"), 1L),
                "普通成员改池状态/删池项 → 403（负责人专属）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 负责人_回收清理池_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanManage(user("TEST_LEAD"), 1L),
                "项目负责人可回收/标记异常/清理数据池项");
    }

    @Test
    void 控制器_详情_校验项目归属() {
        DataFactoryRecordView view = new DataFactoryRecordView(1L, 99L, 2L, "环境", 1,
                "脚本", "{}", null, 1, "u", "2026-09-01");
        DataFactoryController controller = new DataFactoryController(new CoreDataFactoryClient() {
            @Override
            public DataFactoryRecordView get(long id) {
                return view;
            }

            @Override
            public DataFactoryRecordView create(DataFactoryCreateRequest request) {
                return view;
            }

            @Override
            public PageView<DataFactoryRecordView> list(long projectId, String keyword, Integer type,
                                                        int page, int size, String sortBy, String sortDir) {
                return new PageView<>(List.of(), 0L);
            }

            @Override
            public void update(long id, DataFactoryUpdateRequest request) {
            }

            @Override
            public void delete(long id) {
            }

            @Override
            public DataFactoryRunResultView run(long id) {
                return new DataFactoryRunResultView(id, 2, 1, 1, "造了 1 条");
            }

            @Override
            public PageView<DataFactoryRunView> runs(long id, long projectId, int page, int size) {
                return new PageView<>(List.of(), 0L);
            }

            @Override
            public List<DataFactoryRunView> recentRuns(long id, long projectId, int limit) {
                return List.of();
            }

            @Override
            public PageView<DataPoolItemView> listPoolItems(long projectId, Long envId, String type,
                                                            Integer status, int page, int size) {
                return new PageView<>(List.of(), 0L);
            }

            @Override
            public void updatePoolItemStatus(long projectId, long id, int status) {
            }

            @Override
            public void deletePoolItem(long projectId, long id) {
            }

            @Override
            public List<com.sixbean.testpike.admin.datafactory.api.dto.GenFunctionGroupView> genkitCatalog() {
                return List.of();
            }
        }, guard);
        client.memberRoleResult(0);
        UserContext.set(user("TESTER"));
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> controller.get(1L),
                    "非成员跨项目取详情 → 403（H1 隔离）");
            assertEquals(40301, ex.getCode());
        } finally {
            UserContext.clear();
        }
    }
}
