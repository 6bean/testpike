package com.sixbean.testpike.core.tool.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketItem;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketQuery;
import com.sixbean.testpike.core.tool.domain.service.ToolMarketService;
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
@RequestMapping("/internal/tools/market")
public class ToolMarketController {

    private final ToolMarketService toolMarketService;

    public ToolMarketController(ToolMarketService toolMarketService) {
        this.toolMarketService = toolMarketService;
    }

    
    @GetMapping
    public Response<PageResult<ToolMarketItem>> marketList(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           @RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) Integer type,
                                                           @RequestParam(required = false) String category,
                                                           @RequestParam(required = false) String sortBy,
                                                           @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(toolMarketService.marketList(
                new ToolMarketQuery(page, size, keyword, type, category, sortBy, sortDir)));
    }

    
    @GetMapping("/{id}/download")
    public Response<ToolMarketDownloadView> marketDownload(@PathVariable long id,
                                                           @RequestParam String format) {
        return Response.builderSuccess(toolMarketService.download(id, format));
    }

    
    @PostMapping("/import")
    public Response<ToolInfo> marketImport(@RequestParam(required = false) Long projectId,
                                           @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(toolMarketService.importTool(
                OperatorContext.currentOperatorId(), projectId,
                file.getOriginalFilename(), file.getBytes()));
    }
}
