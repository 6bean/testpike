package com.sixbean.testpike.core.favorite.api.dto;


public record FavoriteItem(String entityType, long entityId, String title,
                          String subtitle, String favoritedAt) {
}
