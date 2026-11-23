package com.sixbean.testpike.admin.skill.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.skill.api.dto.SkillMarketDownloadView;
import com.sixbean.testpike.admin.skill.api.dto.SkillMarketItemView;
import com.sixbean.testpike.admin.skill.api.dto.SkillView;
import com.sixbean.testpike.admin.skill.app.component.SkillAccessGuard;
import com.sixbean.testpike.admin.skill.infrastructure.remote.client.SkillClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;


@RestController
@RequestMapping("/api/v1/skill-market")
public class SkillMarketController {

    private final SkillClient skillClient;
    private final SkillAccessGuard skillGuard;

    public SkillMarketController(SkillClient skillClient, SkillAccessGuard skillGuard) {
        this.skillClient = skillClient;
        this.skillGuard = skillGuard;
    }

 
    @RequirePermission("perm:skill:market")
    @GetMapping
    public Response<PageView<SkillMarketItemView>> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Integer type,
                                                        @RequestParam(required = false) Long tagId,
                                                        @RequestParam(required = false) String sortBy,
                                                        @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(skillClient.marketList(page, size, keyword, type,
                tagId, sortBy, sortDir));
    }

    
    @RequirePermission("perm:skill:market")
    @GetMapping("/{id}/download")
    public Response<SkillMarketDownloadView> download(@PathVariable long id,
                                                      @RequestParam String format) {
        return Response.builderSuccess(skillClient.marketDownload(id, format));
    }

    
    @PostMapping("/import")
    public Response<SkillView> importSkill(@RequestPart("file") MultipartFile file,
                                           @RequestParam(required = false) Long projectId)
            throws IOException {
        skillGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(skillClient.marketImport(projectId,
                file.getOriginalFilename(), file.getBytes()));
    }
}
