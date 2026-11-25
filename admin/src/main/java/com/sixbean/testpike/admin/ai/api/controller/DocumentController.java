package com.sixbean.testpike.admin.ai.api.controller;

import com.sixbean.testpike.admin.ai.api.dto.ChunkContextView;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionRequest;
import com.sixbean.testpike.admin.ai.api.dto.ConfirmExtractionResultView;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionEventView;
import java.util.List;
import com.sixbean.testpike.admin.ai.api.dto.DocumentCategoryRequest;
import com.sixbean.testpike.admin.ai.api.dto.DocumentInfoView;
import com.sixbean.testpike.admin.ai.api.dto.DocumentUsageSwitchesRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractRequest;
import com.sixbean.testpike.admin.ai.api.dto.ExtractionPreviewView;
import com.sixbean.testpike.admin.ai.app.service.ExtractionEventStreamBridge;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.aichat.app.component.SseFrameWriter;
import com.sixbean.testpike.admin.aichat.app.component.SseWindowSettings;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;


@RestController
@RequestMapping("/api/v1/ai/documents")
public class DocumentController {

    private final CoreDocumentClient coreDocumentClient;
    private final ProjectAccessGuard guard;
    private final ExtractionEventStreamBridge eventStreamBridge;
    private final SseWindowSettings sseWindow;

    public DocumentController(CoreDocumentClient coreDocumentClient, ProjectAccessGuard guard,
                              ExtractionEventStreamBridge eventStreamBridge,
                              SseWindowSettings sseWindow) {
        this.coreDocumentClient = coreDocumentClient;
        this.guard = guard;
        this.eventStreamBridge = eventStreamBridge;
        this.sseWindow = sseWindow;
    }

