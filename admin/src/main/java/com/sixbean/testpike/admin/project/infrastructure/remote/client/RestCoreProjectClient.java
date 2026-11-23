package com.sixbean.testpike.admin.project.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest;

import com.sixbean.testpike.admin.project.api.dto.MemberAddRequest;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
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
import com.sixbean.testpike.admin.project.api.dto.MemberView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.api.dto.ProjectEnvView;
import com.sixbean.testpike.admin.project.api.dto.ProjectTemplateView;
import com.sixbean.testpike.admin.project.api.dto.ProjectView;
import com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.client.RestClientInvoker;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;


@Component
public class RestCoreProjectClient implements CoreProjectClient {

    private final RestClient rest;

    public RestCoreProjectClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public PageView<ProjectView> listProjects(boolean allVisible, String keyword, Integer status, int page, int size,
                                              String sortBy, String sortDir) {
        String uri = UriComponentsBuilder.fromPath("/internal/projects")
                .queryParam("userId", UserContext.currentUserId())
                .queryParam("allVisible", allVisible)
                .queryParamIfPresent("keyword", Optional.ofNullable(keyword))
                .queryParamIfPresent("status", Optional.ofNullable(status))
                .queryParam("page", page)
                .queryParam("size", size)
                .queryParamIfPresent("sortBy", Optional.ofNullable(sortBy).filter(s -> !s.isBlank()))
                .queryParamIfPresent("sortDir", Optional.ofNullable(sortDir).filter(s -> !s.isBlank()))
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ProjectView getProject(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public long createProject(CreateProjectRequest request) {
        Long id = call(HttpMethod.POST, "/internal/projects", request, new ParameterizedTypeReference<>() {
        });
        return id == null ? 0L : id;
    }

    @Override
    public List<ProjectTemplateView> listTemplates() {
        return call(HttpMethod.GET, "/internal/projects/templates", null,
                new ParameterizedTypeReference<Response<List<ProjectTemplateView>>>() {
                });
    }

    @Override
    public ProjectTemplateView getTemplate(long templateId) {
        return call(HttpMethod.GET, "/internal/projects/templates/" + templateId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long createTemplate(SaveProjectTemplateRequest request) {
        Long id = call(HttpMethod.POST, "/internal/projects/templates", request,
                new ParameterizedTypeReference<Response<Long>>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public ProjectTemplateView updateTemplate(long templateId, SaveProjectTemplateRequest request) {
        return call(HttpMethod.PUT, "/internal/projects/templates/" + templateId, request,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void deleteTemplate(long templateId) {
        call(HttpMethod.DELETE, "/internal/projects/templates/" + templateId, null,
                new ParameterizedTypeReference<Response<Void>>() {
                });
    }

    @Override
    public ProjectView updateProject(long projectId, UpdateProjectRequest request) {
        return call(HttpMethod.PUT, "/internal/projects/" + projectId, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ProjectView archiveProject(long projectId) {
        return call(HttpMethod.POST, "/internal/projects/" + projectId + "/archive", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public PageView<ProjectView> listArchived(int page, int size) {
        String uri = UriComponentsBuilder.fromPath("/internal/projects/archived")
                .queryParam("page", page)
                .queryParam("size", size)
                .build().encode().toUriString();
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public ProjectView restoreProject(long projectId) {
        return call(HttpMethod.PUT, "/internal/projects/" + projectId + "/restore", null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void purgeProject(long projectId, boolean force) {
        call(HttpMethod.DELETE, "/internal/projects/" + projectId + "/purge?force=" + force, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public int memberRole(long projectId, long userId) {
        String uri = UriComponentsBuilder.fromPath("/internal/projects/" + projectId + "/member-role")
                .queryParam("userId", userId).build().encode().toUriString();
        Integer role = call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
        return role == null ? 0 : role;
    }

    @Override
    public List<ProjectEnvView> listEnvs(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/envs", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public long createEnv(long projectId, EnvRequest request) {
        Long id = call(HttpMethod.POST, "/internal/projects/" + projectId + "/envs", request, new ParameterizedTypeReference<>() {
        });
        return id == null ? 0L : id;
    }

    @Override
    public ProjectEnvView updateEnv(long projectId, long envId, EnvRequest request) {
        return call(HttpMethod.PUT, "/internal/projects/" + projectId + "/envs/" + envId, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void deleteEnv(long projectId, long envId) {
        deleteEnv(projectId, envId, false);
    }

    @Override
    public void deleteEnv(long projectId, long envId, boolean force) {
        call(HttpMethod.DELETE, "/internal/projects/" + projectId + "/envs/" + envId + "?force=" + force,
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ProjectEnvView getEnvById(long envId) {
        return call(HttpMethod.GET, "/internal/projects/envs/" + envId, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public EnvHealthView getEnvHealth(long projectId, long envId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/envs/" + envId + "/health",
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public EnvProbeView probeEnvHealth(long projectId, long envId) {
        return call(HttpMethod.POST, "/internal/projects/" + projectId + "/envs/" + envId + "/health",
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<EnvParamView> listEnvParams(long projectId, long envId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/envs/" + envId + "/params",
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<EnvParamView> saveEnvParams(long projectId, long envId, EnvParamsRequest request) {
        return call(HttpMethod.PUT, "/internal/projects/" + projectId + "/envs/" + envId + "/params",
                request, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<EnvConnectionView> listConnections(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/env-connections",
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long createConnection(long projectId, EnvConnectionRequest request) {
        Long id = call(HttpMethod.POST, "/internal/projects/" + projectId + "/env-connections",
                request, new ParameterizedTypeReference<>() {
                });
        return id == null ? 0L : id;
    }

    @Override
    public EnvConnectionView updateConnection(long projectId, long id, EnvConnectionRequest request) {
        return call(HttpMethod.PUT, "/internal/projects/" + projectId + "/env-connections/" + id,
                request, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void deleteConnection(long projectId, long id) {
        call(HttpMethod.DELETE, "/internal/projects/" + projectId + "/env-connections/" + id,
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public ConnectivityTestView testConnection(long projectId, long id) {
        return call(HttpMethod.POST, "/internal/projects/" + projectId + "/env-connections/" + id + "/test",
                null, new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public DeployInfoView recordDeploy(long envId, DeployInfoRequest request) {
        return call(HttpMethod.PUT, "/internal/projects/envs/" + envId + "/deploy-info", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public DeployInfoView getDeployInfo(long envId) {
        return call(HttpMethod.GET, "/internal/projects/envs/" + envId + "/deploy-info", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public List<MemberView> listMembers(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/members", null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void addMember(long projectId, MemberAddRequest request) {
        call(HttpMethod.POST, "/internal/projects/" + projectId + "/members", request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void updateMemberRole(long projectId, long userId, MemberRoleRequest request) {
        call(HttpMethod.PUT, "/internal/projects/" + projectId + "/members/" + userId, request, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public void removeMember(long projectId, long userId) {
        call(HttpMethod.DELETE, "/internal/projects/" + projectId + "/members/" + userId, null, new ParameterizedTypeReference<>() {
        });
    }

    
    private <T> T call(HttpMethod method, String uri, Object body, ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
