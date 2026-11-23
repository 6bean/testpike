package com.sixbean.testpike.api.ci.api.controller;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.domain.component.CiBadgeSvgRenderer;
import com.sixbean.testpike.api.ci.infrastructure.remote.client.CoreCiBatchClient;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class CiBadgeController {

    private static final MediaType SVG_TYPE = MediaType.valueOf("image/svg+xml");

    private final CoreCiBatchClient batchClient;
    private final CiBadgeSvgRenderer renderer;

    public CiBadgeController(CoreCiBatchClient batchClient, CiBadgeSvgRenderer renderer) {
        this.batchClient = batchClient;
        this.renderer = renderer;
    }

    
    @GetMapping("/open/badge/{badgeToken}.svg")
    public ResponseEntity<String> badge(@PathVariable String badgeToken) {
        CiBadgeView view = batchClient.badge(badgeToken);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
                .contentType(SVG_TYPE)
                .body(renderer.render(view));
    }
}
