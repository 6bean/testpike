package com.sixbean.testpike.api.notify.api.controller;

import com.sixbean.testpike.api.notify.domain.service.FeishuCallbackService;
import com.sixbean.testpike.common.clientobject.Response;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class FeishuCallbackController {

    private final FeishuCallbackService callbackService;

    public FeishuCallbackController(FeishuCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    
    @PostMapping("/open/feishu/callback")
    public Response<Void> callback(@RequestBody(required = false) String rawBody,
                                   @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String ts,
                                   @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
                                   @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
                                   HttpServletRequest request) {
        callbackService.handle(rawBody == null ? "" : rawBody, ts, nonce, signature, clientIp(request));
        return Response.builderSuccess(null);
    }

    
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
