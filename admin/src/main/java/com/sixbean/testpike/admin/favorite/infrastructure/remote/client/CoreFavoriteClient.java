package com.sixbean.testpike.admin.favorite.infrastructure.remote.client;

import com.sixbean.testpike.admin.favorite.api.dto.FavoriteView;

import java.util.List;


public interface CoreFavoriteClient {

    
    void favorite(long userId, String entityType, long entityId);

    
    int unfavorite(long userId, String entityType, long entityId);

    
    List<FavoriteView> list(long userId, boolean allVisible, String entityType);
}
