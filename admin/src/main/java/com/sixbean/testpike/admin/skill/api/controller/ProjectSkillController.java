package com.sixbean.testpike.admin.skill.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillBindingRequest;
import com.sixbean.testpike.admin.skill.api.dto.ProjectSkillView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.SkillClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/projects")
public class ProjectSkillController {

    private final SkillClient skillClient;
    private final SkillAccessGuard skillGuard;

    public ProjectSkillController(SkillClient skillClient, SkillAccessGuard skillGuard) {
        this.skillClient = skillClient;
        this.skillGuard = skillGuard;
    }

    
    @GetMapping("/{projectId}/skills")
    public Response<ProjectSkillView> list(@PathVariable long projectId) {
        skillGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(skillClient.projectSkills(projectId));
    }

    
    @PutMapping("/{projectId}/skills")
    public Response<ProjectSkillView> save(@PathVariable long projectId,
                                           @RequestBody ProjectSkillBindingRequest request) {
        skillGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(skillClient.saveProjectSkills(projectId, request));
    }
}
