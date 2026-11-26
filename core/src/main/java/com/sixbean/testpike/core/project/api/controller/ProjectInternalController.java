package com.sixbean.testpike.core.project.api.controller;

import com.sixbean.testpike.core.project.api.dto.MemberRoleRequest;

import com.sixbean.testpike.core.project.api.dto.MemberAddRequest;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.AccessCheckRequest;
import com.sixbean.testpike.core.project.api.dto.EnvDeployInfo;
import com.sixbean.testpike.core.project.api.dto.EnvDeployInput;
import com.sixbean.testpike.core.project.api.dto.EnvHealthScanResult;
import com.sixbean.testpike.core.project.api.dto.EnvParamItem;
import com.sixbean.testpike.core.project.api.dto.EnvParamsInput;
import com.sixbean.testpike.core.project.api.dto.EnvProbeOutcome;
import com.sixbean.testpike.core.project.api.dto.MemberInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectTemplateInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;
import com.sixbean.testpike.core.project.domain.service.EnvHealthService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.project.domain.service.ProjectTemplateService;


import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/projects")
public class ProjectInternalController {

    private final ProjectService projectService;
    private final ProjectEnvService envService;
    private final MemberService memberService;
    private final ProjectAccessService accessService;
    private final EnvHealthService envHealthService;
    private final ProjectTemplateService templateService;

