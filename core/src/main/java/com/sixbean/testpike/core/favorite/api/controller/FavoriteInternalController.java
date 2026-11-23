package com.sixbean.testpike.core.favorite.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.favorite.api.dto.FavoriteItem;
import com.sixbean.testpike.core.favorite.domain.service.FavoriteService;
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
@RequestMapping("/internal/favorites")
public class FavoriteInternalController {

    private final FavoriteService favoriteService;

    public FavoriteInternalController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    
    @PutMapping("/{entityType}/{entityId}")
    public Response<Void> favorite(@RequestParam long userId,
                                   @PathVariable String entityType,
                                   @PathVariable long entityId) {
        favoriteService.favorite(userId, entityType, entityId);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/{entityType}/{entityId}")
    public Response<Map<String, Integer>> unfavorite(@RequestParam long userId,
                                                     @PathVariable String entityType,
                                                     @PathVariable long entityId) {
        return Response.builderSuccess(Map.of("affected", favoriteService.unfavorite(userId, entityType, entityId)));
    }

    
    @GetMapping
    public Response<List<FavoriteItem>> list(@RequestParam long userId,
                                             @RequestParam(defaultValue = "false") boolean allVisible,
                                             @RequestParam(required = false) String entityType) {
        return Response.builderSuccess(favoriteService.listFavorites(userId, allVisible, entityType));
    }
}
