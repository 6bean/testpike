package com.sixbean.testpike.admin.favorite.api.dto;


public record FavoriteView(String entityType, long entityId, String title,
                          String subtitle, String favoritedAt) {
}
