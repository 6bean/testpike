package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.aichat.api.dto.ChatImageInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatImageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Internal door for dialog user-image uploads .
 * <p>admin forwards the multipart bytes here after its login-context and
 * H1 project guard; every semantic gate (size/extension/magic, canonical
 * key minting) stays single-sourced in core — this controller only unwraps
 * the part and pins the project segment it was addressed to.
 */
@RestController
@RequestMapping("/internal/ai-chat/images")
public class ChatImageInternalController {

    private final ChatImageService chatImageService;

    public ChatImageInternalController(ChatImageService chatImageService) {
        this.chatImageService = chatImageService;
    }

    /** Store one user image under the project segment and return the minted key. */
    @PostMapping
    public Response<ChatImageInfo> upload(@RequestParam long projectId,
                                          @RequestPart("file") MultipartFile file)
            throws IOException {
        return Response.builderSuccess(chatImageService.upload(projectId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    /**
 * Presigned GET url for one chat-image key B read door). The
 * ownership/shape/presence gates all live in the service — this endpoint
 * only carries the requesting project segment so core can enforce H1.
 */
    @GetMapping("/url")
    public Response<String> readUrl(@RequestParam long projectId,
                                    @RequestParam String objectKey) {
        return Response.builderSuccess(chatImageService.presignReadUrl(projectId, objectKey));
    }
}
