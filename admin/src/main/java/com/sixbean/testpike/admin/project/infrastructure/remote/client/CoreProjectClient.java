package com.sixbean.testpike.admin.project.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.MemberRoleRequest;

import com.sixbean.testpike.admin.project.api.dto.MemberAddRequest;

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
import com.sixbean.testpike.admin.project.api.dto.SaveProjectTemplateRequest;
import com.sixbean.testpike.admin.project.api.dto.ProjectView;
import com.sixbean.testpike.admin.project.api.dto.UpdateProjectRequest;


import java.util.List;


public interface CoreProjectClient {

    /**
 * The optional sort pair forwards to the core column whitelist (single gate). */
    PageView<ProjectView> listProjects(boolean allVisible, String keyword, Integer status, int page, int size,
                                       String sortBy, String sortDir);

    
    ProjectView getProject(long projectId);

    
    long createProject(CreateProjectRequest request);

    
    java.util.List<ProjectTemplateView> listTemplates();

    
    ProjectTemplateView getTemplate(long templateId);

    
    long createTemplate(SaveProjectTemplateRequest request);

    
    ProjectTemplateView updateTemplate(long templateId, SaveProjectTemplateRequest request);

    
    void deleteTemplate(long templateId);

    
    ProjectView updateProject(long projectId, UpdateProjectRequest request);

    
    ProjectView archiveProject(long projectId);

 
    PageView<ProjectView> listArchived(int page, int size);

    
    ProjectView restoreProject(long projectId);

    
    void purgeProject(long projectId, boolean force);

    
    int memberRole(long projectId, long userId);

    
    List<ProjectEnvView> listEnvs(long projectId);

    
    long createEnv(long projectId, EnvRequest request);

    
    ProjectEnvView updateEnv(long projectId, long envId, EnvRequest request);

    void deleteEnv(long projectId, long envId);

    
    void deleteEnv(long projectId, long envId, boolean force);

    
    ProjectEnvView getEnvById(long envId);

 
    EnvHealthView getEnvHealth(long projectId, long envId);

 
    EnvProbeView probeEnvHealth(long projectId, long envId);

 
    List<EnvParamView> listEnvParams(long projectId, long envId);

 
    List<EnvParamView> saveEnvParams(long projectId, long envId, EnvParamsRequest request);

 
    List<EnvConnectionView> listConnections(long projectId);

 
    long createConnection(long projectId, EnvConnectionRequest request);

 
    EnvConnectionView updateConnection(long projectId, long id, EnvConnectionRequest request);

 
    void deleteConnection(long projectId, long id);

 
    ConnectivityTestView testConnection(long projectId, long id);

    
    DeployInfoView recordDeploy(long envId, DeployInfoRequest request);

    
    DeployInfoView getDeployInfo(long envId);

    
    List<MemberView> listMembers(long projectId);

    
    void addMember(long projectId, MemberAddRequest request);

    
    void updateMemberRole(long projectId, long userId, MemberRoleRequest request);

    
    void removeMember(long projectId, long userId);
}
