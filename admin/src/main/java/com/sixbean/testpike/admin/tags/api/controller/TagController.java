package com.sixbean.testpike.admin.tags.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.tags.api.dto.CreateTagRequest;
import com.sixbean.testpike.admin.tags.api.dto.MergeTagRequest;
import com.sixbean.testpike.admin.tags.api.dto.RenameTagRequest;
import com.sixbean.testpike.admin.tags.api.dto.SetCaseTagsRequest;
import com.sixbean.testpike.admin.tags.api.dto.TagStatsView;
import com.sixbean.testpike.admin.tags.api.dto.TagView;
import com.sixbean.testpike.admin.tags.infrastructure.remote.client.CoreTagClient;
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
@RequestMapping("/api/v1/tags")
public class TagController {

    private final CoreTagClient coreTagClient;
    private final ProjectAccessGuard guard;

    public TagController(CoreTagClient coreTagClient, ProjectAccessGuard guard) {
        this.coreTagClient = coreTagClient;
        this.guard = guard;
    }

    
    @GetMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<List<TagView>> list(@RequestParam long projectId,
                                        @RequestParam(required = false) String sortBy,
                                        @RequestParam(required = false) String sortDir) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreTagClient.listTags(projectId, sortBy, sortDir));
    }

 
    @GetMapping("/stats")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<TagStatsView>> stats(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreTagClient.tagStats(projectId));
    }

    
    @GetMapping("/visible")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<TagView>> listVisible(@RequestParam String projectIds) {
        List<Long> ids = parseIds(projectIds);
        CurrentUser user = UserContext.current();
        ids.forEach(id -> guard.assertCanView(user, id));
        return Response.builderSuccess(coreTagClient.listVisibleTags(ids));
    }

    
    @GetMapping("/stats/visible")
    @RequirePermission("perm:detail:case-tag")
    public Response<List<TagStatsView>> statsVisible(@RequestParam String projectIds) {
        List<Long> ids = parseIds(projectIds);
        CurrentUser user = UserContext.current();
        ids.forEach(id -> guard.assertCanView(user, id));
        return Response.builderSuccess(coreTagClient.tagStatsVisible(ids));
    }

    
    private static List<Long> parseIds(String projectIds) {
        List<Long> ids = new java.util.ArrayList<>();
        if (projectIds == null || projectIds.isBlank()) {
            return ids;
        }
        for (String part : projectIds.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id > 0 && !ids.contains(id)) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return ids;
    }

    
    @PostMapping
    @RequirePermission("perm:detail:case-tag")
    public Response<TagView> create(@RequestBody CreateTagRequest request) {
        guard.assertCanWriteCase(UserContext.current(), request.projectId());
        return Response.builderSuccess(
                coreTagClient.createTag(request.projectId(), request.name(), request.type()));
    }

    
    @PutMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<TagView> rename(@PathVariable long id, @RequestBody RenameTagRequest request) {
        TagView current = requireTag(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        return Response.builderSuccess(coreTagClient.renameTag(id, request.newName()));
    }

 
    @PostMapping("/merge")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> merge(@RequestBody MergeTagRequest request) {
        TagView from = requireTag(request.fromTagId());
        guard.assertCanWriteCase(UserContext.current(), from.projectId());
        coreTagClient.mergeTags(request.fromTagId(), request.toTagId());
        return Response.builderSuccess(null);
    }

 
    @DeleteMapping("/{id}")
    @RequirePermission("perm:detail:case-tag")
    public Response<Void> delete(@PathVariable long id) {
        TagView current = requireTag(id);
        guard.assertCanWriteCase(UserContext.current(), current.projectId());
        coreTagClient.deleteTag(id);
        return Response.builderSuccess(null);
    }

    private TagView requireTag(long tagId) {
        return coreTagClient.getTag(tagId);
    }
}
