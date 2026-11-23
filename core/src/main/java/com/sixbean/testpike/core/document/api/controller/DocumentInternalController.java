package com.sixbean.testpike.core.document.api.controller;

import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.document.api.dto.ChunkContext;
import com.sixbean.testpike.core.document.api.dto.DocumentCategoryUpdate;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.api.dto.DocumentUsageSwitchesUpdate;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/documents")
public class DocumentInternalController {

    private final DocumentService documentService;

    public DocumentInternalController(DocumentService documentService) {
        this.documentService = documentService;
    }

    
    @PostMapping
    public Response<DocumentInfo> upload(@RequestParam long projectId,
                                         @RequestParam(required = false) String category,
                                         @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(documentService.upload(OperatorContext.currentOperatorId(),
                projectId, file.getOriginalFilename(), file.getBytes(), category));
    }

    /**
 * The optional sort pair reaches the core column whitelist (single gate). */
    @GetMapping
    public Response<PageResult<DocumentInfo>> list(@RequestParam long projectId,
                                                   @RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) String sortBy,
                                                   @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                documentService.list(projectId, status, keyword, category, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/{id}")
    public Response<DocumentInfo> get(@PathVariable long id) {
        return Response.builderSuccess(documentService.get(id));
    }

 
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        documentService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /**
 * Field-level category edit : consumes the caller's
 * revision (stale=40901 naming the current version, missing=40001).
 */
    @PatchMapping("/{id}/category")
    public Response<DocumentInfo> updateCategory(@PathVariable long id,
                                                 @RequestBody DocumentCategoryUpdate update) {
        return Response.builderSuccess(documentService.updateCategory(
                OperatorContext.currentOperatorId(), id, update.category(), update.revision()));
    }

    /** Per-document usage switches edit : all three required, same lock. */
    @PatchMapping("/{id}/usage-switches")
    public Response<DocumentInfo> updateUsageSwitches(@PathVariable long id,
                                                      @RequestBody DocumentUsageSwitchesUpdate req) {
        return Response.builderSuccess(documentService.updateUsageSwitches(
                OperatorContext.currentOperatorId(), id, req.useForExtraction(),
                req.useInCasePrompt(), req.useInGrounding(), req.revision()));
    }

 
    @GetMapping("/{id}/chunks")
    public Response<List<Map<String, Object>>> chunks(@PathVariable long id) {
        return Response.builderSuccess(documentService.chunks(id));
    }

    /**
 * Jump-to-source context door (WR batch segment C, consumed by the admin
 * user-facing chunk read): the requested chunk plus its preceding neighbours
 * and position marker. A foreign or stale chunk id is refused here with the
 * honest core message — the admin bridge passes it through, never re-probes
 * ownership by streaming the whole chunk list.
 */
    @GetMapping("/{id}/chunks/{chunkId}/context")
    public Response<ChunkContext> chunkContext(@PathVariable long id,
                                               @PathVariable long chunkId) {
        return Response.builderSuccess(documentService.chunkContext(id, chunkId));
    }
}
