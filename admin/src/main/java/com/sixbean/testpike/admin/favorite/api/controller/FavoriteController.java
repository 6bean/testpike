package com.sixbean.testpike.admin.favorite.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.favorite.api.dto.FavoriteView;
import com.sixbean.testpike.admin.favorite.infrastructure.remote.client.CoreFavoriteClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/favorites")
public class FavoriteController {

    private final CoreFavoriteClient coreFavoriteClient;
    private final ProjectAccessGuard guard;

    public FavoriteController(CoreFavoriteClient coreFavoriteClient, ProjectAccessGuard guard) {
        this.coreFavoriteClient = coreFavoriteClient;
        this.guard = guard;
    }

    
    @PutMapping("/{entityType}/{entityId}")
    public Response<Void> favorite(@PathVariable String entityType, @PathVariable long entityId) {
        coreFavoriteClient.favorite(UserContext.currentUserId(), entityType, entityId);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/{entityType}/{entityId}")
    public Response<Map<String, Integer>> unfavorite(@PathVariable String entityType,
                                                     @PathVariable long entityId) {
        int affected = coreFavoriteClient.unfavorite(UserContext.currentUserId(), entityType, entityId);
        return Response.builderSuccess(Map.of("affected", affected));
    }

    
    @GetMapping
    public Response<List<FavoriteView>> list(@RequestParam(required = false) String entityType) {
        long userId = UserContext.currentUserId();
        boolean allVisible = guard.isSystemManager(UserContext.current());
        return Response.builderSuccess(coreFavoriteClient.list(userId, allVisible, entityType));
    }
}
