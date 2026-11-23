package com.sixbean.testpike.core.skill.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillBindingInput;
import com.sixbean.testpike.core.skill.api.dto.ProjectSkillView;
import com.sixbean.testpike.core.skill.api.dto.SkillAssemblyItem;
import com.sixbean.testpike.core.skill.api.dto.SkillCreateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketDownloadView;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketItem;
import com.sixbean.testpike.core.skill.api.dto.SkillMarketQuery;
import com.sixbean.testpike.core.skill.api.dto.SkillQuery;
import com.sixbean.testpike.core.skill.api.dto.SkillRecycleInfo;
import com.sixbean.testpike.core.skill.api.dto.SkillRejectInput;
import com.sixbean.testpike.core.skill.api.dto.SkillUpdateInput;
import com.sixbean.testpike.core.skill.api.dto.SkillUsageInput;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionDetail;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionInfo;
import com.sixbean.testpike.core.skill.domain.service.ProjectSkillService;
import com.sixbean.testpike.core.skill.domain.service.SkillConsumptionService;
import com.sixbean.testpike.core.skill.domain.service.SkillMarketService;
import com.sixbean.testpike.core.skill.domain.service.SkillService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/internal/skills")
public class SkillInternalController {

    private final SkillService skillService;
    private final SkillMarketService skillMarketService;
    private final ProjectSkillService projectSkillService;
    private final SkillConsumptionService skillConsumptionService;

    public SkillInternalController(SkillService skillService,
                                   SkillMarketService skillMarketService,
                                   ProjectSkillService projectSkillService,
                                   SkillConsumptionService skillConsumptionService) {
        this.skillService = skillService;
        this.skillMarketService = skillMarketService;
        this.projectSkillService = projectSkillService;
        this.skillConsumptionService = skillConsumptionService;
    }

 
    @GetMapping
    public Response<PageResult<SkillInfo>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer type,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) Long tagId,
                                                @RequestParam(required = false) String sortBy,
                                                @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(skillService.list(
                new SkillQuery(page, size, keyword, type, status, projectId, tagId, sortBy, sortDir)));
    }

 
    @PostMapping
    public Response<SkillInfo> create(@RequestBody SkillCreateInput input) {
        return Response.builderSuccess(
                skillService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping("/{id}")
    public Response<SkillInfo> get(@PathVariable long id) {
        return Response.builderSuccess(skillService.get(id));
    }

    
    @PutMapping("/{id}")
    public Response<SkillInfo> update(@PathVariable long id, @RequestBody SkillUpdateInput input) {
        return Response.builderSuccess(
                skillService.update(OperatorContext.currentOperatorId(), id, input));
    }

 
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id, @RequestParam int expectedVersion) {
        skillService.delete(OperatorContext.currentOperatorId(), id, expectedVersion);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/recycle-bin")
    public Response<PageResult<SkillRecycleInfo>> recycleBin(@RequestParam(required = false) Long projectId,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(skillService.recycleBin(projectId, keyword, page, size));
    }

    
    @GetMapping("/{id}/recycle-entry")
    public Response<SkillRecycleInfo> recycleEntry(@PathVariable long id) {
        return Response.builderSuccess(skillService.recycleEntry(id));
    }

 
    @PutMapping("/{id}/restore")
    public Response<SkillInfo> restore(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                skillService.restore(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

    
    @PostMapping("/{id}/submit")
    public Response<SkillInfo> submit(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                skillService.submit(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

 
    @PostMapping("/{id}/approve")
    public Response<SkillInfo> approve(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                skillService.approve(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

 
    @PostMapping("/{id}/reject")
    public Response<SkillInfo> reject(@PathVariable long id, @RequestBody SkillRejectInput input) {
        return Response.builderSuccess(
                skillService.reject(OperatorContext.currentOperatorId(), id,
                        input.expectedVersion(), input.reason()));
    }

    
    @PostMapping("/{id}/retire")
    public Response<SkillInfo> retire(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                skillService.retire(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

    
    @GetMapping("/{id}/versions")
    public Response<List<SkillVersionInfo>> versions(@PathVariable long id) {
        return Response.builderSuccess(skillService.listVersions(id));
    }

 
    @GetMapping("/{id}/versions/{version}")
    public Response<SkillVersionDetail> version(@PathVariable long id, @PathVariable int version) {
        return Response.builderSuccess(skillService.getVersion(id, version));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

 
    @GetMapping("/market")
    public Response<PageResult<SkillMarketItem>> marketList(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(required = false) String keyword,
                                                            @RequestParam(required = false) Integer type,
                                                            @RequestParam(required = false) Long tagId,
                                                            @RequestParam(required = false) String sortBy,
                                                            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(skillMarketService.marketList(
                new SkillMarketQuery(page, size, keyword, type, tagId, sortBy, sortDir)));
    }

 
    @GetMapping("/market/{id}/download")
    public Response<SkillMarketDownloadView> marketDownload(@PathVariable long id,
                                                            @RequestParam String format) {
        return Response.builderSuccess(skillMarketService.download(id, format));
    }

 
    @PostMapping("/market/import")
    public Response<SkillInfo> marketImport(@RequestParam(required = false) Long projectId,
                                            @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(skillMarketService.importSkill(
                OperatorContext.currentOperatorId(), projectId,
                file.getOriginalFilename(), file.getBytes()));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    @GetMapping("/project/{pid}")
    public Response<ProjectSkillView> projectSkills(@PathVariable long pid) {
        return Response.builderSuccess(projectSkillService.getBindings(pid));
    }

    
    @PutMapping("/project/{pid}")
    public Response<ProjectSkillView> saveProjectSkills(@PathVariable long pid,
                                                        @RequestBody ProjectSkillBindingInput input) {
        return Response.builderSuccess(projectSkillService.saveBindings(
                OperatorContext.currentOperatorId(), pid, input));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    @GetMapping("/project/{pid}/assembly")
    public Response<List<SkillAssemblyItem>> projectAssembly(@PathVariable long pid) {
        return Response.builderSuccess(skillConsumptionService.getAssemblyItems(pid));
    }

    
    @PostMapping("/usage")
    public Response<Integer> recordSkillUsage(@RequestBody List<SkillUsageInput> usages) {
        return Response.builderSuccess(skillConsumptionService.recordUsages(usages));
    }
}
