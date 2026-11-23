package com.sixbean.testpike.admin.project.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.CreateProjectRequest;
import com.sixbean.testpike.admin.project.api.dto.ConnectivityTestView;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoRequest;
import com.sixbean.testpike.admin.project.api.dto.DeployInfoView;
import com.sixbean.testpike.admin.project.api.dto.EnvConnectionRequest;
import com.sixbean.testpike.admin.project.api.dto.EnvConnectionView;
import com.sixbean.testpike.admin.project.api.dto.EnvHealthView;
import com.sixbean.testpike.admin.project.api.dto.EnvParamView;
import com.sixbean.testpike.admin.project.api.dto.EnvParamsRequest;
import com.sixbean.testpike.admin.project.api.dto.EnvProbeView;
import com.sixbean.testpike.admin.project.api.dto.EnvRequest;
import com.sixbean.testpike.admin.project.api.dto.MemberAddRequest;
import com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest;
import com.sixbean.testpike.admin.project.api.dto.MemberView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProjectEnvView;
import com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView;
import com.sixbean.testpike.admin.project.api.dto.ProjectView;
import com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;

import java.util.List;


public class StubCoreProjectClient implements CoreProjectClient {

    private volatile int memberRoleResult = 0;

    
    private long crossProjectId = -1L;

    public void memberRoleResult(int role) {
        this.memberRoleResult = role;
    }

    @Override
    public int memberRole(long projectId, long userId) {
        if (projectId == crossProjectId) {
            return 0;
        }
        return memberRoleResult;
    }

    
    public void crossProject(long projectId) {
        this.crossProjectId = projectId;
    }

    
    private volatile Integer lastListStatus;

    
    private volatile UpdateProjectRequest lastUpdateRequest;

    public Integer lastListStatus() {
        return lastListStatus;
    }

    public UpdateProjectRequest lastUpdateRequest() {
        return lastUpdateRequest;
    }

    @Override
    public PageView<ProjectView> listProjects(boolean allVisible, String keyword, Integer status, int page, int size,
                                              String sortBy, String sortDir) {
        this.lastListStatus = status;
        return new PageView<>(List.of(), 0L);
    }

    @Override
    public ProjectView getProject(long projectId) {
        throw new UnsupportedOperationException("单测不触发项目查询");
    }

    @Override
    public long createProject(CreateProjectRequest request) {
        throw new UnsupportedOperationException("单测不触发项目查询");
    }

    private List<ProjectTemplateView> templateListResult = List.of();
    private SaveProjectTemplateRequest lastTemplateSaveRequest;
    private long lastTemplateUpdateId;

    public void stubTemplates(List<ProjectTemplateView> views) {
        this.templateListResult = views;
    }

    public List<ProjectTemplateView> stubTemplates() {
        return templateListResult;
    }

    public SaveProjectTemplateRequest lastTemplateSaveRequest() {
        return lastTemplateSaveRequest;
    }

    public long lastTemplateUpdateId() {
        return lastTemplateUpdateId;
    }

    @Override
    public List<ProjectTemplateView> listTemplates() {
        return templateListResult;
    }

    @Override
    public ProjectTemplateView getTemplate(long templateId) {
        throw new UnsupportedOperationException("单测不触发模板查询");
    }

    @Override
    public long createTemplate(SaveProjectTemplateRequest request) {
        this.lastTemplateSaveRequest = request;
        return 0L;
    }

    @Override
    public ProjectTemplateView updateTemplate(long templateId, SaveProjectTemplateRequest request) {
        this.lastTemplateSaveRequest = request;
        this.lastTemplateUpdateId = templateId;
        return null;
    }

    @Override
    public void deleteTemplate(long templateId) {
    }

    @Override
    public ProjectView updateProject(long projectId, UpdateProjectRequest request) {
        this.lastUpdateRequest = request;
        return null;
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
    public ProjectEnvView getEnvById(long envId) {
        return new ProjectEnvView(envId, projectIdForEnv, "test", 2, "http://t.example.com",
                "{}", "web", "chromium", 0, null, null, "v1.0.0", null, null);
    }

    @Override
    public EnvHealthView getEnvHealth(long projectId, long envId) {
        throw new UnsupportedOperationException("单测不触发环境健康查询");
    }

    @Override
    public EnvProbeView probeEnvHealth(long projectId, long envId) {
        throw new UnsupportedOperationException("单测不触发环境健康探测");
    }

    @Override
    public List<EnvParamView> listEnvParams(long projectId, long envId) {
        throw new UnsupportedOperationException("单测不触发环境参数查询");
    }

    @Override
    public List<EnvParamView> saveEnvParams(long projectId, long envId, EnvParamsRequest request) {
        throw new UnsupportedOperationException("单测不触发环境参数保存");
    }

    @Override
    public List<EnvConnectionView> listConnections(long projectId) {
        throw new UnsupportedOperationException("单测不触发连接查询");
    }

    @Override
    public long createConnection(long projectId, EnvConnectionRequest request) {
        throw new UnsupportedOperationException("单测不触发连接创建");
    }

    @Override
    public EnvConnectionView updateConnection(long projectId, long id, EnvConnectionRequest request) {
        throw new UnsupportedOperationException("单测不触发连接编辑");
    }

    @Override
    public void deleteConnection(long projectId, long id) {
        throw new UnsupportedOperationException("单测不触发连接删除");
    }

    @Override
    public ConnectivityTestView testConnection(long projectId, long id) {
        throw new UnsupportedOperationException("单测不触发连通测试");
    }

    @Override
    public DeployInfoView recordDeploy(long envId, DeployInfoRequest request) {
        return new DeployInfoView(request.version(), "2026-09-01 10:00:00", 1L, "管理员",
                List.of());
    }

    @Override
    public DeployInfoView getDeployInfo(long envId) {
        return new DeployInfoView("v2.0.1", "2026-09-01 10:00:00", 1L, "管理员", List.of());
    }

    
    private long projectIdForEnv = 1L;

    public void projectIdForEnv(long projectId) {
        this.projectIdForEnv = projectId;
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
}
