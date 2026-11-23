package com.sixbean.testpike.admin.skill.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.skill.api.dto.SkillCreateRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillRecycleView;
import com.sixbean.testpike.admin.skill.api.dto.SkillRejectRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillUpdateRequest;
import com.sixbean.testpike.admin.skill.api.dto.SkillVersionDetailView;
import com.sixbean.testpike.admin.skill.api.dto.SkillVersionView;
import com.sixbean.testpike.admin.skill.api.dto.SkillView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.SkillClient;
import com.sixbean.testpike.common.clientobject.Response;
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


@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillClient skillClient;
    private final SkillAccessGuard skillGuard;
    private final ProjectAccessGuard projectGuard;

    public SkillController(SkillClient skillClient, SkillAccessGuard skillGuard,
                           ProjectAccessGuard projectGuard) {
        this.skillClient = skillClient;
        this.skillGuard = skillGuard;
        this.projectGuard = projectGuard;
    }

    
    @RequirePermission("perm:skill:market")
    @GetMapping
    public Response<PageView<SkillView>> list(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer type,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(required = false) Long tagId,
                                              @RequestParam(required = false) String sortBy,
                                              @RequestParam(required = false) String sortDir,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        if (projectId != null) {
            projectGuard.assertCanView(user, projectId);
        }
        return Response.builderSuccess(skillClient.list(keyword, type, status, projectId,
                tagId, sortBy, sortDir, page, size));
    }

    
    @PostMapping
    public Response<SkillView> create(@RequestBody SkillCreateRequest request) {
        skillGuard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(skillClient.create(request));
    }

    
    @RequirePermission("perm:skill:market")
    @GetMapping("/{id}")
    public Response<SkillView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        SkillView skill = skillClient.get(id);
        if (skill.projectId() != null) {
            projectGuard.assertCanView(user, skill.projectId());
        }
        return Response.builderSuccess(skill);
    }

    
    @PutMapping("/{id}")
    public Response<SkillView> update(@PathVariable long id, @RequestBody SkillUpdateRequest request) {
        SkillView skill = skillClient.get(id);
        skillGuard.assertCanManage(UserContext.current(), skill.projectId());
        return Response.builderSuccess(skillClient.update(id, request));
    }

 
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id, @RequestParam int expectedVersion) {
        SkillView skill = skillClient.get(id);
        skillGuard.assertCanManage(UserContext.current(), skill.projectId());
        skillClient.delete(id, expectedVersion);
        return Response.builderSuccess(null);
    }

    
    @RequirePermission("perm:tool:tool-market")
    @GetMapping("/recycle-bin")
    public Response<PageView<SkillRecycleView>> recycleBin(@RequestParam(required = false) Long projectId,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        skillGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(skillClient.recycleBin(projectId, keyword, page, size));
    }

    
    @RequirePermission("perm:tool:tool-market")
    @PutMapping("/{id}/restore")
    public Response<SkillView> restore(@PathVariable long id, @RequestParam int expectedVersion) {
        SkillRecycleView entry = skillClient.recycleEntry(id);
        skillGuard.assertCanManage(UserContext.current(), entry.projectId());
        return Response.builderSuccess(skillClient.restore(id, expectedVersion));
    }

    
    @PostMapping("/{id}/submit")
    public Response<SkillView> submit(@PathVariable long id, @RequestParam int expectedVersion) {
        CurrentUser user = UserContext.current();
        SkillView skill = skillClient.get(id);
        skillGuard.assertCanSubmit(user, skill);
        return Response.builderSuccess(skillClient.submit(id, expectedVersion));
    }

    
    @PostMapping("/{id}/approve")
    public Response<SkillView> approve(@PathVariable long id, @RequestParam int expectedVersion) {
        SkillView skill = skillClient.get(id);
        skillGuard.assertCanManage(UserContext.current(), skill.projectId());
        return Response.builderSuccess(skillClient.approve(id, expectedVersion));
    }

    
    @PostMapping("/{id}/reject")
    public Response<SkillView> reject(@PathVariable long id, @RequestBody SkillRejectRequest request) {
        SkillView skill = skillClient.get(id);
        skillGuard.assertCanManage(UserContext.current(), skill.projectId());
        return Response.builderSuccess(skillClient.reject(id, request));
    }

    
    @PostMapping("/{id}/retire")
    public Response<SkillView> retire(@PathVariable long id, @RequestParam int expectedVersion) {
        SkillView skill = skillClient.get(id);
        skillGuard.assertCanManage(UserContext.current(), skill.projectId());
        return Response.builderSuccess(skillClient.retire(id, expectedVersion));
    }

    
    @RequirePermission("perm:skill:market")
    @GetMapping("/{id}/versions")
    public Response<List<SkillVersionView>> versions(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        SkillView skill = skillClient.get(id);
        if (skill.projectId() != null) {
            projectGuard.assertCanView(user, skill.projectId());
        }
        return Response.builderSuccess(skillClient.versions(id));
    }

 
    @RequirePermission("perm:skill:market")
    @GetMapping("/{id}/versions/{version}")
    public Response<SkillVersionDetailView> version(@PathVariable long id, @PathVariable int version) {
        CurrentUser user = UserContext.current();
        SkillView skill = skillClient.get(id);
        if (skill.projectId() != null) {
            projectGuard.assertCanView(user, skill.projectId());
        }
        return Response.builderSuccess(skillClient.version(id, version));
    }
}
