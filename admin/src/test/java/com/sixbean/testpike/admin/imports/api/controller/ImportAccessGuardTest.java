package com.sixbean.testpike.admin.imports.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.CoreProjectClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class ImportAccessGuardTest {

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
        public com.sixbean.testpike.admin.project.api.dto.PageView<com.sixbean.testpike.admin.project.api.dto.ProjectView>
        listProjects(boolean allVisible, String keyword, Integer status, int page, int size,
                     String sortBy, String sortDir) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectView getProject(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public long createProject(com.sixbean.testpike.admin.project.api.dto.CreateProjectRequest request) {
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
        public com.sixbean.testpike.admin.project.api.dto.ProjectView updateProject(long projectId,
                                                                                    com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectView archiveProject(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.PageView<com.sixbean.testpike.admin.project.api.dto.ProjectView> listArchived(int page, int size) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectView restoreProject(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void purgeProject(long projectId, boolean force) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public List<com.sixbean.testpike.admin.project.api.dto.ProjectEnvView> listEnvs(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public long createEnv(long projectId, com.sixbean.testpike.admin.project.api.dto.EnvRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectEnvView updateEnv(long projectId, long envId,
                                                                                   com.sixbean.testpike.admin.project.api.dto.EnvRequest request) {
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
        public List<com.sixbean.testpike.admin.project.api.dto.MemberView> listMembers(long projectId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void addMember(long projectId, com.sixbean.testpike.admin.project.api.dto.MemberAddRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void updateMemberRole(long projectId, long userId,
                                     com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest request) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public void removeMember(long projectId, long userId) {
            throw new UnsupportedOperationException("单测不触发项目查询");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.ProjectEnvView getEnvById(long envId) {
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
        public com.sixbean.testpike.admin.project.api.dto.DeployInfoView recordDeploy(long envId,
                com.sixbean.testpike.admin.project.api.dto.DeployInfoRequest request) {
            throw new UnsupportedOperationException("单测不触发部署记录");
        }

        @Override
        public com.sixbean.testpike.admin.project.api.dto.DeployInfoView getDeployInfo(long envId) {
            throw new UnsupportedOperationException("单测不触发部署查询");
        }
    }

    private static CurrentUser user(String... roles) {
        return new CurrentUser(1L, "u", "U", "D", List.of(roles), System.currentTimeMillis() + 3600_000, null);
    }

    @Test
    void 导入_项目成员非开发_通过() {
        client.memberRoleResult(1);
        assertDoesNotThrow(() -> guard.assertCanWriteCase(user("TESTER"), 1L),
                "项目内测试人员可导入用例（写操作）");
    }

    @Test
    void 导入_项目负责人_通过() {
        client.memberRoleResult(2);
        assertDoesNotThrow(() -> guard.assertCanWriteCase(user("TEST_LEAD"), 1L));
    }

    @Test
    void 导入_非项目成员_403() {
        client.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanWriteCase(user("TESTER"), 1L),
                "非项目成员导入 → 403（IT-J1-03，H1 隔离）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 导入_开发人员即使负责人_403() {
        client.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> guard.assertCanWriteCase(user("DEVELOPER"), 1L),
                "开发角色只读（TC-AUTH-03）");
        assertEquals(40301, ex.getCode());
    }
}
