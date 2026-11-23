package com.sixbean.testpike.core.favorite.domain.service;

import com.sixbean.testpike.core.favorite.api.dto.FavoriteItem;

import java.util.List;


public interface FavoriteService {

    
    void favorite(long userId, String entityType, long entityId);

    
    int unfavorite(long userId, String entityType, long entityId);

    
    List<FavoriteItem> listFavorites(long userId, boolean allVisible, String entityType);
}
