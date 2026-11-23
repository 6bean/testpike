package com.sixbean.testpike.admin.project.app.component;

import com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest;

import com.sixbean.testpike.admin.project.api.dto.MemberAddRequest;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.api.dto.CreateProjectRequest;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoRequest;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoView;
import com.sixbean.testpike.admin.project.api.dto.EnvRequest;
import com.sixbean.testpike.admin.project.api.dto.MemberView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProjectEnvView;
import com.sixbean.testpike.admin.project.api.dto.ProjectView;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class ProjectAccessGuardTest {

    private final TestCoreProjectClient client = new TestCoreProjectClient();
    private final ProjectAccessGuard guard = new ProjectAccessGuard(client);

    
    private static final class TestCoreProjectClient implements CoreProjectClient {
        private volatile int memberRoleResult = 0;

        void memberRoleResult(int role) {
            this.memberRoleResult = role;
        }

        @Override
        public int memberRole(long projectId, long userId) {
            return memberRoleResult;
        }

        @Override
        public PageView<ProjectView> listProjects(boolean allVisible, String keyword, Integer status, int page, int size,
                                                  String sortBy, String sortDir) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public ProjectView getProject(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public long createProject(CreateProjectRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public java.util.List<com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView> listTemplates() {
            throw new UnsupportedOperationException("单测不触发模板查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView getTemplate(long templateId) {
            throw new UnsupportedOperationException("单测不触发模板查询");
        }

        @Override
        public long createTemplate(com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest request) {
            throw new UnsupportedOperationException("单测不触发模板写入");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView updateTemplate(long templateId,
                com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest request) {
            throw new UnsupportedOperationException("单测不触发模板写入");
        }

        @Override
        public void deleteTemplate(long templateId) {
            throw new UnsupportedOperationException("单测不触发模板删除");
        }

        @Override
        public ProjectView updateProject(long projectId, UpdateProjectRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public ProjectView archiveProject(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public PageView<ProjectView> listArchived(int page, int size) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public ProjectView restoreProject(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void purgeProject(long projectId, boolean force) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public List<ProjectEnvView> listEnvs(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public long createEnv(long projectId, EnvRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public ProjectEnvView updateEnv(long projectId, long envId, EnvRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void deleteEnv(long projectId, long envId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void deleteEnv(long projectId, long envId, boolean force) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public List<MemberView> listMembers(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void addMember(long projectId, MemberAddRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void updateMemberRole(long projectId, long userId, MemberRoleRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void removeMember(long projectId, long userId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public ProjectEnvView getEnvById(long envId) {
            throw new UnsupportedOperationException("单测不触发环境查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.EnvHealthView getEnvHealth(long projectId, long envId) {
            throw new UnsupportedOperationException("单测不触发环境健康查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.EnvProbeView probeEnvHealth(long projectId, long envId) {
            throw new UnsupportedOperationException("单测不触发环境健康探测");
        }

        @Override
        public java.util.List<com.sixbean.testpike.admin.project.api.dto.EnvParamView> listEnvParams(
                long projectId, long envId) {
            throw new UnsupportedOperationException("单测不触发环境参数查询");
        }

        @Override
        public java.util.List<com.sixbean.testpike.admin.project.api.dto.EnvParamView> saveEnvParams(
                long projectId, long envId,
                com.sixbean.testpike.admin.project.api.dto.EnvParamsRequest request) {
            throw new UnsupportedOperationException("单测不触发环境参数保存");
        }

        @Override
        public java.util.List<com.sixbean.testpike.admin.project.api.dto.EnvConnectionView> listConnections(long projectId) {
            throw new UnsupportedOperationException("单测不触发连接查询");
        }

        @Override
        public long createConnection(long projectId,
                com.sixbean.testpike.admin.project.api.dto.EnvConnectionRequest request) {
            throw new UnsupportedOperationException("单测不触发连接创建");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.EnvConnectionView updateConnection(long projectId, long id,
                com.sixbean.testpike.admin.project.api.dto.EnvConnectionRequest request) {
            throw new UnsupportedOperationException("单测不触发连接编辑");
        }

        @Override
        public void deleteConnection(long projectId, long id) {
            throw new UnsupportedOperationException("单测不触发连接删除");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ConnectivityTestView testConnection(long projectId, long id) {
            throw new UnsupportedOperationException("单测不触发连通测试");
        }

        @Override
        public DeployInfoView recordDeploy(long envId, DeployInfoRequest request) {
            throw new UnsupportedOperationException("单测不触发部署记录");
        }

        @Override
        public DeployInfoView getDeployInfo(long envId) {
            throw new UnsupportedOperationException("单测不触发部署查询");
        }
    }

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 用户管理_仅系统管理员() {
        assertDoesNotThrow(() -> guard.assertCanManageUsers(user("ADMIN")), "系统管理员可管理用户");
    }

    @Test
    void 用户管理_测试负责人403() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanManageUsers(user("TEST_LEAD")), "测试负责人无用户管理权（PRD 4.6：仅系统管理员）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 用户管理_测试人员403() {
        assertThrows(ServiceException.class, () -> guard.assertCanManageUsers(user("TESTER")));
    }

    @Test
    void 用户管理_开发人员403() {
        assertThrows(ServiceException.class, () -> guard.assertCanManageUsers(user("DEVELOPER")), "开发角色只读（TC-AUTH-03 语义）");
    }

    @Test
    void 系统级管理角色_管理员与测试负责人() {
        assertEquals(true, guard.isSystemManager(user("ADMIN")));
        assertEquals(true, guard.isSystemManager(user("TEST_LEAD")));
        assertEquals(false, guard.isSystemManager(user("TESTER")));
        assertEquals(false, guard.isSystemManager(user("DEVELOPER")));
    }


    @Test
    void 评审_系统管理员可评审任意用例() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanReview(user("ADMIN"), 1L, 99L),
                "系统管理员=全部（PRD 2 章）");
    }

    @Test
    void 评审_项目负责人可评审任意用例() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanReview(user("TEST_LEAD"), 1L, null),
                "项目负责人可评审（PRD 4.6 审批仅被分配人/负责人）");
    }

    @Test
    void 评审_被分配人可评审() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanReview(user("TESTER"), 1L, 1L),
                "被分配人（assignee_id=自己）可评审");
    }

    @Test
    void 评审_非分配人普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanReview(user("TESTER"), 1L, 999L),
                "普通测试人员非分配人 → 403（TC-AUTH-01/TC-RVW-13）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 评审_用例无分配人普通成员_403() {
        client.memberRoleResult(1);
        assertThrows(ServiceException.class, () -> guard.assertCanReview(user("TESTER"), 1L, null),
                "无分配人时普通成员不可评审");
    }

    @Test
    void 评审_非项目成员_403() {
        client.memberRoleResult(0);
        assertThrows(ServiceException.class, () -> guard.assertCanReview(user("TESTER"), 1L, 1L),
                "非项目成员即使被分配也不可评审（H1 隔离）");
    }

    @Test
    void 评审_开发人员_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanReview(user("DEVELOPER"), 1L, 1L),
                "开发角色只读（TC-AUTH-03/TC-RVW-14）");
        assertEquals(40301, ex.getCode());
    }


    @Test
    void 分配_系统管理员可分配() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanAssignCase(user("ADMIN"), 1L),
                "系统管理员可分配（PRD 2 章全部权限）");
    }

    @Test
    void 分配_项目负责人可分配() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanAssignCase(user("TEST_LEAD"), 1L),
                "项目负责人可分配（PRD 4.6 用例分配）");
    }

    @Test
    void 分配_普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanAssignCase(user("TESTER"), 1L),
                "普通测试人员非负责人 → 403（TC-ASN-11）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 分配_开发人员即使负责人_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanAssignCase(user("DEVELOPER"), 1L),
                "开发角色只读（TC-ASN-12）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 分配_非项目成员_403() {
        client.memberRoleResult(0);
        assertThrows(ServiceException.class, () -> guard.assertCanAssignCase(user("TESTER"), 1L),
                "非项目成员不可分配（H1 隔离）");
    }


    @Test
    void 需求点_系统管理员可管理() {
        client.memberRoleResult(0);
        assertDoesNotThrow(() -> guard.assertCanWriteRequirement(user("ADMIN"), 1L),
                "系统管理员可管理需求点（PRD 2 章全部权限）");
    }

    @Test
    void 需求点_项目负责人可管理() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanWriteRequirement(user("TEST_LEAD"), 1L),
                "项目负责人可创建/编辑/停用/删除需求点（TC-REQ-13）");
    }

    @Test
    void 需求点_普通成员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanWriteRequirement(user("TESTER"), 1L),
                "普通测试人员非负责人 → 403（TC-REQ-13/IT-REQ-10）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 需求点_开发人员即使负责人_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanWriteRequirement(user("DEVELOPER"), 1L),
                "开发角色只读，不能管理需求点");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 需求点_非项目成员_403() {
        client.memberRoleResult(0);
        assertThrows(ServiceException.class, () -> guard.assertCanWriteRequirement(user("TESTER"), 1L),
                "非项目成员不可管理需求点（H1 隔离，TC-REQ-14）");
    }


    @Test
    void 用例导出_项目成员可导出() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanExportCase(user("TESTER"), 1L),
                "项目内成员可导出用例（TC-P2-02 项目内可见）");
    }

    @Test
    void 用例导出_开发人员_403() {
        client.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanExportCase(user("DEVELOPER"), 1L),
                "开发角色只读，不能导出用例（TC-AUTH-03 语义）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 用例导出_非项目成员_403() {
        client.memberRoleResult(0);
        assertThrows(ServiceException.class, () -> guard.assertCanExportCase(user("TESTER"), 1L),
                "非项目成员不可导出（H1 隔离）");
    }
}
