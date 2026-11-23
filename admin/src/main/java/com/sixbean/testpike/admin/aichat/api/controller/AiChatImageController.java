package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.aichat.api.dto.ChatImageView;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreChatImageClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Upload door for images the user attaches to a dialog question (AO batch
 * P4 ; browser entry = the chat composer's photo attach).
 * <p>Thin by rule: the login identity comes from the session context, the
 * H1 project guard runs before core is reached at all (uploading into
 * someone else's segment is horizontal access, refused with the project
 * named), and the size/extension/magic triple gate plus canonical key
 * minting stay single-sourced in core — no second copy here.
 */
@RestController
@RequestMapping("/api/v1/ai/chat/images")
public class AiChatImageController {

    private final CoreChatImageClient chatImageClient;
    private final ProjectAccessGuard accessGuard;

    public AiChatImageController(CoreChatImageClient chatImageClient,
                                 ProjectAccessGuard accessGuard) {
        this.chatImageClient = chatImageClient;
        this.accessGuard = accessGuard;
    }

    /** Upload one chat image into the caller's project segment. */
    @PostMapping
    @RequirePermission("perm:aichat:use")
    public Response<ChatImageView> upload(@RequestParam long projectId,
                                          @RequestPart("file") MultipartFile file)
            throws IOException {
        CurrentUser user = UserContext.current();
        accessGuard.assertCanView(user, projectId);
        return Response.builderSuccess(chatImageClient.uploadChatImage(projectId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }
}
