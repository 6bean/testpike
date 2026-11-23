package com.sixbean.testpike.admin.tool.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.admin.tool.api.dto.ToolMarketItemView;
import com.sixbean.testpike.admin.tool.api.dto.ToolView;
import com.sixbean.testpike.admin.tool.app.component.ToolAccessGuard;
import com.sixbean.testpike.admin.tool.infrastructure.remote.client.ToolClient;
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
@RequestMapping("/api/v1/tool-market")
public class ToolMarketController {

    private final ToolClient toolClient;
    private final ToolAccessGuard toolGuard;

    public ToolMarketController(ToolClient toolClient, ToolAccessGuard toolGuard) {
        this.toolClient = toolClient;
        this.toolGuard = toolGuard;
    }

    
    @GetMapping
    public Response<PageView<ToolMarketItemView>> list(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) Integer type,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) String sortBy,
                                                       @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(toolClient.marketList(page, size, keyword, type,
                category, sortBy, sortDir));
    }

    
    @GetMapping("/{id}/download")
    public Response<ToolMarketDownloadView> download(@PathVariable long id,
                                                     @RequestParam String format) {
        return Response.builderSuccess(toolClient.marketDownload(id, format));
    }

    
    @PostMapping("/import")
    public Response<ToolView> importTool(@RequestPart("file") MultipartFile file,
                                         @RequestParam(required = false) Long projectId)
            throws IOException {
        toolGuard.assertCanManage(UserContext.current(), projectId);
        return Response.builderSuccess(toolClient.marketImport(projectId,
                file.getOriginalFilename(), file.getBytes()));
    }
}
