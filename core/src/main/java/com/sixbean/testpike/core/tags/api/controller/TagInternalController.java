package com.sixbean.testpike.core.tags.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.tags.api.dto.CaseTagBatchInput;
import com.sixbean.testpike.core.tags.api.dto.CaseTagSetInput;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.api.dto.TagRenameInput;
import com.sixbean.testpike.core.tags.api.dto.TagStats;
import com.sixbean.testpike.core.tags.domain.service.TagService;
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
@RequestMapping("/internal/tags")
public class TagInternalController {

    private final TagService tagService;

    public TagInternalController(TagService tagService) {
        this.tagService = tagService;
    }

    
    @PostMapping
    public Response<TagInfo> create(@RequestBody TagCreateInput input) {
        return Response.builderSuccess(
                tagService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @PutMapping("/{id}")
    public Response<TagInfo> rename(@PathVariable long id, @RequestBody TagRenameInput input) {
        return Response.builderSuccess(
                tagService.rename(OperatorContext.currentOperatorId(), id, input));
    }

    
    @PostMapping("/merge")
    public Response<Void> merge(@RequestBody TagMergeInput input) {
        tagService.merge(OperatorContext.currentOperatorId(), input);
        return Response.builderSuccess(null);
    }

 
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        tagService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping
    public Response<List<TagInfo>> list(@RequestParam long projectId,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(tagService.list(projectId, sortBy, sortDir));
    }

    
    @GetMapping("/visible")
    public Response<List<TagInfo>> listVisible(@RequestParam List<Long> projectIds) {
        return Response.builderSuccess(tagService.listVisible(projectIds));
    }

    
    @GetMapping("/{id}")
    public Response<TagInfo> get(@PathVariable long id) {
        return Response.builderSuccess(tagService.get(id));
    }

 
    @GetMapping("/stats")
    public Response<List<TagStats>> stats(@RequestParam long projectId) {
        return Response.builderSuccess(tagService.stats(projectId));
    }

    
    @GetMapping("/stats/visible")
    public Response<List<TagStats>> statsVisible(@RequestParam List<Long> projectIds) {
        return Response.builderSuccess(tagService.statsVisible(projectIds));
    }

    
    @PostMapping("/by-cases")
    public Response<Map<Long, List<TagInfo>>> tagsByCases(@RequestBody CaseTagBatchInput input) {
        return Response.builderSuccess(tagService.tagsByCases(input.projectId(), input.caseIds()));
    }

 
    @PutMapping("/cases/{caseId}/tags")
    public Response<List<TagInfo>> setCaseTags(@PathVariable long caseId,
                                               @RequestBody CaseTagSetInput input) {
        return Response.builderSuccess(
                tagService.setCaseTags(OperatorContext.currentOperatorId(), caseId, input));
    }

    
    @GetMapping("/cases/{caseId}/tags")
    public Response<List<TagInfo>> caseTags(@PathVariable long caseId) {
        return Response.builderSuccess(tagService.caseTags(caseId));
    }
}
