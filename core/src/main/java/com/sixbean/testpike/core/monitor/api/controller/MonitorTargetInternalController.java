package com.sixbean.testpike.core.monitor.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.core.monitor.api.dto.MonitorEpisodeView;
import com.sixbean.testpike.core.monitor.api.dto.MonitorLinkedAssetsView;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInfo;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService;
import com.sixbean.testpike.core.monitor.domain.service.MonitorLinkedAssetService;
import com.sixbean.testpike.core.monitor.domain.service.MonitorTargetService;
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
@RequestMapping("/internal/monitor")
public class MonitorTargetInternalController {

    private final MonitorTargetService targetService;
    private final MonitorEpisodeService episodeService;
    private final MonitorLinkedAssetService linkedAssetService;

    public MonitorTargetInternalController(MonitorTargetService targetService,
                                           MonitorEpisodeService episodeService,
                                           MonitorLinkedAssetService linkedAssetService) {
        this.targetService = targetService;
        this.episodeService = episodeService;
        this.linkedAssetService = linkedAssetService;
    }

    
    @PostMapping("/targets")
    public Response<Long> create(@RequestParam long projectId, @RequestBody MonitorTargetInput input) {
        return Response.builderSuccess(targetService.create(projectId, input));
    }

    
    @PutMapping("/targets/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody MonitorTargetInput input) {
        targetService.update(id, input);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/targets/{id}")
    public Response<MonitorTargetInfo> get(@PathVariable long id) {
        return Response.builderSuccess(targetService.get(id));
    }

    
    @GetMapping("/targets")
    public Response<List<MonitorTargetInfo>> list(@RequestParam long projectId) {
        return Response.builderSuccess(targetService.list(projectId));
    }

    /** Paged target list (server-side paging channel of the wall page, S-group contract; + wall filters status/targetType/keyword). */
    @GetMapping("/targets/page")
    public Response<com.sixbean.testpike.core.monitor.api.dto.MonitorTargetPageView> page(
            @RequestParam long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "200") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String keyword) {
        return Response.builderSuccess(
                targetService.page(projectId, page, pageSize, sortBy, sortDir, status, targetType, keyword));
    }

    
    @PutMapping("/targets/{id}/enabled")
    public Response<Void> toggle(@PathVariable long id, @RequestParam boolean enabled) {
        targetService.updateEnabled(id, enabled);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/targets/{id}")
    public Response<Void> delete(@PathVariable long id) {
        targetService.delete(id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/targets/{id}/silence")
    public Response<Void> silence(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object minutes = body.get("minutes");
        targetService.silence(id, minutes == null ? 0 : ((Number) minutes).intValue(),
                (String) body.get("reason"));
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/targets/{id}/silence")
    public Response<Void> clearSilence(@PathVariable long id) {
        targetService.clearSilence(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/targets/{id}/check-logs")
    public Response<List<MonitorCheckLogView>> checkLogs(@PathVariable long id,
                                                         @RequestParam(defaultValue = "0") int limit) {
        return Response.builderSuccess(targetService.recentCheckLogs(id, limit));
    }

    
    @GetMapping("/environments/{envId}/open-episode")
    public Response<MonitorEpisodeView> openEpisode(@PathVariable long envId) {
        return Response.builderSuccess(episodeService.findOpenEpisodeByEnv(envId));
    }

    
    @GetMapping("/environments/{envId}/linked-assets")
    public Response<MonitorLinkedAssetsView> linkedAssets(@PathVariable long envId) {
        return Response.builderSuccess(linkedAssetService.linkedAssets(envId));
    }
}
