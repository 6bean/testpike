package com.sixbean.testpike.core.storage.api.controller;

import com.sixbean.testpike.core.storage.domain.service.LocalStorageFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/storage/local")
public class LocalStorageFileInternalController {

    private static final String PATH_PREFIX = "/internal/storage/local/";

    private final LocalStorageFileService localStorageFileService;

    public LocalStorageFileInternalController(LocalStorageFileService localStorageFileService) {
        this.localStorageFileService = localStorageFileService;
    }

    
    @GetMapping("/**")
    public ResponseEntity<byte[]> download(HttpServletRequest request,
                                           @RequestParam("expires") long expires,
                                           @RequestParam("signature") String signature) {
        byte[] content = localStorageFileService.download(
                extractKey(request), expires, signature);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    
    @PutMapping("/**")
    public ResponseEntity<Void> upload(HttpServletRequest request,
                                       @RequestParam("expires") long expires,
                                       @RequestParam("signature") String signature,
                                       @RequestBody(required = false) byte[] content) {
        localStorageFileService.upload(extractKey(request), expires, signature, content);
        return ResponseEntity.ok().build();
    }

    
    private static String extractKey(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        int idx = uri.indexOf(PATH_PREFIX);
        String raw = idx < 0 ? "" : uri.substring(idx + PATH_PREFIX.length());
        return java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
}