    /**
 * The optional sort pair forwards to the core column whitelist (single gate). */
    @GetMapping
    public Response<PageView<DocumentInfoView>> list(@RequestParam long projectId,
                                                     @RequestParam(required = false) Integer status,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String category,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String sortBy,
                                                     @RequestParam(required = false) String sortDir) {
        CurrentUser user = UserContext.current();
        guard.assertCanView(user, projectId);
        return Response.builderSuccess(
                coreDocumentClient.list(projectId, status, keyword, category, page, size, sortBy, sortDir));
    }

    
    @PostMapping
    public Response<DocumentInfoView> upload(@RequestParam long projectId,
                                             @RequestParam(required = false) String category,
                                             @RequestPart("file") MultipartFile file) throws IOException {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, projectId);
        return Response.builderSuccess(coreDocumentClient.upload(projectId,
                file.getBytes(), file.getOriginalFilename(), category));
    }

    
    @GetMapping("/{id}")
    public Response<DocumentInfoView> get(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(doc);
    }

 
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        coreDocumentClient.delete(id);
        return Response.builderSuccess(null);
    }

    /**
     * Field-level category edit (PATCH /ai/documents/{id}/category): write
     * door = same requirement-input surface as upload/delete; the caller's
     * revision is consumed by core (stale=40901 naming the current version,
     * missing=40001). get-first keeps the 404 human-readable message ahead.
     */
    @PatchMapping("/{id}/category")
    public Response<DocumentInfoView> updateCategory(@PathVariable long id,
                                                     @RequestBody DocumentCategoryRequest request) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.updateCategory(id, request));
    }

    /** Per-document usage switches edit (PATCH /ai/documents/{id}/usage-switches). */
    @PatchMapping("/{id}/usage-switches")
    public Response<DocumentInfoView> updateUsageSwitches(
            @PathVariable long id, @RequestBody DocumentUsageSwitchesRequest request) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.updateUsageSwitches(id, request));
    }

 
    @PostMapping("/{id}/extract")
    public Response<DocumentInfoView> extract(@PathVariable long id,
                                              @RequestBody(required = false) ExtractRequest request) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.extract(id,
                request == null ? new ExtractRequest(null) : request));
    }

    /**
     * Explicit cancel of a running extraction (self-recovery lane): core rotates the
     * generation token, settles the row back to parsed(1) with the cancel reason and the
     * in-flight run self-aborts at its next seam. Write door = same surface as
     * upload/extract; "nothing is extracting" comes back as core's honest 409.
     */
    @PostMapping("/{id}/extraction-cancel")
    public Response<DocumentInfoView> cancelExtraction(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.cancelExtraction(id));
    }

    
    @GetMapping("/{id}/extraction")
    public Response<ExtractionPreviewView> extraction(@PathVariable long id,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.extraction(id, page, size));
    }

    /** Cursor window of the extraction's process events tail-f
 * observation face, read-only; ownership gate = assertCanView, same as
 * the preview read; an absent limit defers to the core hot key). */
    @GetMapping("/{id}/extraction-events")
    public Response<List<ExtractionEventView>> extractionEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String runToken) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.extractionEvents(id, cursor, limit, runToken));
    }

    /**
 * Per-run revisit door of the extraction process tail (R67): lets the
 * preview dialog tell "frames aged out of retention" apart from "this run
 * produced nothing"; read-only, ownership gate = assertCanView like the
 * extraction-events door above.
 */
    @GetMapping("/{id}/extraction-runs")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>> extractionRuns(
            @PathVariable long id,
            @RequestParam(required = false) Integer limit) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.extractionRuns(id, limit));
    }

    /**
 * Cursor window of the newest channel-B spec-extraction attempt's
 * process events : read-only tail for the import
 * wizard's sub-window; ownership gate = assertCanView, same as the
 * preview read; an absent limit defers to the core hot key.
 */
    @GetMapping("/{id}/spec-extraction-events")
    public Response<List<ExtractionEventView>> specExtractionEvents(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String runToken) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.specExtractionEvents(id, cursor, limit, runToken));
    }

    /**
 * Per-run revisit door of the spec extraction process tail (R67): the
 * import list's row-level entry reads these rows so the history outlives
 * the wizard; read-only, ownership gate = assertCanView like the doors
 * above.
 */
    @GetMapping("/{id}/spec-extraction-runs")
    public Response<List<com.sixbean.testpike.admin.ai.api.dto.AiTaskRunView>> specExtractionRuns(
            @PathVariable long id,
            @RequestParam(required = false) Integer limit) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.specExtractionRuns(id, limit));
    }

    /**
 * Live tail of the extraction's process events : one SSE
 * stream per open drawer, frames ride in strictly increasing cursor order
 * and the stream seals when the document settles. EventSource cannot carry
 * the login token, so the browser consumes this with fetch + ReadableStream
 * exactly like the dialog stream door; when the wire drops, the viewer
 * degrades to the plain cursor window above (poll fallback) and reopens
 * from the last cursor it saw — nothing is skipped, nothing replays.
 */
    @GetMapping("/{id}/extraction-events/stream")
    public SseEmitter extractionEventStream(@PathVariable long id,
                                            @RequestParam(defaultValue = "0") long cursor,
                                            HttpServletResponse response) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        // Proxy buffering would batch the frames and recreate the dead-spinner
        // failure the tail exists to prevent (same header pair as the dialog door).
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        SseEmitter emitter = sseWindow.openEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);
        // Virtual thread per drawer: the pump blocks on cursor polls, which is
        // exactly what virtual threads are for.
        Thread.ofVirtual().name("doc-extract-tail-" + id).start(() -> {
            try {
                eventStreamBridge.stream(id, cursor, writer);
            } catch (RuntimeException pumpDied) {
                writer.write("error", cursor + 1,
                        "提取过程通道暂时不可用：" + pumpDied.getMessage(), null);
                writer.complete();
            }
        });
        return emitter;
    }

    /**
 * WR batch segment C: jump-to-source context door for the extraction
 * preview (requested chunk verbatim + preceding neighbours + position
 * marker), so a thin chunk can be read inside its surrounding original
 * text. Read gate = project member assertCanView (same order as the detail
 * endpoint). Ownership of the chunk id is enforced single-sourced by core —
 * a foreign or stale id arrives as a found=false answer carrying no text
 * (never another document's content), so the door stays an honest 200.
 */
    @GetMapping("/{id}/chunks/{chunkId}")
    public Response<ChunkContextView> chunk(@PathVariable long id, @PathVariable long chunkId) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanView(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.chunkContext(id, chunkId));
    }

    
    @PostMapping("/{id}/confirm-extraction")
    public Response<ConfirmExtractionResultView> confirmExtraction(
            @PathVariable long id, @RequestBody ConfirmExtractionRequest request) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.confirmExtraction(id, request));
    }

    /** Full-store confirm door : every preview item lands in one core
 * transaction regardless of how the viewer pages; write gate is the very
 * same surface as the checkbox door, so no permission widens here. */
    @PostMapping("/{id}/confirm-all-extraction")
    public Response<ConfirmExtractionResultView> confirmAllExtraction(@PathVariable long id) {
        CurrentUser user = UserContext.current();
        DocumentInfoView doc = coreDocumentClient.get(id);
        guard.assertCanWriteRequirement(user, doc.projectId());
        return Response.builderSuccess(coreDocumentClient.confirmAllExtraction(id));
    }
}