    public ProjectInternalController(ProjectService projectService, ProjectEnvService envService,
                                     MemberService memberService, ProjectAccessService accessService,
                                     EnvHealthService envHealthService,
                                     ProjectTemplateService templateService) {
        this.projectService = projectService;
        this.envService = envService;
        this.memberService = memberService;
        this.accessService = accessService;
        this.envHealthService = envHealthService;
        this.templateService = templateService;
    }


    
    @GetMapping
    public Response<PageResult<ProjectInfo>> list(@RequestParam long userId,
                                                  @RequestParam(defaultValue = "false") boolean allVisible,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  @RequestParam(required = false) String sortBy,
                                                  @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                projectService.list(userId, allVisible, keyword, status, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/{id}")
    public Response<ProjectInfo> get(@PathVariable long id) {
        return Response.builderSuccess(projectService.get(id));
    }

    
    @PostMapping
    public Response<Long> create(@RequestBody ProjectCreateInput input) {
        return Response.builderSuccess(projectService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}")
    public Response<ProjectInfo> update(@PathVariable long id, @RequestBody ProjectUpdateInput input) {
        return Response.builderSuccess(projectService.update(id, input));
    }

    
    @PostMapping("/{id}/archive")
    public Response<ProjectInfo> archive(@PathVariable long id) {
        return Response.builderSuccess(projectService.archive(id));
    }

 
    @GetMapping("/archived")
    public Response<PageResult<ProjectInfo>> listArchived(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "10") int size) {
        return Response.builderSuccess(projectService.listArchived(page, size));
    }

    
    @PutMapping("/{id}/restore")
    public Response<ProjectInfo> restore(@PathVariable long id) {
        return Response.builderSuccess(projectService.restore(id));
    }

    
    @GetMapping("/{id}/member-role")
    public Response<Integer> memberRole(@PathVariable long id, @RequestParam long userId) {
        return Response.builderSuccess(accessService.getMemberRole(id, userId));
    }


    
    @PostMapping("/access-check")
    public Response<Void> accessCheck(@RequestBody AccessCheckRequest request) {
        if ("OWNER".equals(request.requiredRole())) {
            accessService.requireProjectOwner(request.projectId(), request.userId());
        } else if ("MEMBER".equals(request.requiredRole())) {
            accessService.requireProjectMember(request.projectId(), request.userId());
        } else {
            throw new ServiceException("requiredRole「" + request.requiredRole()
                    + "」不是支持的取值——请改成「MEMBER」或「OWNER」后重新发起访问校验",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess();
    }


    @GetMapping("/{id}/envs")
    public Response<List<ProjectEnvInfo>> listEnvs(@PathVariable long id) {
        return Response.builderSuccess(envService.list(id));
    }

    @PostMapping("/{id}/envs")
    public Response<Long> createEnv(@PathVariable long id, @RequestBody ProjectEnvInput input) {
        return Response.builderSuccess(envService.create(id, input));
    }

    @PutMapping("/{id}/envs/{envId}")
    public Response<ProjectEnvInfo> updateEnv(@PathVariable long id, @PathVariable long envId,
                                              @RequestBody ProjectEnvInput input) {
        return Response.builderSuccess(envService.update(id, envId, input));
    }

    @DeleteMapping("/{id}/envs/{envId}")
    public Response<Void> deleteEnv(@PathVariable long id, @PathVariable long envId,
                                    @RequestParam(defaultValue = "false") boolean force) {
        envService.delete(id, envId, force);
        return Response.builderSuccess();
    }

 
    @DeleteMapping("/{id}/purge")
    public Response<Void> purge(@PathVariable long id,
                                @RequestParam(defaultValue = "false") boolean force) {
        projectService.purge(OperatorContext.currentOperatorId(), id, force);
        return Response.builderSuccess();
    }


    
    @GetMapping("/templates")
    public Response<List<ProjectTemplateInfo>> listTemplates() {
        return Response.builderSuccess(templateService.list());
    }

    
    @GetMapping("/templates/{templateId}")
    public Response<ProjectTemplateInfo> getTemplate(@PathVariable long templateId) {
        return Response.builderSuccess(templateService.get(templateId));
    }

    
    @PostMapping("/templates")
    public Response<Long> createTemplate(@RequestBody ProjectTemplateCreateInput input) {
        return Response.builderSuccess(templateService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/templates/{templateId}")
    public Response<ProjectTemplateInfo> updateTemplate(@PathVariable long templateId,
                                                        @RequestBody ProjectTemplateCreateInput input) {
        return Response.builderSuccess(templateService.update(templateId, input));
    }

    
    @DeleteMapping("/templates/{templateId}")
    public Response<Void> deleteTemplate(@PathVariable long templateId) {
        templateService.delete(templateId);
        return Response.builderSuccess();
    }


    
    @GetMapping("/envs/{envId}")
    public Response<ProjectEnvInfo> getEnvById(@PathVariable long envId) {
        return Response.builderSuccess(envService.getByEnvId(envId));
    }

    
    @PutMapping("/envs/{envId}/deploy-info")
    public Response<EnvDeployInfo> recordDeploy(@PathVariable long envId,
                                                @RequestBody EnvDeployInput input) {
        ProjectEnvInfo env = envService.getByEnvId(envId);
        return Response.builderSuccess(envService.recordDeploy(
                env.projectId(), envId, input.version(), OperatorContext.currentOperatorId()));
    }

    
    @GetMapping("/envs/{envId}/deploy-info")
    public Response<EnvDeployInfo> getDeployInfo(@PathVariable long envId) {
        ProjectEnvInfo env = envService.getByEnvId(envId);
        return Response.builderSuccess(envService.getDeployInfo(env.projectId(), envId));
    }


    
    @GetMapping("/{id}/envs/{envId}/health")
    public Response<Map<String, Object>> getEnvHealth(@PathVariable long id, @PathVariable long envId) {
        envService.get(id, envId);
        return Response.builderSuccess(envHealthService.getHealth(envId));
    }

    
    @PostMapping("/{id}/envs/{envId}/health")
    public Response<EnvProbeOutcome> probeEnvHealth(@PathVariable long id, @PathVariable long envId) {
        envService.get(id, envId);
        return Response.builderSuccess(envHealthService.scanOne(envId));
    }


    
    @GetMapping("/{id}/envs/{envId}/params")
    public Response<List<EnvParamItem>> listEnvParams(@PathVariable long id, @PathVariable long envId) {
        return Response.builderSuccess(envService.listParams(id, envId));
    }

    
    @PutMapping("/{id}/envs/{envId}/params")
    public Response<List<EnvParamItem>> saveEnvParams(@PathVariable long id, @PathVariable long envId,
                                                      @RequestBody EnvParamsInput input) {
        return Response.builderSuccess(envService.saveParams(id, envId, input));
    }

    
    @PostMapping("/env-scan")
    public Response<EnvHealthScanResult> envHealthScan() {
        return Response.builderSuccess(envHealthService.scanAll());
    }


    @GetMapping("/{id}/members")
    public Response<List<MemberInfo>> listMembers(@PathVariable long id) {
        return Response.builderSuccess(memberService.list(id));
    }

    
    @PostMapping("/{id}/members")
    public Response<Void> addMember(@PathVariable long id, @RequestBody MemberAddRequest request) {
        memberService.add(id, request.userId(), request.role());
        return Response.builderSuccess();
    }

    
    @PutMapping("/{id}/members/{userId}")
    public Response<Void> updateMemberRole(@PathVariable long id, @PathVariable long userId,
                                           @RequestBody MemberRoleRequest request) {
        memberService.updateRole(id, userId, request.role());
        return Response.builderSuccess();
    }

    
    @DeleteMapping("/{id}/members/{userId}")
    public Response<Void> removeMember(@PathVariable long id, @PathVariable long userId) {
        memberService.remove(id, userId);
        return Response.builderSuccess();
    }
}
